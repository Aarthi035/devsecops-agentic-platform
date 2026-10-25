package com.devsecops.agentservice.incident;

import com.devsecops.agentservice.TestProperties;
import com.devsecops.agentservice.action.ActionType;
import com.devsecops.agentservice.action.ApprovalService;
import com.devsecops.agentservice.action.MockActionExecutor;
import com.devsecops.agentservice.audit.AuditLog;
import com.devsecops.agentservice.config.AgentProperties;
import com.devsecops.agentservice.diagnosis.DiagnosisEngine;
import com.devsecops.agentservice.diagnosis.HeuristicDiagnosisEngine;
import com.devsecops.agentservice.logs.LogSanitizer;
import com.devsecops.agentservice.logs.PromptInjectionGuard;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The {@code /alert} pipeline end to end, in mock mode: normalise,
 * deduplicate, investigate, diagnose, propose.
 *
 * <p>The properties tested here are the ones a reviewer would ask about: that a
 * repeat notification is not investigated twice, that a resolved alert gets
 * advice rather than an action, that nothing is proposed for a target the agent
 * cannot act on, and that a {@code scale} is only proposed with a number the
 * agent actually read.
 */
class IncidentAnalysisServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-25T09:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MutableClock clock = new MutableClock(NOW);

    @Test
    void proposesARestartWhenTheEvidenceShowsACrashLoop() {
        IncidentAnalysisService service = service(TestProperties.mockScenario("pod-restarts"),
                new MockDiagnosticTools(TestProperties.mockScenario("pod-restarts")), clock);

        IncidentAnalysisResponse response = service.analyze(firing(serviceLabels("user-service")));

        assertThat(response.accepted()).isTrue();
        assertThat(response.deduplicated()).isFalse();
        assertThat(response.subject()).isEqualTo("deployment devsecops/user-service");
        assertThat(response.diagnosis().recommendedAction()).isEqualTo("restart");
        assertThat(response.proposedAction()).isNotNull();
        assertThat(response.proposedAction().type()).isEqualTo(ActionType.RESTART_WORKLOAD);
        assertThat(response.proposedAction().kind()).isEqualTo("Deployment");
        assertThat(response.proposedAction().name()).isEqualTo("user-service");
        assertThat(response.proposedAction().parameters()).isEmpty();
        assertThat(response.proposedAction().reason()).isEqualTo(response.diagnosis().rootCause());
        assertThat(response.toolsMode()).isEqualTo("mock");
        assertThat(response.executorMode()).isEqualTo("mock");
        assertThat(response.approvalsEnabled()).isFalse();
    }

    @Test
    void deduplicatesARepeatedNotificationInsideTheWindow() {
        AgentProperties props = TestProperties.mockScenario("pod-restarts");
        IncidentAnalysisService service = service(props, new MockDiagnosticTools(props), clock);

        IncidentAnalysisResponse first = service.analyze(firing(serviceLabels("user-service")));
        IncidentAnalysisResponse second = service.analyze(firing(serviceLabels("user-service")));

        assertThat(first.deduplicated()).isFalse();
        assertThat(second.deduplicated()).isTrue();
        assertThat(second.toolCalls()).isZero();
        assertThat(second.proposedAction()).isNull();
        assertThat(second.diagnosis()).isEqualTo(first.diagnosis());
        assertThat(second.notices()).anyMatch(notice -> notice.contains("deduplication window"));
    }

    @Test
    void reinvestigatesARepeatThatIsOutsideTheWindow() {
        AgentProperties props = TestProperties.mockScenario("pod-restarts");
        IncidentAnalysisService service = service(props, new MockDiagnosticTools(props), clock);
        service.analyze(firing(serviceLabels("user-service")));

        clock.advance(Duration.ofSeconds(301));

        IncidentAnalysisResponse second = service.analyze(firing(serviceLabels("user-service")));

        assertThat(second.deduplicated()).isFalse();
        assertThat(second.toolCalls()).isGreaterThan(0);
    }

    @Test
    void doesNotTreatAResolvedNotificationAsADuplicateOfAFiringOne() {
        AgentProperties props = TestProperties.mockScenario("pod-restarts");
        IncidentAnalysisService service = service(props, new MockDiagnosticTools(props), clock);
        service.analyze(firing(serviceLabels("user-service")));

        IncidentAnalysisResponse resolved =
                service.analyze(webhook("resolved", serviceLabels("user-service"), "fp-1"));

        assertThat(resolved.deduplicated()).isFalse();
        assertThat(resolved.status()).isEqualTo("resolved");
    }

    @Test
    void proposesNoActionForAResolvedAlert() {
        AgentProperties props = TestProperties.mockScenario("pod-restarts");
        IncidentAnalysisService service = service(props, new MockDiagnosticTools(props), clock);

        IncidentAnalysisResponse response =
                service.analyze(webhook("resolved", serviceLabels("user-service"), "fp-1"));

        assertThat(response.proposedAction()).isNull();
        assertThat(response.notices()).anyMatch(notice -> notice.contains("has resolved"));
    }

    @Test
    void proposesNoActionWhenTheTargetIsAPod() {
        AgentProperties props = TestProperties.mockScenario("pod-restarts");
        IncidentAnalysisService service = service(props, new MockDiagnosticTools(props), clock);

        IncidentAnalysisResponse response = service.analyze(webhook("firing",
                Map.of("alertname", "UserServiceCrashLooping", "namespace", "devsecops",
                        "severity", "critical", "pod", "user-service-7d9f-abc"),
                "fp-pod"));

        assertThat(response.proposedAction()).isNull();
        assertThat(response.notices()).anyMatch(notice -> notice.contains("does not apply to a Pod"));
    }

    @Test
    void refusesToScaleWhenTheReplicaCountCannotBeRead() {
        AgentProperties props = TestProperties.mockScenario("oom-killed");
        IncidentAnalysisService service = service(props, new MockDiagnosticTools(props), clock);

        IncidentAnalysisResponse response =
                service.analyze(firing(serviceLabels("order-service")));

        assertThat(response.diagnosis().recommendedAction()).isEqualTo("scale");
        assertThat(response.proposedAction()).isNull();
        assertThat(response.notices()).anyMatch(notice -> notice.contains("could not be read"));
        assertThat(response.toolCalls()).isEqualTo(5);
    }

    @Test
    void proposesAScaleOneReplicaAboveTheMeasuredCount() {
        DiagnosticTools tools = mock(DiagnosticTools.class);
        when(tools.mode()).thenReturn("mock");
        when(tools.queryPrometheus(anyString())).thenReturn(ToolResult.ok("queryPrometheus", "up = 1", "series"));
        when(tools.kubectlDescribe(any(), any(), any()))
                .thenReturn(ToolResult.ok("kubectlReadOnly", "Described the target", "reason=OOMKilled"));
        when(tools.queryLoki(anyString(), anyInt()))
                .thenReturn(ToolResult.ok("queryLoki", "LogQL returned 1 line", "an application log line"));
        when(tools.kubectlGet(any(), any(), any())).thenReturn(ToolResult.ok(
                "kubectlReadOnly", "Read Deployment devsecops/order-service",
                "{\"kind\":\"Deployment\",\"spec\":{\"replicas\":3}}"));
        IncidentAnalysisService service = service(TestProperties.defaults(), tools, clock);

        IncidentAnalysisResponse response = service.analyze(firing(serviceLabels("order-service")));

        assertThat(response.diagnosis().recommendedAction()).isEqualTo("scale");
        assertThat(response.proposedAction()).isNotNull();
        assertThat(response.proposedAction().type()).isEqualTo(ActionType.SCALE_WORKLOAD);
        assertThat(response.proposedAction().parameters()).containsEntry("replicas", 4);
        assertThat(response.notices()).anyMatch(notice -> notice.contains("scales to 4"));
    }

    // --- helpers -----------------------------------------------------------

    private IncidentAnalysisService service(AgentProperties properties,
                                            DiagnosticTools tools,
                                            Clock serviceClock) {
        LogSanitizer sanitizer = new LogSanitizer();
        PromptInjectionGuard guard = new PromptInjectionGuard();
        AuditLog auditLog = new AuditLog(objectMapper);
        AlertNormalizer normalizer = new AlertNormalizer(properties);
        IncidentInvestigator investigator =
                new IncidentInvestigator(tools, sanitizer, guard, properties, auditLog);
        DiagnosisEngine engine = new HeuristicDiagnosisEngine(sanitizer);
        ApprovalService approval =
                new ApprovalService(properties, new MockActionExecutor(), tools, auditLog, serviceClock);
        return new IncidentAnalysisService(normalizer, investigator, engine, approval, tools,
                sanitizer, properties, auditLog, objectMapper, serviceClock);
    }

    /** The labels this platform's recording rules attach to a service alert. */
    private static Map<String, String> serviceLabels(String service) {
        return Map.of(
                "alertname", "ServiceCrashLooping",
                "service", service,
                "namespace", "devsecops",
                "severity", "critical");
    }

    /** A one-alert group with the standard labels, carrying {@code fp-1} as its fingerprint. */
    private static AlertmanagerWebhook firing(Map<String, String> labels) {
        return webhook("firing", labels, "fp-1");
    }

    private static AlertmanagerWebhook webhook(String status, Map<String, String> labels, String fingerprint) {
        return new AlertmanagerWebhook(
                "4", "group-1", status, "agent-service",
                Map.of(), Map.of(), Map.of(), "http://alertmanager.monitoring",
                List.of(new AlertmanagerWebhook.Alert(
                        status, labels, Map.of(), "2026-10-25T08:55:00Z", null, null, fingerprint)));
    }

    /** A clock a test can move, so a deduplication window can be left behind. */
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
