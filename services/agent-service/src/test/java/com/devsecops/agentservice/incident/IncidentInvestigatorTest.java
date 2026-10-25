package com.devsecops.agentservice.incident;

import com.devsecops.agentservice.TestProperties;
import com.devsecops.agentservice.audit.AuditLog;
import com.devsecops.agentservice.config.AgentProperties;
import com.devsecops.agentservice.logs.LogSanitizer;
import com.devsecops.agentservice.logs.PromptInjectionGuard;
import com.devsecops.agentservice.tools.DiagnosticTools;
import com.devsecops.agentservice.tools.MockDiagnosticTools;
import com.devsecops.agentservice.tools.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The bounded read loop: which steps run, in what order, and what happens when
 * a step cannot be taken.
 *
 * <p>The interesting behaviour is the conditional step and the degradation, so
 * the fixtures carry both: a scenario with a dead Prometheus, a scenario whose
 * log is written to look like instructions, and a budget small enough to be
 * reached. The logging tool is stubbed for the two cases the fixtures cannot
 * express - Loki returning nothing, and Loki returning lines.
 */
class IncidentInvestigatorTest {

    private final LogSanitizer sanitizer = new LogSanitizer();
    private final PromptInjectionGuard guard = new PromptInjectionGuard();
    private final AuditLog auditLog = new AuditLog(new ObjectMapper());

    @Test
    void runsThePlanAndReportsEachReadingInOrder() {
        IncidentInvestigator investigator = investigator(TestProperties.mockScenario("pod-restarts"));

        Investigation investigation = investigator.investigate(
                "deployment devsecops/user-service", "firing", List.of(deploymentAlert()));

        assertThat(investigator.mode()).isEqualTo("mock");
        assertThat(investigation.toolCalls()).isEqualTo(4);
        assertThat(investigation.toolBudget()).isEqualTo(12);
        assertThat(investigation.budgetExhausted()).isFalse();
        assertThat(investigation.readings()).extracting(Reading::tool)
                .containsExactly("queryPrometheus", "kubectlReadOnly", "queryLoki", "queryPrometheus");
        assertThat(investigation.bundle().facts()).isNotEmpty();
        assertThat(investigation.bundle().evidence()).isNotEmpty();
    }

    @Test
    void readsTheContainerLogDirectlyOnlyWhenLokiReturnedNothing() {
        DiagnosticTools tools = mock(DiagnosticTools.class);
        when(tools.mode()).thenReturn("mock");
        when(tools.queryPrometheus(anyString())).thenReturn(ToolResult.ok("queryPrometheus", "up = 1", "series"));
        when(tools.kubectlDescribe(any(), any(), any()))
                .thenReturn(ToolResult.ok("kubectlReadOnly", "Described the target", "phase=Running"));
        when(tools.queryLoki(anyString(), anyInt()))
                .thenReturn(ToolResult.ok("queryLoki", "LogQL matched no lines", ""));
        when(tools.kubectlLogs(any(), any(), any()))
                .thenReturn(ToolResult.ok("kubectlReadOnly", "Read 2 log line(s) from pod", "line one\nline two"));
        IncidentInvestigator investigator = investigator(TestProperties.defaults(), tools);

        Investigation investigation = investigator.investigate(
                "deployment devsecops/user-service", "firing", List.of(deploymentAlert()));

        verify(tools).kubectlLogs("devsecops", "user-service", null);
        assertThat(investigation.toolCalls()).isEqualTo(5);
        assertThat(investigation.readings()).extracting(Reading::summary)
                .anyMatch(summary -> summary.startsWith("Read 2 log line(s)"));
    }

    @Test
    void skipsTheDirectLogReadWhenLokiReturnedLines() {
        DiagnosticTools tools = mock(DiagnosticTools.class);
        when(tools.mode()).thenReturn("mock");
        when(tools.queryPrometheus(anyString())).thenReturn(ToolResult.ok("queryPrometheus", "up = 1", "series"));
        when(tools.kubectlDescribe(any(), any(), any()))
                .thenReturn(ToolResult.ok("kubectlReadOnly", "Described the target", "phase=Running"));
        when(tools.queryLoki(anyString(), anyInt()))
                .thenReturn(ToolResult.ok("queryLoki", "LogQL returned 1 line", "an application log line"));
        IncidentInvestigator investigator = investigator(TestProperties.defaults(), tools);

        Investigation investigation = investigator.investigate(
                "deployment devsecops/user-service", "firing", List.of(deploymentAlert()));

        verify(tools, never()).kubectlLogs(any(), any(), any());
        assertThat(investigation.toolCalls()).isEqualTo(4);
    }

