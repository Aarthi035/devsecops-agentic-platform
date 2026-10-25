package com.devsecops.agentservice.action;

import com.devsecops.agentservice.config.AgentProperties;
import com.devsecops.agentservice.error.AgentUpstreamException;
import com.devsecops.agentservice.tools.ClusterTls;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The live {@link ActionExecutor}: the only class in the agent that changes
 * anything.
 *
 * <p>Every method here is a PATCH, and every PATCH is a change to a workload's
 * <em>desired state</em> that the controller then reconciles. Nothing deletes an
 * object, nothing touches a namespace, and nothing is applied from a document
 * the caller supplied - the bodies are built here from the proposal's validated
 * fields, so the set of things this can do is exactly the three
 * {@link ActionType}s.
 *
 * <p>Three details worth knowing:
 *
 * <ul>
 *   <li><b>It needs its own credential and refuses without one.</b> {@code
 *       agent.tools.executor-token} (or a mounted file) is separate from the
 *       read-only ServiceAccount token, and blank means "this deployment cannot
 *       act". That refusal is checked before anything else, so a deployment that
 *       only ever intended to diagnose stays a deployment that only diagnoses
 *       even if a bug hands it a proposal.</li>
 *   <li><b>A restart is an annotation, not a delete.</b> The rolling restart is
 *       the same one {@code kubectl rollout restart} does: a timestamp on the
 *       pod template, which the deployment controller turns into a new
 *       ReplicaSet and a gradual replacement. Deleting pods would be faster and
 *       would take the service down, which is not what "restart" should mean.</li>
 *   <li><b>A rollback restores a template, it does not re-run a command.</b>
 *       The previous ReplicaSet's {@code spec.template} is copied onto the
 *       Deployment, which is what {@code kubectl rollout undo} does and is the
 *       only way to roll back that does not depend on an external revision
 *       history.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "agent.tools.mode", havingValue = "live", matchIfMissing = true)
public class KubernetesActionExecutor implements ActionExecutor {

    private static final Logger log = LoggerFactory.getLogger(KubernetesActionExecutor.class);

    private static final String USER_AGENT = "devsecops-agentic-platform";

    /** The annotation {@code kubectl rollout restart} sets. */
    private static final String RESTARTED_AT = "kubectl.kubernetes.io/restartedAt";

    /** The annotation the deployment controller keeps the revision number in. */
    private static final String REVISION = "deployment.kubernetes.io/revision";

    /** Strategic merge is what makes a patch to a pod template additive rather than wholesale. */
    private static final String STRATEGIC_MERGE = "application/strategic-merge-patch+json";

    private static final String MERGE_PATCH = "application/merge-patch+json";

    private final AgentProperties.Tools tools;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public KubernetesActionExecutor(AgentProperties properties, ObjectMapper objectMapper) {
        this.tools = properties.tools();
        this.objectMapper = objectMapper;
        this.httpClient = buildClient();
        if (!hasExecutorCredential()) {
            // Said once, at startup, and not per request: an agent with no
            // executor credential is a supportable state - it is the default -
            // but an operator should be able to tell from the boot log whether
            // this deployment can act at all.
            log.info("No Kubernetes executor credential is configured; approved actions will be refused. "
                    + "Set agent.tools.executor-token to allow the agent to restart, scale or roll back.");
        }
    }

    @Override
    public String mode() {
        return "live";
    }

    @Override
    public ActionOutcome execute(ActionProposal proposal) {
        String action = proposal.describe();
        if (!hasExecutorCredential()) {
            return ActionOutcome.failure(action,
                    "The agent has no executor credential, so it cannot perform actions. Set "
                            + "agent.tools.executor-token on a deployment that is meant to act.",
                    "no executor credential");
        }

        try {
            return switch (proposal.type()) {
                case RESTART_WORKLOAD -> restart(proposal, action);
                case SCALE_WORKLOAD -> scale(proposal, action);
                case ROLLBACK_DEPLOYMENT -> rollback(proposal, action);
            };
        } catch (AgentUpstreamException e) {
            log.warn("The cluster refused '{}': {}", action, e.getMessage());
            return ActionOutcome.failure(action, "The cluster refused the action: " + e.getMessage(),
                    e.getMessage());
        } catch (IOException e) {
            return ActionOutcome.failure(action,
                    "The cluster's answer could not be read: " + e.getMessage(), e.getMessage());
        }
    }

    // --- the three actions ------------------------------------------------

    private ActionOutcome restart(ActionProposal proposal, String action) throws IOException {
        String stamp = OffsetDateTime.now(ZoneOffset.UTC).toString();
        ObjectNode body = objectMapper.createObjectNode();
        body.putObject("spec")
                .putObject("template")
                .putObject("metadata")
                .putObject("annotations")
                .put(RESTARTED_AT, stamp);

        String response = patch(deploymentPath(proposal), STRATEGIC_MERGE, body);
        return ActionOutcome.success(action,
                "Requested a rolling restart of " + target(proposal) + " at " + stamp + ".",
                summarise(response));
    }

