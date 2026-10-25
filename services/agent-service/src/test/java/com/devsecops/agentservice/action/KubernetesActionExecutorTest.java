package com.devsecops.agentservice.action;

import com.devsecops.agentservice.TestProperties;
import tools.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The live executor's first decision: with no executor credential it refuses
 * every action, before it looks at the proposal or builds a request.
 *
 * <p>This is the safety property the whole approval gate rests on - a
 * deployment that was only ever meant to diagnose stays one even if a bug hands
 * it a valid proposal - so it is the one path that can be tested without a
 * cluster. Everything past it needs a real API server and belongs to a
 * different kind of test.
 */
class KubernetesActionExecutorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void refusesEveryActionWhenNoExecutorCredentialIsConfigured() {
        KubernetesActionExecutor executor =
                new KubernetesActionExecutor(TestProperties.defaults(), objectMapper);

        ActionProposal proposal = ActionProposal.create(
                ActionType.RESTART_WORKLOAD, "Deployment", "devsecops", "user-service",
                Map.of(),
                "The container is crash-looping.",
                List.of("Back-off restarting failed container user-service"),
                "deployment devsecops/user-service",
                Instant.parse("2026-10-25T09:00:00Z"),
                Duration.ofMinutes(15));

        ActionOutcome outcome = executor.execute(proposal);

        assertThat(outcome.executed()).isFalse();
        assertThat(outcome.detail()).isEqualTo("no executor credential");
        assertThat(outcome.message()).contains("no executor credential");
        assertThat(outcome.action()).isEqualTo("restart_workload deployment devsecops/user-service");
    }

    @Test
    void reportsTheLiveModeSoAResponseCanSayWhetherAnActionWouldDoAnything() {
        KubernetesActionExecutor executor =
                new KubernetesActionExecutor(TestProperties.defaults(), objectMapper);

        assertThat(executor.mode()).isEqualTo("live");
    }
}
