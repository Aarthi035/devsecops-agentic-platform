package com.devsecops.agentservice.tools;

import com.devsecops.agentservice.config.AgentProperties;
import com.devsecops.agentservice.error.AgentUpstreamException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The live {@link DiagnosticTools}: HTTP calls to Prometheus, Loki and the
 * Kubernetes API, and a file read for scanner reports.
 *
 * <p><b>Read-only, structurally.</b> The only request this class can build is a
 * GET - there is no method that takes a body or a verb - and the Kubernetes
 * calls carry the pod's ServiceAccount token, whose Role grants {@code
 * get}/{@code list}/{@code watch}. Both halves matter: the code cannot write and
 * the cluster would refuse it if it tried.
 *
 * <p>Three details are worth knowing before reading the code:
 *
 * <ul>
 *   <li><b>The retry rule is deliberate.</b> A 5xx, a timeout or a refused
 *       connection is retried up to {@code agent.tools.retries} times, because
 *       those are the transient failures a monitoring stack has while it is
 *       coming up. A 4xx is not retried: a bad PromQL expression will be just
 *       as bad the second time, and retrying it only makes the incident slower
 *       to diagnose.</li>
 *   <li><b>Every URL is configuration.</b> Prometheus and Loki carry their own
 *       route prefixes - Prometheus serves under {@code /prometheus} because of
 *       {@code --web.external-url} - so the configured base URLs include them
 *       rather than the code hardcoding a path that only holds for one
 *       deployment.</li>
 *   <li><b>No URL is guessed from user input.</b> A caller supplies a kind, a
 *       namespace and a name, and this class maps the kind to an API path
 *       through a fixed table. An unknown kind is refused, so a request cannot
 *       walk the agent into an arbitrary API path.</li>
 * </ul>
 *
 * <p>Failures come back as {@link ToolResult#failed}, never as an exception: an
 * incident where Loki is down should still be diagnosed from the metrics.
 */
@Component
@ConditionalOnProperty(name = "agent.tools.mode", havingValue = "live", matchIfMissing = true)
public class HttpDiagnosticTools implements DiagnosticTools {

    private static final Logger log = LoggerFactory.getLogger(HttpDiagnosticTools.class);

    private static final String USER_AGENT = "devsecops-agentic-platform";

    /**
     * A ceiling on any single tool response. Loki honours {@code limit} and the
     * log endpoint honours {@code tailLines}, but a Kubernetes object or an
     * unexpected Prometheus answer is not bounded by anything the agent asked
     * for, and this is the last line of defence before it becomes evidence.
     */
    private static final int MAX_BODY_CHARS = 100_000;

    /** Kinds served from {@code /api/v1}. Keys are singular, lower-case. */
    private static final Map<String, String> CORE_PLURALS = Map.of(
            "pod", "pods",
            "service", "services",
            "configmap", "configmaps",
            "secret", "secrets",
            "event", "events",
            "node", "nodes");

    /** Kinds served from {@code /apis/apps/v1}. */
    private static final Map<String, String> APPS_PLURALS = Map.of(
            "deployment", "deployments",
            "replicaset", "replicasets",
            "statefulset", "statefulsets",
            "daemonset", "daemonsets");

    private final AgentProperties.Tools tools;
    private final AgentProperties.Incident incident;
    private final AgentProperties.Security security;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public HttpDiagnosticTools(AgentProperties properties, ObjectMapper objectMapper) {
        this.tools = properties.tools();
        this.incident = properties.incident();
        this.security = properties.security();
        this.objectMapper = objectMapper;
        this.httpClient = buildClient();
    }

    @Override
    public String mode() {
        return "live";
    }

    // --- Prometheus --------------------------------------------------------

    @Override
    public ToolResult queryPrometheus(String promql) {
        String url = base(tools.prometheusUrl()) + "/api/v1/query?query=" + encode(promql);
        try {
            JsonNode root = objectMapper.readTree(get(url, tools.prometheusToken(), "application/json").body());
            List<String> series = new ArrayList<>();
            for (JsonNode entry : root.path("data").path("result")) {
                series.add(formatSeries(entry));
            }
            String summary = series.isEmpty()
                    ? "PromQL '" + promql + "' matched no series"
                    : "PromQL '" + promql + "' returned " + series.size() + " series";
            return ToolResult.ok("queryPrometheus", summary, truncate(String.join("\n", series)));
        } catch (AgentUpstreamException e) {
            return ToolResult.failed("queryPrometheus",
                    "Prometheus did not answer '" + promql + "': " + e.getMessage(), e.getMessage());
        } catch (IOException e) {
            return ToolResult.failed("queryPrometheus",
                    "Prometheus returned something that is not JSON for '" + promql + "'.",
                    e.getMessage());
        }
    }

    /** One series as {@code labels = value}, which is what a reader wants to see. */
    private static String formatSeries(JsonNode entry) {
        StringBuilder line = new StringBuilder();
        JsonNode metric = entry.path("metric");
        if (metric.isObject() && !metric.isEmpty()) {
            List<String> labels = new ArrayList<>();
            Iterator<Map.Entry<String, JsonNode>> fields = metric.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                labels.add(field.getKey() + "=\"" + field.getValue().asText() + "\"");
            }
            line.append(String.join(", ", labels));
        } else {
            line.append("{}");
        }
        JsonNode value = entry.path("value");
        line.append(" = ").append(value.isArray() && value.size() > 1 ? value.get(1).asText() : value.toString());
        return line.toString();
    }

    // --- Loki --------------------------------------------------------------

    @Override
    public ToolResult queryLoki(String logql, int limit) {
        int effectiveLimit = Math.max(1, Math.min(limit, tools.maxLogLines()));
        long endMillis = System.currentTimeMillis();
        long startMillis = endMillis - Math.max(1, incident.lookbackSeconds()) * 1000L;

        String url = base(tools.lokiUrl()) + "/loki/api/v1/query_range"
                + "?query=" + encode(logql)
                + "&limit=" + effectiveLimit
                + "&start=" + startMillis * 1_000_000L
                + "&end=" + endMillis * 1_000_000L
                + "&direction=backward";

        try {
            JsonNode root = objectMapper.readTree(get(url, null, "application/json").body());
            List<String> lines = new ArrayList<>();
            for (JsonNode stream : root.path("data").path("result")) {
                for (JsonNode pair : stream.path("values")) {
                    if (pair.isArray() && pair.size() > 1) {
                        lines.add(pair.get(1).asText());
                    }
                }
            }
            // Loki answers newest-first. Reversed, the log reads in the order the
            // application wrote it, which is the order a cause-then-effect story
            // is told in.
            Collections.reverse(lines);
            if (lines.size() > tools.maxLogLines()) {
                lines = lines.subList(lines.size() - tools.maxLogLines(), lines.size());
            }
            String summary = lines.isEmpty()
                    ? "LogQL '" + logql + "' matched no lines in the last " + incident.lookbackSeconds() + "s"
                    : "LogQL '" + logql + "' returned " + lines.size() + " line(s)";
            return ToolResult.ok("queryLoki", summary, truncate(String.join("\n", lines)));
        } catch (AgentUpstreamException e) {
            return ToolResult.failed("queryLoki", "Loki did not answer: " + e.getMessage(), e.getMessage());
        } catch (IOException e) {
            return ToolResult.failed("queryLoki", "Loki returned something that is not JSON.", e.getMessage());
        }
    }

    // --- kubectlReadOnly ---------------------------------------------------

    @Override
    public ToolResult kubectlGet(String kind, String namespace, String name) {
        return readObject("kubectlReadOnly", kind, namespace, name);
    }

    private ToolResult readObject(String tool, String kind, String namespace, String name) {
        String where = describeTarget(kind, namespace, name);
        try {
            String url = objectUrl(kind, namespace, name);
            HttpResponse<String> response = get(url, kubernetesToken(), "application/json");
            JsonNode object = objectMapper.readTree(response.body());
            return ToolResult.ok(tool, "Read " + where + ": " + objectState(object),
                    truncate(response.body()));
        } catch (AgentUpstreamException e) {
            return ToolResult.failed(tool, "Could not read " + where + ": " + e.getMessage(), e.getMessage());
        } catch (IOException e) {
            return ToolResult.failed(tool, "Kubernetes returned something that is not JSON for " + where + ".",
                    e.getMessage());
        }
    }

    /**
     * The compact state summary that goes into the facts.
     *
     * <p>Read out of the object rather than left in the JSON body so the agent's
     * own observation ("restartCount=6, waiting reason CrashLoopBackOff") is
     * distinguishable from the raw document when both appear in the evidence.
     */
    private static String objectState(JsonNode object) {
        StringBuilder state = new StringBuilder();
        JsonNode status = object.path("status");

        String phase = status.path("phase").asText("");
        if (!phase.isBlank()) {
            state.append("phase=").append(phase);
        }
        String replicas = status.path("readyReplicas").asText("");
        if (!replicas.isBlank()) {
            state.append(state.length() > 0 ? " " : "")
                    .append("readyReplicas=").append(replicas)
                    .append("/").append(status.path("replicas").asText("?"));
        }

        List<String> containers = new ArrayList<>();
        for (JsonNode container : status.path("containerStatuses")) {
            if (!container.isObject()) {
                continue;
            }
            int restarts = container.path("restartCount").asInt(0);
            String reason = container.path("state").path("waiting").path("reason").asText("");
            if (reason.isBlank()) {
                reason = container.path("lastState").path("terminated").path("reason").asText("");
            }
            containers.add(container.path("name").asText("?") + "(restarts=" + restarts
                    + (reason.isBlank() ? "" : ", reason=" + reason) + ")");
        }
        if (!containers.isEmpty()) {
            state.append(state.length() > 0 ? " " : "").append("containers=")
                    .append(String.join(", ", containers));
        }
        if (state.length() == 0) {
            state.append(object.path("kind").asText("object")).append(" has no status to read");
        }
        return state.toString();
    }

    @Override
    public ToolResult kubectlDescribe(String kind, String namespace, String name) {
        String where = describeTarget(kind, namespace, name);
        ToolResult object = readObject("kubectlReadOnly", kind, namespace, name);
        ToolResult events = events("kubectlReadOnly", namespace, kind, name);

        StringBuilder body = new StringBuilder();
        if (object.ok()) {
            body.append(object.body()).append("\n\n");
        }
        if (events.ok()) {
            body.append(events.body());
        }
        String summary = "Described " + where + ": " + object.summary() + "; " + events.summary();

        if (!object.ok() && !events.ok()) {
            // Both halves failed, so there is nothing to reason from. Reported as
            // a failure so the investigator records a gap rather than an absence.
            return ToolResult.failed("kubectlReadOnly", summary, object.error());
        }
        return ToolResult.ok("kubectlReadOnly", summary, truncate(body.toString().strip()));
    }

    @Override
    public ToolResult kubectlEvents(String namespace) {
        return events("kubectlReadOnly", namespace, null, null);
    }

    /**
     * Lists events, optionally filtered to one object.
     *
     * <p>The filter is a {@code fieldSelector} rather than a client-side pass so
     * the API server does the narrowing - an unfiltered event list in a busy
     * namespace is large enough to matter, and the agent only ever wants the
     * events about the object the alert named.
     */
    private ToolResult events(String tool, String namespace, String kind, String name) {
        StringBuilder url = new StringBuilder(base(tools.kubernetesUrl()))
                .append("/api/v1/namespaces/").append(encodePath(namespace)).append("/events");
        if (kind != null && name != null) {
            url.append("?fieldSelector=").append(encode("involvedObject.kind=" + normaliseKind(kind)
                    + ",involvedObject.name=" + name));
        }
        String where = kind == null ? "namespace " + namespace : describeTarget(kind, namespace, name);
        try {
            JsonNode root = objectMapper.readTree(get(url.toString(), kubernetesToken(), "application/json").body());
            List<String> lines = new ArrayList<>();
            for (JsonNode item : root.path("items")) {
                String type = item.path("type").asText("Normal");
                String reason = item.path("reason").asText("?");
                String message = item.path("message").asText("");
                int count = item.path("count").asInt(1);
                lines.add(type + " " + reason + " x" + count + ": " + message);
            }
            String summary = lines.isEmpty()
                    ? "No events recorded for " + where
                    : lines.size() + " event(s) for " + where;
            return ToolResult.ok(tool, summary, truncate(String.join("\n", lines)));
        } catch (AgentUpstreamException e) {
            return ToolResult.failed(tool, "Could not read events for " + where + ": " + e.getMessage(),
                    e.getMessage());
        } catch (IOException e) {
            return ToolResult.failed(tool, "Kubernetes returned something that is not JSON for the events of "
                    + where + ".", e.getMessage());
        }
    }

    @Override
    public ToolResult kubectlLogs(String namespace, String pod, String container) {
        String where = "pod " + namespace + "/" + pod + (container == null || container.isBlank()
                ? "" : " (container " + container + ")");
        StringBuilder url = new StringBuilder(base(tools.kubernetesUrl()))
                .append("/api/v1/namespaces/").append(encodePath(namespace))
                .append("/pods/").append(encodePath(pod))
                .append("/log?tailLines=").append(tools.maxLogLines());
        if (container != null && !container.isBlank()) {
            url.append("&container=").append(encodePath(container));
        }
        try {
            // text/plain: the log endpoint does not answer with JSON, and asking
            // for JSON here is how a perfectly good log becomes a 406.
            HttpResponse<String> response = get(url.toString(), kubernetesToken(), "text/plain");
            String body = response.body() == null ? "" : response.body();
            long lineCount = body.isEmpty() ? 0 : body.lines().count();
            return ToolResult.ok("kubectlReadOnly", "Read " + lineCount + " log line(s) from " + where,
                    truncate(body));
        } catch (AgentUpstreamException e) {
            return ToolResult.failed("kubectlReadOnly", "Could not read the log of " + where + ": "
                    + e.getMessage(), e.getMessage());
        }
    }

    // --- readSecurityReport ------------------------------------------------

    @Override
    public ToolResult readSecurityReport(String path) {
        try {
            Path report = resolveReportPath(path);
            long size = Files.size(report);
            if (size > security.maxReportBytes()) {
                String message = "The report at " + report + " is " + size + " bytes, above the "
                        + security.maxReportBytes() + "-byte limit.";
                return ToolResult.failed("readSecurityReport", message, "report too large");
            }
            return ToolResult.ok("readSecurityReport",
                    "Read " + size + " bytes from " + report.getFileName(),
                    Files.readString(report));
        } catch (AgentUpstreamException e) {
            return ToolResult.failed("readSecurityReport", e.getMessage(), e.getMessage());
        } catch (IOException e) {
            return ToolResult.failed("readSecurityReport",
                    "Could not read the report: " + e.getMessage(), e.getMessage());
        }
    }

    /**
     * Resolves a requested report path inside the configured report directory.
     *
     * <p>The containment check is done on the <em>real</em> paths, after
     * symlinks are followed, so a symlink planted inside the report directory
     * cannot be used to read a file outside it. The directory must exist and the
     * target must be a readable regular file; anything else is refused with a
     * message that says which rule was broken.
     */
    private Path resolveReportPath(String requested) {
        Path root = Path.of(security.reportDirectory()).toAbsolutePath().normalize();
        Path realRoot;
        try {
            realRoot = root.toRealPath();
        } catch (IOException e) {
            throw new AgentUpstreamException("The report directory " + root + " does not exist.");
        }

        String candidate = requested == null || requested.isBlank() ? security.reportPath() : requested;
        Path requestedPath = Path.of(candidate);
        Path resolved = (requestedPath.isAbsolute() ? requestedPath : realRoot.resolve(requestedPath))
                .normalize();
        if (!resolved.startsWith(realRoot)) {
            throw new AgentUpstreamException("The requested report path " + resolved
                    + " is outside the report directory " + realRoot + "; refusing to read it.");
        }

        Path real;
        try {
            real = resolved.toRealPath();
        } catch (IOException e) {
            throw new AgentUpstreamException("There is no readable report at " + resolved + ".");
        }
        if (!real.startsWith(realRoot)) {
            throw new AgentUpstreamException("The report path " + real
                    + " resolves outside the report directory " + realRoot + "; refusing to read it.");
        }
        if (!Files.isRegularFile(real) || !Files.isReadable(real)) {
            throw new AgentUpstreamException("The report at " + real + " is not a readable file.");
        }
        return real;
    }

    // --- HTTP plumbing -----------------------------------------------------

    private HttpClient buildClient() {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, Math.min(10, tools.timeoutSeconds()))))
                .followRedirects(HttpClient.Redirect.NEVER);

        Path caFile = readableFile(tools.kubernetesCaFile());
        if (caFile != null) {
            try {
                builder.sslContext(ClusterTls.trusting(caFile));
            } catch (Exception e) {
                // Not fatal, and not silent: without the CA the Kubernetes calls
                // will fail TLS verification, which is a confusing symptom, so it
                // is worth a warning that names the cause.
                log.warn("Could not load the Kubernetes CA certificate from {}; the Kubernetes API calls may "
                        + "fail TLS verification. {}", caFile, e.getMessage());
            }
        }
        return builder.build();
    }

    /**
     * One GET, with the retry rule described on the class.
     *
     * <p>Failures are raised as {@link AgentUpstreamException} so each tool
     * method can phrase its own {@link ToolResult} - the exception never escapes
     * this class, because a tool failing is an expected state rather than an
     * error the caller should handle.
     */
    private HttpResponse<String> get(String url, String bearer, String accept) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(1, tools.timeoutSeconds())))
                .header("Accept", accept)
                .header("User-Agent", USER_AGENT)
                .GET();
        if (bearer != null && !bearer.isBlank()) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        HttpRequest request = builder.build();

        int attempts = Math.max(1, tools.retries() + 1);
        AgentUpstreamException lastFailure = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status / 100 == 2) {
                    return response;
                }
                if (status / 100 == 4) {
                    throw new AgentUpstreamException("HTTP " + status
                            + " - the query or the credentials were rejected");
                }
                lastFailure = new AgentUpstreamException("HTTP " + status);
            } catch (IOException e) {
                lastFailure = new AgentUpstreamException("the endpoint could not be reached: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AgentUpstreamException("the call was interrupted", e);
            }
            if (attempt < attempts) {
                log.debug("Retrying a tool call after a transient failure: {}", lastFailure.getMessage());
            }
        }
        throw lastFailure;
    }

    /**
     * The ServiceAccount token, read per call.
     *
     * <p>Per call rather than cached in the constructor because a projected
     * ServiceAccount token is rotated by the kubelet, and a cached copy would
     * start failing an hour into a long-running pod for no visible reason.
     */
    private String kubernetesToken() {
        if (tools.kubernetesToken() != null && !tools.kubernetesToken().isBlank()) {
            return tools.kubernetesToken();
        }
        Path tokenFile = readableFile(tools.kubernetesTokenFile());
        if (tokenFile == null) {
            return null;
        }
        try {
            return Files.readString(tokenFile).strip();
        } catch (IOException e) {
            log.warn("Could not read the ServiceAccount token from {}: {}", tokenFile, e.getMessage());
            return null;
        }
    }

    /** The URL for reading one object, or a refusal naming the kinds that work. */
    private String objectUrl(String kind, String namespace, String name) {
        if (name == null || name.isBlank()) {
            throw new AgentUpstreamException("A name is required to read a " + kind + ".");
        }
        return base(tools.kubernetesUrl()) + apiPath(kind) + "/namespaces/" + encodePath(namespace)
                + "/" + plural(kind) + "/" + encodePath(name);
    }

    private static String apiPath(String kind) {
        String key = normaliseKind(kind);
        if (CORE_PLURALS.containsKey(key)) {
            return "/api/v1";
        }
        if (APPS_PLURALS.containsKey(key)) {
            return "/apis/apps/v1";
        }
        throw new AgentUpstreamException("The agent does not read '" + kind + "'. It reads: "
                + String.join(", ", supportedKinds()) + ".");
    }

    private static String plural(String kind) {
        String key = normaliseKind(kind);
        String plural = CORE_PLURALS.containsKey(key) ? CORE_PLURALS.get(key) : APPS_PLURALS.get(key);
        if (plural == null) {
            throw new AgentUpstreamException("The agent does not read '" + kind + "'.");
        }
        return plural;
    }

    private static List<String> supportedKinds() {
        List<String> kinds = new ArrayList<>(CORE_PLURALS.keySet());
        kinds.addAll(APPS_PLURALS.keySet());
        Collections.sort(kinds);
        return kinds;
    }

    /** {@code pods}, {@code Pod} and {@code POD} all mean the same kind here. */
    private static String normaliseKind(String kind) {
        String key = kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT);
        return key.endsWith("s") && key.length() > 1 ? key.substring(0, key.length() - 1) : key;
    }

    private static String describeTarget(String kind, String namespace, String name) {
        return normaliseKind(kind) + " " + namespace + "/" + name;
    }

    private static String base(String url) {
        return url == null ? "" : url.replaceAll("/+$", "");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    /**
     * Encodes one path segment, keeping {@code /} out of it.
     *
     * <p>{@link URLEncoder} turns a space into {@code +}, which is right for a
     * query and wrong for a path; the replacement below is the standard fix and
     * matters here because a namespace or a name containing a space should be an
     * encoded space, not a plus sign.
     */
    private static String encodePath(String segment) {
        return encode(segment).replace("+", "%20");
    }

    private static Path readableFile(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        Path candidate = Path.of(path);
        return Files.isReadable(candidate) ? candidate : null;
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_BODY_CHARS ? text : text.substring(0, MAX_BODY_CHARS);
    }
}
