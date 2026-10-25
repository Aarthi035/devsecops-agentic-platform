package com.devsecops.agentservice.tracing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.devsecops.agentservice.config.TracingProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The agent's own metrics, which are the Grafana panel's data source.
 *
 * <p>They are recorded in every tracing mode, so these tests say what a
 * deployment that traces nothing still gets: request counts and latency by
 * endpoint, tool calls by tool mode, diagnoses by engine and recommended action,
 * and - separately - how often the agent was unsure. That last number is the one
 * a total hides, which is why it has its own counter.
 */
class AgentTelemetryTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AgentTelemetry telemetry = new AgentTelemetry(registry,
            new TracingProperties("log", "http://langfuse.invalid:3000", "", "",
                    5, "release", "test", 0.0002, 0.00002));

    @Test
    void countsARequestAndTimesItByEndpoint() {
        telemetry.recordRequest("/alert", "accepted", 42);

        assertThat(registry.get("agent.requests").tags("endpoint", "/alert", "outcome", "accepted")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("agent.request.duration").tag("endpoint", "/alert")
                .timer().count()).isEqualTo(1L);
        assertThat(registry.get("agent.request.duration").tag("endpoint", "/alert")
                .timer().totalTime(TimeUnit.MILLISECONDS)).isEqualTo(42.0);
    }

    @Test
    void tagsADiagnosisWithItsEngineAndRecommendedAction() {
        telemetry.recordDiagnosis("/alert", "heuristic", "restart", 0.8);

        assertThat(registry.get("agent.diagnoses")
                .tags("endpoint", "/alert", "provider", "heuristic", "action", "restart")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.find("agent.low_confidence_diagnoses").counter()).isNull();
    }

    @Test
    void countsALowConfidenceDiagnosisSeparately() {
        telemetry.recordDiagnosis("/alert", "heuristic", "none", 0.2);

        assertThat(registry.get("agent.diagnoses")
                .tags("endpoint", "/alert", "provider", "heuristic", "action", "none")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("agent.low_confidence_diagnoses").tag("endpoint", "/alert")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void doesNotCountANegativeConfidenceAsALowConfidenceDiagnosis() {
        // -1 means there was no diagnosis at all, which is not the same as an
        // unsure one.
        telemetry.recordDiagnosis("/alert", "heuristic", "none", -1);

        assertThat(registry.find("agent.low_confidence_diagnoses").counter()).isNull();
    }

    @Test
    void countsToolCallsByModeAndSkipsWhenThereWereNone() {
        telemetry.recordToolCalls("/alert", 6, "mock");
        telemetry.recordToolCalls("/approve", 0, "");

        assertThat(registry.get("agent.tool_calls").tags("endpoint", "/alert", "mode", "mock")
                .counter().count()).isEqualTo(6.0);
        assertThat(registry.find("agent.tool_calls").tag("endpoint", "/approve").counter()).isNull();
    }

    @Test
    void accumulatesTheEstimatedCostAndSkipsAZero() {
        telemetry.recordEstimatedCost("/alert", 0.00012);
        telemetry.recordEstimatedCost("/alert", 0.00012);
        telemetry.recordEstimatedCost("/approve", 0);

        assertThat(registry.get("agent.estimated_cost_usd").tag("endpoint", "/alert")
                .counter().count()).isCloseTo(0.00024, within(1e-9));
        assertThat(registry.find("agent.estimated_cost_usd").tag("endpoint", "/approve").counter()).isNull();
    }

    @Test
    void delegatesTheEstimateToTheConfiguredRates() {
        assertThat(telemetry.estimateCostUsd(3, 12)).isEqualTo(0.00084);
        assertThat(telemetry.estimateCostUsd(0, 0)).isZero();
    }
}
