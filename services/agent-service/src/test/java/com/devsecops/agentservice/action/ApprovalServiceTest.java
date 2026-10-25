package com.devsecops.agentservice.action;

import com.devsecops.agentservice.TestProperties;
import com.devsecops.agentservice.audit.AuditLog;
import com.devsecops.agentservice.config.AgentProperties;
import com.devsecops.agentservice.error.AgentRequestException;
import com.devsecops.agentservice.error.AgentUnauthorizedException;
import com.devsecops.agentservice.tools.DiagnosticTools;
import com.devsecops.agentservice.tools.MockDiagnosticTools;
import com.devsecops.agentservice.tools.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The approval gate, tested where it matters: the order of its refusals and the
 * exactly-once property.
 *
 * <p>The clock is injected and mutable, so "the proposal expired" is a fact
 * about a moved clock rather than a test that sleeps for fifteen minutes. The
 * executor is the recording mock, so "the executor was reached once" is an
 * assertion rather than an inference - which is the whole reason the mock
 * records its executions.
 */
class ApprovalServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-25T09:00:00Z");

    private final AuditLog auditLog = new AuditLog(new ObjectMapper());
    private final MutableClock clock = new MutableClock(NOW);

    @Test
    void refusesEveryApprovalWhenNoTokenIsConfigured() {
        MockActionExecutor executor = new MockActionExecutor();
        ApprovalService service = service(TestProperties.defaults(), executor,
                new MockDiagnosticTools(TestProperties.defaults()));
        ActionProposal proposal = proposeRestart(service);

        assertThat(service.approvalsEnabled()).isFalse();
        assertThatThrownBy(() -> service.decide(proposal.id(), true, "anything", "me"))
                .isInstanceOf(AgentUnauthorizedException.class)
                .hasMessageContaining("no approval token is configured");
        assertThat(executor.executions()).isEmpty();
    }

    @Test
    void refusesAWrongToken() {
        MockActionExecutor executor = new MockActionExecutor();
        ApprovalService service = service(TestProperties.withApproval("s3cret"), executor,
                new MockDiagnosticTools(TestProperties.withApproval("s3cret")));
        ActionProposal proposal = proposeRestart(service);

        assertThat(service.approvalsEnabled()).isTrue();
        assertThatThrownBy(() -> service.decide(proposal.id(), true, "s3cres", "me"))
                .isInstanceOf(AgentUnauthorizedException.class)
                .hasMessageContaining("missing or wrong");
        assertThat(executor.executions()).isEmpty();
    }

    @Test
    void refusesAnIdThatNamesNoProposalEvenBeforeCheckingTheToken() {
        ApprovalService service = service(TestProperties.defaults(), new MockActionExecutor(),
                new MockDiagnosticTools(TestProperties.defaults()));

        assertThatThrownBy(() -> service.decide("nope", true, "anything", "me"))
                .isInstanceOf(AgentRequestException.class)
                .hasMessageContaining("There is no proposed action with id 'nope'");
    }

    @Test
    void executesAnApprovedActionExactlyOnce() {
        MockActionExecutor executor = new MockActionExecutor();
        ApprovalService service = service(TestProperties.withApproval("s3cret"), executor,
                new MockDiagnosticTools(TestProperties.withApproval("s3cret")));
        ActionProposal proposal = proposeRestart(service);

        ApprovalResult result = service.decide(proposal.id(), true, "s3cret", "alice");

        assertThat(result.decision()).isEqualTo("APPROVED");
        assertThat(result.status()).isEqualTo(ActionStatus.EXECUTED);
        assertThat(result.executed()).isTrue();
        assertThat(result.decidedBy()).isEqualTo("alice");
        assertThat(executor.executions()).singleElement()
                .satisfies(executed -> assertThat(executed.id()).isEqualTo(proposal.id()));
    }

    @Test
    void aSecondDecisionIsAnIdempotentDuplicateAndDoesNotExecuteAgain() {
        MockActionExecutor executor = new MockActionExecutor();
        ApprovalService service = service(TestProperties.withApproval("s3cret"), executor,
                new MockDiagnosticTools(TestProperties.withApproval("s3cret")));
        ActionProposal proposal = proposeRestart(service);
        service.decide(proposal.id(), true, "s3cret", "alice");

        ApprovalResult second = service.decide(proposal.id(), true, "s3cret", "bob");

        assertThat(second.decision()).isEqualTo("DUPLICATE");
        assertThat(second.status()).isEqualTo(ActionStatus.EXECUTED);
        assertThat(second.executed()).isTrue();
        assertThat(second.message()).contains("already decided");
        assertThat(executor.executions()).hasSize(1);
    }

    @Test
    void recordsARejectionWithoutCallingTheExecutor() {
        MockActionExecutor executor = new MockActionExecutor();
        ApprovalService service = service(TestProperties.withApproval("s3cret"), executor,
                new MockDiagnosticTools(TestProperties.withApproval("s3cret")));
        ActionProposal proposal = proposeRestart(service);

        ApprovalResult result = service.decide(proposal.id(), false, "s3cret", "alice");

        assertThat(result.decision()).isEqualTo("REJECTED");
        assertThat(result.status()).isEqualTo(ActionStatus.REJECTED);
        assertThat(result.executed()).isFalse();
        assertThat(executor.executions()).isEmpty();
    }

    @Test
    void expiresAProposalWhoseTimeToLiveHasPassed() {
        MockActionExecutor executor = new MockActionExecutor();
        ApprovalService service = service(TestProperties.withApproval("s3cret"), executor,
                new MockDiagnosticTools(TestProperties.withApproval("s3cret")));
        ActionProposal proposal = proposeRestart(service);

        clock.advance(Duration.ofSeconds(901));

        ApprovalResult result = service.decide(proposal.id(), true, "s3cret", "alice");

        assertThat(result.decision()).isEqualTo("EXPIRED");
        assertThat(result.status()).isEqualTo(ActionStatus.EXPIRED);
        assertThat(result.executed()).isFalse();
        assertThat(executor.executions()).isEmpty();
    }

    @Test
    void refusesWhenTheTargetNoLongerExists() {
        MockActionExecutor executor = new MockActionExecutor();
        DiagnosticTools tools = mock(DiagnosticTools.class);
        when(tools.kubectlGet(any(), any(), any())).thenReturn(ToolResult.failed(
                "kubectlReadOnly", "Read Deployment devsecops/user-service: not found", "not found"));
        ApprovalService service = service(TestProperties.withApproval("s3cret"), executor, tools);
        ActionProposal proposal = proposeRestart(service);

        ApprovalResult result = service.decide(proposal.id(), true, "s3cret", "alice");

        assertThat(result.decision()).isEqualTo("INVALID_TARGET");
        assertThat(result.status()).isEqualTo(ActionStatus.INVALID_TARGET);
        assertThat(result.executed()).isFalse();
        assertThat(result.message()).contains("not found");
        assertThat(executor.executions()).isEmpty();
    }

    // --- helpers -----------------------------------------------------------

    private ApprovalService service(AgentProperties properties,
                                    ActionExecutor executor,
                                    DiagnosticTools tools) {
        return new ApprovalService(properties, executor, tools, auditLog, clock);
    }

    private static ActionProposal proposeRestart(ApprovalService service) {
        return service.propose(
                ActionType.RESTART_WORKLOAD,
                "Deployment",
                "devsecops",
                "user-service",
                Map.of(),
                "The container is crash-looping.",
                List.of("Back-off restarting failed container user-service"),
                "deployment devsecops/user-service");
    }

    /** A clock a test can move, so a deadline is reached without waiting for it. */
    private static final class MutableClock extends Clock {

        private final ZoneId zone = ZoneOffset.UTC;
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            this.now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId newZone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