    private ActionOutcome scale(ActionProposal proposal, String action) throws IOException {
        int replicas = ((Number) proposal.parameters().get("replicas")).intValue();
        ObjectNode body = objectMapper.createObjectNode();
        body.putObject("spec").put("replicas", replicas);

        String response = patch(deploymentPath(proposal), MERGE_PATCH, body);
        return ActionOutcome.success(action,
                "Requested " + target(proposal) + " to run " + replicas + " replica(s).",
                summarise(response));
    }

    /**
     * Copies a previous ReplicaSet's pod template onto the Deployment.
     *
     * <p>Reads the Deployment's selector, lists the ReplicaSets it owns, and
     * picks by the {@code deployment.kubernetes.io/revision} annotation - the
     * same number {@code kubectl rollout history} shows. With no {@code
     * revision} parameter it picks the highest revision below the current one;
     * with one, it picks that revision and refuses if there is no such
     * ReplicaSet, rather than silently rolling back to something else.
     */
    private ActionOutcome rollback(ActionProposal proposal, String action) throws IOException {
        String deploymentJson = get(deploymentPath(proposal));
        JsonNode deployment = objectMapper.readTree(deploymentJson);

        int currentRevision = deployment.path("metadata").path("annotations").path(REVISION)
                .asInt(0);
        Map<String, String> selector = selectorOf(deployment);
        if (selector.isEmpty()) {
            return ActionOutcome.failure(action,
                    "The Deployment has no selector, so its previous ReplicaSets cannot be identified.",
                    "no spec.selector.matchLabels");
        }

        List<JsonNode> candidates = new ArrayList<>();
        for (JsonNode item : objectMapper.readTree(get(replicaSetsPath(proposal, selector))).path("items")) {
            int revision = item.path("metadata").path("annotations").path(REVISION).asInt(0);
            if (revision > 0 && revision < currentRevision) {
                candidates.add(item);
            }
        }
        if (candidates.isEmpty()) {
            return ActionOutcome.failure(action,
                    "There is no earlier revision of " + target(proposal)
                            + " to restore; the current revision is " + currentRevision + ".",
                    "no earlier ReplicaSet");
        }

        Object requested = proposal.parameters().get("revision");
        JsonNode chosen;
        if (requested != null) {
            int wanted = ((Number) requested).intValue();
            chosen = candidates.stream()
                    .filter(item -> item.path("metadata").path("annotations").path(REVISION).asInt(0) == wanted)
                    .findFirst()
                    .orElse(null);
            if (chosen == null) {
                return ActionOutcome.failure(action,
                        "There is no revision " + wanted + " of " + target(proposal)
                                + " to restore. Available revisions: " + revisions(candidates) + ".",
                        "unknown revision");
            }
        } else {
            chosen = candidates.stream()
                    .max(Comparator.comparingInt(
                            item -> item.path("metadata").path("annotations").path(REVISION).asInt(0)))
                    .orElseThrow();
        }

        int chosenRevision = chosen.path("metadata").path("annotations").path(REVISION).asInt(0);
        JsonNode template = chosen.path("spec").path("template");
        if (template.isMissingNode() || template.isEmpty()) {
            return ActionOutcome.failure(action,
                    "The ReplicaSet for revision " + chosenRevision + " has no pod template to restore.",
                    "empty spec.template");
        }

        ObjectNode body = objectMapper.createObjectNode();
        body.putObject("spec").set("template", template);
        String response = patch(deploymentPath(proposal), STRATEGIC_MERGE, body);
        return ActionOutcome.success(action,
                "Restored revision " + chosenRevision + " of " + target(proposal)
                        + " (was revision " + currentRevision + ").",
                summarise(response));
    }

    // --- HTTP --------------------------------------------------------------

    private String get(String path) {
        return send(HttpRequest.newBuilder(URI.create(base() + path))
                .timeout(Duration.ofSeconds(Math.max(1, tools.timeoutSeconds())))
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .header("Authorization", "Bearer " + executorToken())
                .GET()
                .build());
    }

