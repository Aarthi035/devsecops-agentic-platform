package com.devsecops.agentservice.tracing;

import com.devsecops.agentservice.config.TracingProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * The agent's own Micrometer metrics: latency, request counts, tool calls,
 * diagnoses and estimated cost.
 *
 * <p>Separate from {@link AgentTracePublisher} on purpose. A trace is an export
 * - it leaves the process and lands in somebody else's store - while a metric is
 * local and is what the Grafana panel on {@code /actuator/prometheus} reads. So
 * {@code agent.tracing.mode=off} silences the export and leaves the panel
 * working; the two are not the same switch even though they describe the same
 * request.
 *
 * <p>Everything here is tagged so the panel can break it down: latency and cost
 * by endpoint, tool calls by tool mode, diagnoses by engine and by the action
 * the engine recommended. A low-confidence diagnosis is counted separately
 * because "how often is the agent unsure" is the one number that says whether
 * its answers are worth reading, and it is not visible in a total.
 */
@Component
public class AgentTelemetry {

    /** Below this, a diagnosis is a guess and is counted as one. */
    private static final double LOW_CONFIDENCE = 0.5;

    private final MeterRegistry registry;
    private final TracingProperties properties;

    public AgentTelemetry(MeterRegistry registry, TracingProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    /**
     * One finished request.
     *
     * @param endpoint      the agent route, e.g. {@code /alert}
     * @param outcome       {@code accepted}, {@code rejected} or {@code error}
     * @param durationMillis how long the request took
     */
    public void recordRequest(String endpoint, String outcome, long durationMillis) {
        registry.counter("agent.requests", "endpoint", endpoint, "outcome", outcome).increment();
        registry.timer("agent.request.duration", "endpoint", endpoint)
                .record(java.time.Duration.ofMillis(Math.max(0, durationMillis)));
    }

    /**
     * The diagnosis a request produced.
     *
     * @param endpoint  the agent route
     * @param provider  which engine wrote it
     * @param action    what it recommended
     * @param confidence how sure it was; {@code -1} when there was no diagnosis
     */
    public void recordDiagnosis(String endpoint, String provider, String action, double confidence) {
        registry.counter("agent.diagnoses",
                "endpoint", endpoint, "provider", provider, "action", action).increment();
        if (confidence >= 0 && confidence < LOW_CONFIDENCE) {
            registry.counter("agent.low_confidence_diagnoses", "endpoint", endpoint).increment();
        }
    }

    /**
     * The steps a request spent.
     *
     * @param toolCalls  how many tool calls were made
     * @param toolsMode  {@code live} or {@code mock}
     */
    public void recordToolCalls(String endpoint, int toolCalls, String toolsMode) {
        if (toolCalls <= 0) {
            return;
        }
        registry.counter("agent.tool_calls",
                "endpoint", endpoint, "mode", toolsMode == null || toolsMode.isBlank() ? "none" : toolsMode)
                .increment(toolCalls);
    }

    /**
     * The request's estimated cost, accumulated in US dollars.
     *
     * <p>A counter rather than a gauge because cost only ever accrues; the panel
     * reads it with {@code rate()}, which is the same shape it reads request
     * counts with.
     */
    public void recordEstimatedCost(String endpoint, double estimatedCostUsd) {
        if (estimatedCostUsd <= 0) {
            return;
        }
        registry.counter("agent.estimated_cost_usd", "endpoint", endpoint).increment(estimatedCostUsd);
    }

    /** The estimate for one request, using the configured rates. */
    public double estimateCostUsd(int modelCalls, int toolCalls) {
        return properties.estimateCostUsd(modelCalls, toolCalls);
    }
}
