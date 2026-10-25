package com.devsecops.agentservice.action;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mock executor: it contacts nothing, but it records what it was asked to
 * do.
 *
 * <p>That record is the point. An executor that silently did nothing would make
 * "the approved action reached the executor with the right parameters"
 * unassertable, which is exactly the behaviour an approval gate exists to
 * guarantee.
 */
class MockActionExecutorTest {

    private final MockActionExecutor executor = new MockActionExecutor();

    @Test
    void recordsEveryExecutionAndReportsMockMode() {
        executor.execute(proposal(ActionType.RESTART_WORKLOAD, Map.of()));
        executor.execute(proposal(ActionType.SCALE_WORKLOAD, Map.of("replicas", 4)));

        assertThat(executor.mode()).isEqualTo("mock");
        assertThat(executor.executions()).hasSize(2);
        assertThat(executor.executions().get(1).parameters()).containsEntry("replicas", 4);
    }

    @Test
    void answersWithASuccessThatSaysNoClusterWasContacted() {
        ActionOutcome outcome = executor.execute(proposal(ActionType.RESTART_WORKLOAD, Map.of()));

        assertThat(outcome.executed()).isTrue();
        assertThat(outcome.message()).contains("no cluster was contacted");
        assertThat(outcome.detail()).isEqualTo("mock");
    }

    @Test
    void resetForgetsTheRecord() {
        executor.execute(proposal(ActionType.RESTART_WORKLOAD, Map.of()));

        executor.reset();

        assertThat(executor.executions()).isEmpty();
    }

    private static ActionProposal proposal(ActionType type, Map<String, Object> parameters) {
        return ActionProposal.create(
                type, "Deployment", "devsecops", "user-service",
                parameters,
                "The evidence points at this workload.",
                List.of("CrashLoopBackOff"),
                "deployment devsecops/user-service",
                Instant.parse("2026-10-25T09:00:00Z"),
                Duration.ofMinutes(15));
    }
}