    private String patch(String path, String contentType, JsonNode body) {
        try {
            return send(HttpRequest.newBuilder(URI.create(base() + path))
                    .timeout(Duration.ofSeconds(Math.max(1, tools.timeoutSeconds())))
                    .header("Accept", "application/json")
                    .header("Content-Type", contentType)
                    .header("User-Agent", USER_AGENT)
                    .header("Authorization", "Bearer " + executorToken())
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body)))
                    .build());
        } catch (tools.jackson.core.JacksonException e) {
            throw new AgentUpstreamException("The action's body could not be built.", e);
        }
    }

    /**
     * Sends one request and maps the answer.
     *
     * <p>No retries. A diagnostic read is idempotent and worth retrying when a
     * monitoring stack is coming up; a state-changing PATCH is not, and a retry
     * that follows an accepted change whose response was lost would apply it
     * twice. An action either happens or is reported as not having happened.
     */
    private String send(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status / 100 == 2) {
                return response.body() == null ? "" : response.body();
            }
            if (status == 401 || status == 403) {
                throw new AgentUpstreamException("HTTP " + status
                        + " - the executor credential is not allowed to do this");
            }
            if (status == 404) {
                throw new AgentUpstreamException("HTTP 404 - the target no longer exists");
            }
            throw new AgentUpstreamException("HTTP " + status);
        } catch (IOException e) {
            throw new AgentUpstreamException("the API server could not be reached: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentUpstreamException("the call was interrupted", e);
        }
    }

    // --- paths and small helpers -------------------------------------------

    private String deploymentPath(ActionProposal proposal) {
        return apiPath(proposal.kind()) + "/namespaces/" + encode(proposal.namespace()) + "/"
                + plural(proposal.kind()) + "/" + encode(proposal.name());
    }

    private String replicaSetsPath(ActionProposal proposal, Map<String, String> selector) {
        String labelSelector = selector.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        return "/apis/apps/v1/namespaces/" + encode(proposal.namespace())
                + "/replicasets?labelSelector=" + encode(labelSelector);
    }

    /** The API group and version for a kind, from a fixed table - never from input. */
    private static String apiPath(String kind) {
        String normalised = kind == null ? "" : kind.strip().toLowerCase(Locale.ROOT);
        return switch (normalised) {
            case "deployment", "deployments", "replicaset", "replicasets",
                 "statefulset", "statefulsets", "daemonset", "daemonsets" -> "/apis/apps/v1";
            default -> throw new AgentUpstreamException("The executor cannot act on a " + kind + ".");
        };
    }

    private static String plural(String kind) {
        String normalised = kind == null ? "" : kind.strip().toLowerCase(Locale.ROOT);
        return normalised.endsWith("s") && normalised.length() > 1 ? normalised : normalised + "s";
    }

    private static Map<String, String> selectorOf(JsonNode deployment) {
        Map<String, String> selector = new LinkedHashMap<>();
        JsonNode matchLabels = deployment.path("spec").path("selector").path("matchLabels");
        if (matchLabels.isObject()) {
            matchLabels.properties().forEach(field -> selector.put(field.getKey(), field.getValue().asText()));
        }
        return selector;
    }

    private static String revisions(List<JsonNode> candidates) {
        return candidates.stream()
                .map(item -> item.path("metadata").path("annotations").path(REVISION).asText("?"))
                .reduce((left, right) -> left + ", " + right)
                .orElse("none");
    }

    /** One line about the object the API answered with, for the audit trail. */
    private static String summarise(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "";
        }
        int limit = 500;
        String single = responseBody.replaceAll("\\s+", " ");
        return single.length() <= limit ? single : single.substring(0, limit) + "...";
    }

    private static String target(ActionProposal proposal) {
        return plural(proposal.kind()) + " " + proposal.namespace() + "/" + proposal.name();
    }

    private boolean hasExecutorCredential() {
        if (tools.executorToken() != null && !tools.executorToken().isBlank()) {
            return true;
        }
        Path tokenFile = tokenFile();
        return tokenFile != null;
    }

    /** Read per call, because a projected ServiceAccount token is rotated by the kubelet. */
    private String executorToken() {
        if (tools.executorToken() != null && !tools.executorToken().isBlank()) {
            return tools.executorToken();
        }
        Path tokenFile = tokenFile();
        if (tokenFile == null) {
            // Cannot happen through execute(), which checks first; kept so the
            // method has no path that returns null into a header.
            throw new AgentUpstreamException("No executor credential is available.");
        }
        try {
            return Files.readString(tokenFile).strip();
        } catch (IOException e) {
            throw new AgentUpstreamException("The executor credential could not be read.", e);
        }
    }

    private Path tokenFile() {
        String path = tools.executorTokenFile();
        if (path == null || path.isBlank()) {
            return null;
        }
        Path candidate = Path.of(path);
        return Files.isReadable(candidate) ? candidate : null;
    }

    private String base() {
        String url = tools.kubernetesUrl();
        return url == null ? "" : url.replaceAll("/+$", "");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private HttpClient buildClient() {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, Math.min(10, tools.timeoutSeconds()))))
                .followRedirects(HttpClient.Redirect.NEVER);

        String caPath = tools.kubernetesCaFile();
        if (caPath != null && !caPath.isBlank()) {
            Path caFile = Path.of(caPath);
            if (Files.isReadable(caFile)) {
                try {
                    builder.sslContext(ClusterTls.trusting(caFile));
                } catch (Exception e) {
                    log.warn("Could not load the Kubernetes CA certificate from {}; actions against the API "
                            + "server may fail TLS verification. {}", caFile, e.getMessage());
                }
            }
        }
        return builder.build();
    }
}
