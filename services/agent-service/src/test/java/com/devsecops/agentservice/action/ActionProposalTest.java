package com.devsecops.agentservice.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devsecops.agentservice.error.AgentRequestException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Building a proposal, which is the only way one exists.
 *
 * <p>What is asserted here is that the record cannot hold something that would
 * be refused later: a name that is not a Kubernetes name, a target the action
 * does not apply to, a number outside its range, or a suggestion with no reason
 * and no evidence. Everything else the executor relies on - a numeric
 * {@code replicas} rather than the string a JSON body carries - is normalised at
 * creation, and that is asserted too.
 */
class ActionProposalTest {

    private static final Instant NOW = Instant.parse("2026-10-25T09:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(15);

    private static ActionProposal restart() {
        return ActionProposal.create(ActionType.RESTART_WORKLOAD, "Deployment", "devsecops", "user-service",
                Map.of(), "The container is crash-looping.", List.of("reason=CrashLoopBackOff"), "deployment "
                        + "devsecops/user-service", NOW, TTL);
    }

    @Test
    void recordsWhatItWillDoAndWhenItStopsBeingExecutable() {
        ActionProposal proposal = restart();

        assertThat(proposal.id()).isNotBlank();
        assertThat(proposal.type()).isEqualTo(ActionType.RESTART_WORKLOAD);
        assertThat(proposal.createdAt()).isEqualTo(NOW);
        assertThat(proposal.expiresAt()).isEqualTo(NOW.plus(TTL));
        assertThat(proposal.describe()).isEqualTo("restart_workload deployment devsecops/user-service");
    }

    @Test
    void normalisesAParameterSentAsAStringIntoTheNumberTheExecutorReads() {
        ActionProposal proposal = ActionProposal.create(ActionType.SCALE_WORKLOAD, "Deployment", "devsecops",
                "order-service", Map.of("replicas", "3"), "OOM kills under load.", List.of("restarts=4"),
                "deployment devsecops/order-service", NOW, TTL);

        assertThat(proposal.parameters().get("replicas")).isInstanceOf(Integer.class);
        assertThat(proposal.parameters().get("replicas")).isEqualTo(3);
    }

    @Test
    void dropsAParameterTheActionDoesNotDeclare() {
        ActionProposal proposal = ActionProposal.create(ActionType.SCALE_WORKLOAD, "Deployment", "devsecops",
                "order-service", Map.of("replicas", 2, "revision", 9, "namespace", "kube-system"),
                "OOM kills under load.", List.of("restarts=4"), "deployment devsecops/order-service", NOW, TTL);

        assertThat(proposal.parameters()).containsOnlyKeys("replicas");
    }

    @Test
    void refusesATargetTheActionDoesNotApplyTo() {
        assertThatThrownBy(() -> ActionProposal.create(ActionType.ROLLBACK_DEPLOYMENT, "StatefulSet",
                "devsecops", "user-service", Map.of(), "why", List.of("evidence"), "subject", NOW, TTL))
                .isInstanceOf(AgentRequestException.class)
                .hasMessage("rollback_deployment does not apply to a StatefulSet; it applies to: Deployment.");
    }

    @Test
    void refusesANameThatIsNotAKubernetesName() {
        assertThatThrownBy(() -> ActionProposal.create(ActionType.RESTART_WORKLOAD, "Deployment",
                "devsecops", "User Service", Map.of(), "why", List.of("evidence"), "subject", NOW, TTL))
                .isInstanceOf(AgentRequestException.class)
                .hasMessage("The name 'User Service' is not a valid Kubernetes name.");

        assertThatThrownBy(() -> ActionProposal.create(ActionType.RESTART_WORKLOAD, "Deployment",
                "", "user-service", Map.of(), "why", List.of("evidence"), "subject", NOW, TTL))
                .isInstanceOf(AgentRequestException.class)
                .hasMessage("The namespace of an action is required.");
    }

    @Test
    void refusesASuggestionWithNoReasonOrNoEvidence() {
        assertThatThrownBy(() -> ActionProposal.create(ActionType.RESTART_WORKLOAD, "Deployment", "devsecops",
                "user-service", Map.of(), "  ", List.of("evidence"), "subject", NOW, TTL))
                .isInstanceOf(AgentRequestException.class)
                .hasMessage("A proposed action must say why it is being proposed.");

        assertThatThrownBy(() -> ActionProposal.create(ActionType.RESTART_WORKLOAD, "Deployment", "devsecops",
                "user-service", Map.of(), "why", List.of(), "subject", NOW, TTL))
                .isInstanceOf(AgentRequestException.class)
                .hasMessage("A proposed action must cite the evidence behind it.");
    }

    @Test
    void refusesAProposalThatWouldAlreadyHaveExpired() {
        assertThatThrownBy(() -> ActionProposal.create(ActionType.RESTART_WORKLOAD, "Deployment", "devsecops",
                "user-service", Map.of(), "why", List.of("evidence"), "subject", NOW, Duration.ZERO))
                .isInstanceOf(AgentRequestException.class)
                .hasMessage("A proposed action must have a positive time to live.");
    }

    @Test
    void isPendingUpToAndIncludingItsDeadline() {
        ActionProposal proposal = restart();

        assertThat(proposal.isPendingAt(NOW)).isTrue();
        assertThat(proposal.isPendingAt(NOW.plus(TTL))).isTrue();
        assertThat(proposal.isPendingAt(NOW.plus(TTL).plusNanos(1))).isFalse();
    }
}
