package com.devsecops.agentservice.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.devsecops.agentservice.TestProperties;
import com.devsecops.agentservice.config.AgentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The one boundary the live tools own rather than the caller: a requested
 * report path cannot leave {@code agent.security.report-directory}.
 *
 * <p>Tested against the live implementation rather than the mock, because the
 * mock deliberately does not implement the jail - it never touches a filesystem.
 * The point of the test is that {@code POST /analyze-security} with an arbitrary
 * {@code reportPath} cannot be turned into a file-read primitive, so the
 * assertions are about refusals.
 */
class HttpDiagnosticToolsReportPathTest {

    @TempDir
    Path reports;

    @Test
    void readsAReportInsideTheConfiguredDirectory() throws IOException {
        Files.writeString(reports.resolve("trivy.json"), "{\"Results\":[]}");

        ToolResult result = toolsFor(reports, 2_097_152).readSecurityReport("trivy.json");

        assertThat(result.ok()).isTrue();
        assertThat(result.body()).isEqualTo("{\"Results\":[]}");
    }

    @Test
    void resolvesARelativePathAgainstTheReportDirectory() throws IOException {
        Files.createDirectory(reports.resolve("nested"));
        Files.writeString(reports.resolve("nested").resolve("scan.json"), "{\"Results\":[]}");

        ToolResult result = toolsFor(reports, 2_097_152).readSecurityReport("nested/scan.json");

        assertThat(result.ok()).isTrue();
    }

    @Test
    void refusesARelativePathThatEscapesTheReportDirectory() throws IOException {
        Files.writeString(reports.getParent().resolve("outside.json"), "{\"Results\":[]}");

        ToolResult result = toolsFor(reports, 2_097_152).readSecurityReport("../outside.json");

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("outside the report directory");
    }

    @Test
    void refusesAnAbsolutePathOutsideTheReportDirectory() {
        String outside = reports.getParent().resolve("elsewhere.json").toAbsolutePath().toString();

        ToolResult result = toolsFor(reports, 2_097_152).readSecurityReport(outside);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("outside the report directory");
    }

    @Test
    void refusesAReportAboveTheSizeLimit() throws IOException {
        Files.writeString(reports.resolve("big.json"), "0123456789");

        ToolResult result = toolsFor(reports, 5).readSecurityReport("big.json");

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).isEqualTo("report too large");
    }

    @Test
    void reportsAMissingReportRatherThanThrowing() {
        ToolResult result = toolsFor(reports, 2_097_152).readSecurityReport("nothing-here.json");

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("no readable report");
    }

    private static HttpDiagnosticTools toolsFor(Path reportDirectory, int maxReportBytes) {
        AgentProperties properties = new AgentProperties(
                TestProperties.defaults().llm(),
                TestProperties.defaults().github(),
                TestProperties.defaults().limits(),
                TestProperties.liveTools(),
                TestProperties.incident(),
                new AgentProperties.Security("trivy.json", reportDirectory.toString(), 50, maxReportBytes),
                TestProperties.approval());
        return new HttpDiagnosticTools(properties, new ObjectMapper());
    }
}
