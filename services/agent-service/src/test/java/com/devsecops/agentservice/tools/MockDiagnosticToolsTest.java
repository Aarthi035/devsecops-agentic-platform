package com.devsecops.agentservice.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.devsecops.agentservice.TestProperties;
import org.junit.jupiter.api.Test;

/**
 * The fixtures the whole of Phase 9 runs on.
 *
 * <p>These look like tests of a stub, and they are - but the stub is the
 * evidence source for every incident test above it, so a fixture that silently
 * answers the wrong thing would make a green diagnosis test meaningless. What is
 * pinned here is the routing (a query is matched by keyword, not by exact text)
 * and the two properties the investigator relies on: a scenario can report a
 * service as unreachable, and an unknown scenario degrades instead of failing at
 * startup.
 */
class MockDiagnosticToolsTest {

    private static MockDiagnosticTools tools(String scenario) {
        return new MockDiagnosticTools(TestProperties.mockScenario(scenario));
    }

    @Test
    void announcesItsModeAndTheScenarioInUse() {
        MockDiagnosticTools tools = tools("oom-killed");

        assertThat(tools.mode()).isEqualTo("mock");
        assertThat(tools.scenario()).isEqualTo("oom-killed");
    }

    @Test
    void fallsBackToTheDefaultScenarioRatherThanFailingAtStartup() {
        assertThat(tools("no-such-fixture").scenario()).isEqualTo("pod-restarts");
        assertThat(tools(null).scenario()).isEqualTo("pod-restarts");
    }

    @Test
    void routesAPromqlQueryByKeywordSoAPhrasedExpressionStillAnswers() {
        MockDiagnosticTools tools = tools("pod-restarts");

        assertThat(tools.queryPrometheus("up{service=\"user-service\"}").body()).contains("= 1");
        assertThat(tools.queryPrometheus("kube_pod_container_status_restarts_total").body()).contains("= 7");
        assertThat(tools.queryPrometheus("sum(rate(http_server_errors_total[5m]))").body()).contains("= 0.02");
        assertThat(tools.queryPrometheus("histogram_quantile(0.99, rate(x_bucket[5m]))").body())
                .contains("0.42");
    }

    @Test
    void reportsAMissingReadingWhenTheFixtureSaysPrometheusIsDown() {
        ToolResult result = tools("prometheus-down").queryPrometheus("up");

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).isEqualTo("connection refused");
        assertThat(result.body()).isEmpty();
        assertThat(result.summary()).contains("Prometheus did not answer");
    }

    @Test
    void capsTheLogLinesItReturns() {
        MockDiagnosticTools tools = tools("pod-restarts");

        assertThat(tools.queryLoki("{namespace=\"devsecops\"}", 1).body().lines()).hasSize(1);
        assertThat(tools.queryLoki("{namespace=\"devsecops\"}", 200).body().lines()).hasSize(3);
    }

    @Test
    void returnsTheObjectStateAlongsideItsEvents() {
        MockDiagnosticTools tools = tools("pod-restarts");

        assertThat(tools.kubectlGet("Pod", "devsecops", "user-service-7d9f-abc").body())
                .contains("CrashLoopBackOff");

        ToolResult described = tools.kubectlDescribe("Deployment", "devsecops", "user-service");
        assertThat(described.ok()).isTrue();
        assertThat(described.summary()).contains("1 event(s)");
        assertThat(described.body()).contains("Back-off restarting failed container");
    }

    @Test
    void servesTheSecurityReportTheScenarioDefines() {
        assertThat(tools("pod-restarts").readSecurityReport("/reports/trivy.json").body())
                .contains("CVE-2024-3094");
        assertThat(tools("clean").readSecurityReport(null).body()).contains("\"Results\":[]");
    }
}