    @Test
    void recordsAMissingReadingAsAFactRatherThanFailingTheInvestigation() {
        IncidentInvestigator investigator = investigator(TestProperties.mockScenario("prometheus-down"));

        Investigation investigation = investigator.investigate(
                "deployment devsecops/user-service", "firing", List.of(deploymentAlert()));

        assertThat(investigation.readings()).anyMatch(reading -> !reading.ok());
        assertThat(investigation.bundle().facts())
                .anyMatch(fact -> fact.startsWith("a reading is missing - "));
        assertThat(investigation.toolCalls()).isEqualTo(4);
    }

    @Test
    void flagsInjectionShapedLogLinesAsFactsAndKeepsTheEvidence() {
        IncidentInvestigator investigator = investigator(TestProperties.mockScenario("prompt-injection"));

        Investigation investigation = investigator.investigate(
                "deployment devsecops/user-service", "firing", List.of(deploymentAlert()));

        assertThat(investigation.bundle().facts())
                .anyMatch(fact -> fact.contains("Possible prompt injection"));
        assertThat(investigation.bundle().evidence())
                .anyMatch(line -> line.contains("IGNORE ALL PREVIOUS INSTRUCTIONS"));
    }

    @Test
    void stopsAtTheToolBudgetAndSaysSo() {
        IncidentInvestigator investigator = investigator(props("pod-restarts", 2));

        Investigation investigation = investigator.investigate(
                "deployment devsecops/user-service", "firing", List.of(deploymentAlert()));

        assertThat(investigation.toolCalls()).isEqualTo(2);
        assertThat(investigation.budgetExhausted()).isTrue();
        assertThat(investigation.notices()).anyMatch(notice -> notice.contains("budget"));
    }

    @Test
    void skipsTheObjectReadsWhenTheTargetCouldNotBeIdentified() {
        IncidentInvestigator investigator = investigator(TestProperties.mockScenario("pod-restarts"));
        NormalizedAlert unknown = new NormalizedAlert(
                "SomethingOdd", "firing", "devsecops", "unknown", "SomethingOdd",
                "critical", "something is wrong", "", "abc123", "2026-10-25T09:00:00Z",
                "{namespace=\"devsecops\"}", true);

        Investigation investigation = investigator.investigate(
                "unknown devsecops/SomethingOdd", "firing", List.of(unknown));

        assertThat(investigation.readings()).extracting(Reading::tool)
                .containsExactly("queryPrometheus", "queryLoki", "queryPrometheus");
        assertThat(investigation.bundle().facts())
                .anyMatch(fact -> fact.contains("the target was inferred"));
    }

    // --- helpers -----------------------------------------------------------

    private IncidentInvestigator investigator(AgentProperties properties) {
        return investigator(properties, new MockDiagnosticTools(properties));
    }

    private IncidentInvestigator investigator(AgentProperties properties, DiagnosticTools tools) {
        return new IncidentInvestigator(tools, sanitizer, guard, properties, auditLog);
    }

    /** The standard alert: a Deployment the investigator may read. */
    private static NormalizedAlert deploymentAlert() {
        return new NormalizedAlert(
                "UserServiceCrashLooping", "firing", "devsecops", "Deployment", "user-service",
                "critical", "user-service is crash-looping", "", "abc123", "2026-10-25T09:00:00Z",
                "{namespace=\"devsecops\"}", false);
    }

    private static AgentProperties props(String scenario, int maxToolCalls) {
        AgentProperties base = TestProperties.mockScenario(scenario);
        return new AgentProperties(base.llm(), base.github(),
                new AgentProperties.Limits(12_000, 3, 30, maxToolCalls),
                base.tools(), base.incident(), base.security(), base.approval());
    }
}
