package com.devsecops.agentservice.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The agent's entire configuration surface, bound from the {@code agent.*} tree
 * in application.yml.
 *
 * <p>One class rather than a scatter of {@code @Value} annotations, because the
 * values are read together and the guardrails are part of the design: a
 * {@code max-log-chars} is not a detail of the HTTP client, it is the "truncate
 * before the LLM" rule from the spec, and it belongs next to the other limits.
 *
 * <p>Every component carries {@link DefaultValue} as well as a default in
 * application.yml, so a test that loads a slice of the context without the YAML
 * still gets a working object instead of a {@code null} binder failure.
 *
 * <p>The GitHub token and the Kubernetes credentials are the only secrets here.
 * They are bound from the environment ({@code GITHUB_TOKEN}, a mounted
 * ServiceAccount token) and are never logged, never echoed in an error message
 * and never sent to the model.
 *
 * <p>Phase 9 adds five groups and one limit. The split follows the question each
 * group answers rather than the class that reads it: {@link Tools} is "where are
 * the things I can read, and am I allowed to touch a real cluster at all",
 * {@link Incident} is "what bounds one alert's investigation", {@link Security}
 * is "where do scan reports live and how much of one will I read", and
 * {@link Approval} is "who may authorise a state change".
 */
@ConfigurationProperties(prefix = "agent")
public record AgentProperties(
        @DefaultValue Llm llm,
        @DefaultValue Github github,
        @DefaultValue Limits limits,
        @DefaultValue Tools tools,
        @DefaultValue Incident incident,
        @DefaultValue Security security,
        @DefaultValue Approval approval) {

    /**
     * Which engine writes the diagnosis, and how it is reached.
     *
     * <p>{@code provider} is the switch that makes the module testable without a
     * model: {@code heuristic} runs the in-repo rule table and needs nothing
     * installed, {@code ollama} posts the sanitized log to a local model. The two
     * engines are chosen with {@code @ConditionalOnProperty} against this value,
     * so exactly one of them is ever in the context.
     */
    public record Llm(
            @DefaultValue("heuristic") String provider,
            @DefaultValue("http://localhost:11434") String baseUrl,
            @DefaultValue("llama3.2:3b") String model,
            @DefaultValue("60") int timeoutSeconds,
            @DefaultValue("700") int outputBudget,
            @DefaultValue("0.1") double temperature) {
    }

    /**
     * Where the failed run is read from and where the diagnosis is written.
     *
     * <p>{@code commentEnabled} exists so the agent can be exercised end to end
     * against a real run without writing to a pull request; a dry run is the
     * default posture for anything that calls a third party.
     */
    public record Github(
            @DefaultValue("https://api.github.com") String apiUrl,
            @DefaultValue("") String token,
            @DefaultValue("true") boolean commentEnabled) {

        /**
         * Whether a token is configured. Callers should ask this rather than
         * testing {@code token} themselves: it keeps the "is it blank?" rule in
         * one place and never returns the value, so it is safe in a log line.
         */
        public boolean hasToken() {
            return token != null && !token.isBlank();
        }
    }

    /**
     * The guardrails from the spec, applied where each one bites.
     *
     * <ul>
     *   <li>{@code maxLogChars} - the log is truncated to this before it leaves
     *       the process, so a runaway build log cannot blow up the model's
     *       context window.</li>
     *   <li>{@code maxFailedJobs} - the "max steps" limit applied to log
     *       fetching: at most this many failed jobs of a run are read.</li>
     *   <li>{@code githubTimeoutSeconds} - a hung GitHub call must not hold the
     *       request thread open indefinitely.</li>
     *   <li>{@code maxToolCalls} - the same "max steps" limit applied to a
     *       Phase 9 investigation, where the steps are tool calls rather than
     *       jobs. A tool-using loop without a step budget is a loop that can run
     *       until someone notices the bill.</li>
     * </ul>
     */
    public record Limits(
            @DefaultValue("12000") int maxLogChars,
            @DefaultValue("3") int maxFailedJobs,
            @DefaultValue("30") int githubTimeoutSeconds,
            @DefaultValue("12") int maxToolCalls) {
    }

    /**
     * The read-only tools, and whether they talk to a real cluster.
     *
     * <p>{@code mode} is the switch that makes the whole of Phase 9 demonstrable
     * on a laptop: {@code live} uses the HTTP implementations below, {@code mock}
     * swaps in deterministic fixtures and calls nothing. The same switch governs
     * the action executor, so "mock" means the agent can neither read nor change
     * a cluster - which is what the tests and the demo scripts run in.
     *
     * <p>The two Kubernetes credentials are deliberately separate, and each has
     * its own source. {@code kubernetesToken} / {@code kubernetesTokenFile} are
     * the diagnostic credential: by default the pod's own ServiceAccount token,
     * which RBAC keeps read-only. {@code executorToken} / {@code
     * executorTokenFile} are the credential the action executor uses, and both
     * default to <em>empty string, not the ServiceAccount token</em> - the
     * default must not silently hand the executor the token that happens to be
     * mounted, or every deployment would be able to act while looking as if it
     * only diagnoses. Empty means "this deployment cannot change anything",
     * which is the safe reading of "not configured".
     */
    public record Tools(
            @DefaultValue("live") String mode,
            @DefaultValue("http://prometheus.monitoring.svc.cluster.local:9090/prometheus") String prometheusUrl,
            @DefaultValue("") String prometheusToken,
            @DefaultValue("http://loki.monitoring.svc.cluster.local:3100") String lokiUrl,
            @DefaultValue("https://kubernetes.default.svc") String kubernetesUrl,
            @DefaultValue("") String kubernetesToken,
            @DefaultValue("/var/run/secrets/kubernetes.io/serviceaccount/token") String kubernetesTokenFile,
            @DefaultValue("/var/run/secrets/kubernetes.io/serviceaccount/ca.crt") String kubernetesCaFile,
            @DefaultValue("") String executorToken,
            @DefaultValue("") String executorTokenFile,
            @DefaultValue("10") int timeoutSeconds,
            @DefaultValue("1") int retries,
            @DefaultValue("20") int maxSamples,
            @DefaultValue("200") int maxLogLines,
            @DefaultValue("pod-restarts") String mockScenario) {

        /** True when the mock implementations should be in the context. */
        public boolean mock() {
            return "mock".equalsIgnoreCase(mode);
        }
    }

    /**
     * What bounds one alert's investigation.
     *
     * <p>{@code dedupWindowSeconds} exists because Alertmanager retries: a
     * re-delivered notification is not a second incident, and investigating it
     * again would double the tool calls, the audit entries and - with a model -
     * the cost. {@code lookbackSeconds} is how far back the log and metric
     * queries reach; it is short on purpose, because an alert is about now.
     */
    public record Incident(
            @DefaultValue("300") int dedupWindowSeconds,
            @DefaultValue("20") int maxAlerts,
            @DefaultValue("300") int lookbackSeconds,
            @DefaultValue("devsecops") String namespace) {
    }

    /**
     * Where scan reports live, and how much of one will be read.
     *
     * <p>{@code reportDirectory} is a boundary, not a convenience: a requested
     * path is resolved against it and refused if it escapes, so
     * {@code POST /analyze-security} cannot be used to read an arbitrary file off
     * the container's filesystem.
     */
    public record Security(
            @DefaultValue("/reports/trivy.json") String reportPath,
            @DefaultValue("/reports") String reportDirectory,
            @DefaultValue("50") int maxFindings,
            @DefaultValue("2097152") int maxReportBytes) {
    }

    /**
     * Who may authorise a state change.
     *
     * <p>{@code token} is blank by default, and a blank token means the
     * deployment has not said who is allowed to approve - so {@code POST
     * /approve} refuses every approval rather than waving them through. That is
     * the safe reading of "not configured": an agent that can restart workloads
     * without a credential would be a cluster-admin endpoint wearing a
     * diagnosis. The k8s ConfigMap sets the token from a Secret, so a deployed
     * agent is not left in that state by accident.
     *
     * <p>The token is compared in constant time against the configured value,
     * and it is never logged: {@link #hasToken()} answers "is one configured"
     * without ever returning it.
     *
     * <p>{@code defaultTtlSeconds} is how long a proposal waits for a human. A
     * proposal that is still pending an hour later is a proposal about a
     * situation that has changed, and executing it then is how an "approved
     * rollback" becomes an unapproved one.
     */
    public record Approval(
            @DefaultValue("") String token,
            @DefaultValue("900") int defaultTtlSeconds,
            @DefaultValue("100") int maxRetained) {

        /** Whether an approval token has been configured. Never returns the value. */
        public boolean hasToken() {
            return token != null && !token.isBlank();
        }

        /**
         * Compares a presented token with the configured one without leaking
         * their difference through timing.
         *
         * <p>Always runs the full comparison, even when the lengths differ, so
         * the time taken does not say how much of the token was right. Not
         * strictly necessary against a human typing an approval string, but it
         * costs nothing and this is the one place a guess is accepted.
         */
        public boolean accepts(String presented) {
            if (!hasToken() || presented == null) {
                return false;
            }
            return java.security.MessageDigest.isEqual(
                    token.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    presented.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    /**
     * Every secret in one place, for the startup check.
     *
     * <p>Not configuration - a summary used by a log line that reports which
     * credentials are present without printing any of them. A deployment should
     * be able to answer "am I configured?" from one log entry.
     */
    public List<String> configuredCredentials() {
        List<String> present = new java.util.ArrayList<>();
        if (github().hasToken()) {
            present.add("github-token");
        }
        if (tools().kubernetesToken() != null && !tools().kubernetesToken().isBlank()) {
            present.add("kubernetes-token");
        }
        if (tools().executorToken() != null && !tools().executorToken().isBlank()) {
            present.add("kubernetes-executor-token");
        }
        if (tools().executorTokenFile() != null && !tools().executorTokenFile().isBlank()) {
            // A path, not a value: naming which file is configured is useful in a
            // log line and reveals nothing about the credential in it.
            present.add("kubernetes-executor-token-file");
        }
        if (tools().prometheusToken() != null && !tools().prometheusToken().isBlank()) {
            present.add("prometheus-token");
        }
        if (approval().hasToken()) {
            present.add("approval-token");
        }
        return List.copyOf(present);
    }
}
