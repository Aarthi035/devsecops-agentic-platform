package com.devsecops.agentservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where the agent's traces go, and what its work is estimated to cost.
 *
 * <p>Bound from {@code agent.tracing.*}, and deliberately <em>not</em> a
 * component of {@link AgentProperties}. That record is the agent's behaviour and
 * guardrail surface - the values a unit test has to state before it can run the
 * agent at all - and every one of its groups is constructed by hand in the
 * tests. Tracing is neither behaviour nor a guardrail: it is an optional
 * <em>export</em> of what the agent already did, and a test that exercises a
 * rule table should not have to name a Langfuse host to do it. Keeping the two
 * apart means adding a tracing knob never edits a test, and that a deployment
 * can turn tracing off without touching anything the agent's behaviour depends
 * on.
 *
 * <p>There are three modes, and the middle one is the laptop default:
 *
 * <ul>
 *   <li>{@code off} - nothing is exported. The Micrometer metrics in {@link
 *       com.devsecops.agentservice.tracing.AgentTelemetry} are still recorded,
 *       because they never leave the process and the Grafana panel is meant to
 *       work on a deployment that traces nothing.</li>
 *   <li>{@code log} - one structured line per request to the {@code TRACE}
 *       logger. Promtail already ships every pod's stdout to Loki, so a trace
 *       that is a log line is a trace an operator can query in Grafana with
 *       nothing else installed. This is the default for the same reason the
 *       diagnostic tools default to mock: the whole Phase 10 demo has to run on
 *       a laptop with no extra service to start.</li>
 *   <li>{@code langfuse} - the same trace, POSTed to a Langfuse instance's
 *       ingestion API. Off the laptop and into a real trace UI, with no change
 *       to the agent's behaviour.</li>
 * </ul>
 *
 * <p>{@code costPerModelCallUsd} and {@code costPerToolCallUsd} exist because
 * cost is the one evaluation dimension the agent cannot read off its own
 * response: a local Ollama call has no invoice. The agent therefore
 * <em>estimates</em> it, from a number of model calls and tool calls, with the
 * two rates as configuration. The estimate is labelled as an estimate wherever
 * it is shown, and the rates default to a small hosted model's, so a deployment
 * running a local model can set both to zero and get an honest zero rather than
 * a made-up number.
 */
@ConfigurationProperties(prefix = "agent.tracing")
public record TracingProperties(
        @DefaultValue("log") String mode,
        @DefaultValue("http://langfuse.monitoring.svc.cluster.local:3000") String host,
        @DefaultValue("") String publicKey,
        @DefaultValue("") String secretKey,
        @DefaultValue("5") int timeoutSeconds,
        @DefaultValue("devsecops-agentic-platform") String release,
        @DefaultValue("local") String environment,
        @DefaultValue("0.0002") double costPerModelCallUsd,
        @DefaultValue("0.00002") double costPerToolCallUsd) {

    /** True when traces should be sent to a Langfuse instance. */
    public boolean langfuse() {
        return "langfuse".equalsIgnoreCase(mode);
    }

    /** True when the mode asks for a structured line per trace. */
    public boolean logs() {
        return "log".equalsIgnoreCase(mode);
    }

    /**
     * Whether both Langfuse credentials are configured.
     *
     * <p>Asked rather than assumed, because "the mode is langfuse but nobody set
     * the keys" is a deployment mistake that must not turn into a stream of 401s
     * - the publisher falls back to the log sink and says so once at startup.
     */
    public boolean hasCredentials() {
        return publicKey != null && !publicKey.isBlank() && secretKey != null && !secretKey.isBlank();
    }

    /**
     * The estimated cost of one agent request.
     *
     * @param modelCalls how many times an engine wrote a diagnosis in this
     *                   request; zero for the heuristic provider, which is why a
     *                   local deployment's estimate is tool calls alone
     * @param toolCalls  how many tool calls the request spent
     * @return the estimate in US dollars, rounded to six decimal places so a
     *         metric does not carry meaningless float noise
     */
    public double estimateCostUsd(int modelCalls, int toolCalls) {
        double estimate = Math.max(0, modelCalls) * costPerModelCallUsd
                + Math.max(0, toolCalls) * costPerToolCallUsd;
        return Math.round(estimate * 1_000_000d) / 1_000_000d;
    }
}
