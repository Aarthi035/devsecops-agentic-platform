package com.devsecops.agentservice.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devsecops.agentservice.error.AgentRequestException;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The closed list of things the agent may propose, and the parameter checks that
 * make a proposal refusable before it is stored.
 *
 * <p>Two behaviours here are deliberate and worth stating: an unrecognised kind
 * is refused rather than coerced, and an out-of-range parameter is refused
 * rather than clamped - a scale request for 500 that quietly became 20 has done
 * something other than what was asked.
 */
class ActionTypeTest {

    @Test
    void serialisesAsTheWireNameNotTheEnumConstant() {
        assertThat(ActionType.RESTART_WORKLOAD.wireName()).isEqualTo("restart_workload");
        assertThat(ActionType.SCALE_WORKLOAD.wireName()).isEqualTo("scale_workload");
        assertThat(ActionType.ROLLBACK_DEPLOYMENT.wireName()).isEqualTo("rollback_deployment");
    }

    @Test
    void parsesEitherTheWireNameOrTheConstantName() {
        assertThat(ActionType.parse("scale_workload")).contains(ActionType.SCALE_WORKLOAD);
        assertThat(ActionType.parse("SCALE_WORKLOAD")).contains(ActionType.SCALE_WORKLOAD);
        assertThat(ActionType.parse("delete_namespace")).isEmpty();
        assertThat(ActionType.parse(null)).isEmpty();
    }

    @Test
    void appliesToTheKindsItDeclaresAndNoOthers() {
        assertThat(ActionType.RESTART_WORKLOAD.supportsKind("Deployment")).isTrue();
        assertThat(ActionType.RESTART_WORKLOAD.supportsKind("statefulsets")).isTrue();
        assertThat(ActionType.RESTART_WORKLOAD.supportsKind("Pod")).isFalse();
        assertThat(ActionType.RESTART_WORKLOAD.supportsKind(null)).isFalse();
        assertThat(ActionType.SCALE_WORKLOAD.supportsKind("DaemonSet")).isFalse();
        assertThat(ActionType.ROLLBACK_DEPLOYMENT.supportsKind("StatefulSet")).isFalse();
    }

    @Test
    void restartTakesNoParameters() {
        ActionType.RESTART_WORKLOAD.validateParameters(Map.of());

        assertThatThrownBy(() -> ActionType.RESTART_WORKLOAD.validateParameters(Map.of("replicas", 3)))
                .isInstanceOf(AgentRequestException.class)
                .hasMessageContaining("restart_workload takes no parameters");
    }

    @Test
    void scaleRequiresAReplicaCountInsideItsRange() {
        ActionType.SCALE_WORKLOAD.validateParameters(Map.of("replicas", 3));
        ActionType.SCALE_WORKLOAD.validateParameters(Map.of("replicas", "3"));

        assertThatThrownBy(() -> ActionType.SCALE_WORKLOAD.validateParameters(Map.of()))
                .isInstanceOf(AgentRequestException.class)
                .hasMessage("The parameter 'replicas' is required for this action.");
        assertThatThrownBy(() -> ActionType.SCALE_WORKLOAD.validateParameters(Map.of("replicas", 21)))
                .isInstanceOf(AgentRequestException.class)
                .hasMessage("replicas must be between 0 and 20, but was 21.");
        assertThatThrownBy(() -> ActionType.SCALE_WORKLOAD.validateParameters(Map.of("replicas", -1)))
                .isInstanceOf(AgentRequestException.class)
                .hasMessage("replicas must be between 0 and 20, but was -1.");
        assertThatThrownBy(() -> ActionType.SCALE_WORKLOAD.validateParameters(Map.of("replicas", "many")))
                .isInstanceOf(AgentRequestException.class)
                .hasMessage("The parameter 'replicas' must be a whole number, but was 'many'.");
    }

    @Test
    void rollbackTakesAnOptionalPositiveRevision() {
        ActionType.ROLLBACK_DEPLOYMENT.validateParameters(Map.of());
        ActionType.ROLLBACK_DEPLOYMENT.validateParameters(Map.of("revision", 4));

        assertThatThrownBy(() -> ActionType.ROLLBACK_DEPLOYMENT.validateParameters(Map.of("revision", 0)))
                .isInstanceOf(AgentRequestException.class)
                .hasMessage("revision must be a positive number, but was 0.");
    }
}
