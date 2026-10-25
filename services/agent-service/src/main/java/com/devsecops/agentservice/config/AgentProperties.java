package com.devsecops.agentservice.config;

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
 * <p>The GitHub token is the only secret here. It is bound from the environment
 * ({@code GITHUB_TOKEN}) and is never logged, never echoed in an error message
 * and never sent to the model - see {@link Github#hasToken()} for the one way
 * any code should ask about it.
 */
@ConfigurationProperties(prefix = "agent")
public record AgentProperties(
        @DefaultValue Llm llm,
        @DefaultValue Github github,
        @DefaultValue Limits limits) {

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
            @DefaultValue("700") int maxOutputTokens,
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
     * </ul>
     */
    public record Limits(
            @DefaultValue("12000") int maxLogChars,
            @DefaultValue("3") int maxFailedJobs,
            @DefaultValue("30") int githubTimeoutSeconds) {
    }
}
