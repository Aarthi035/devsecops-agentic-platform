package com.devsecops.agentservice.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Reading a scanner report, and refusing one.
 *
 * <p>The refusals carry the weight here. A report that is missing, truncated or
 * written in the scanner's table format instead of its JSON one must come back
 * as an explicit problem - never as an empty report, because "no vulnerabilities
 * found" and "I could not read the file" are the two answers a security endpoint
 * must not confuse.
 */
class SecurityReportParserTest {

    private final SecurityReportParser parser = new SecurityReportParser(new ObjectMapper());

    @Test
    void flattensTrivysTargetsIntoOneListOfFindings() {
        SecurityReportParser.Parsed parsed = parser.parse("""
                {
                  "SchemaVersion": 2,
                  "ArtifactName": "user-service:1.4.2",
                  "Results": [
                    {
                      "Target": "user-service:1.4.2 (ubuntu 22.04)",
                      "Vulnerabilities": [
                        {"VulnerabilityID": "CVE-2024-3094", "PkgName": "xz-utils",
                         "InstalledVersion": "5.2.5-2ubuntu1", "FixedVersion": "5.2.5-2ubuntu1.1",
                         "Severity": "CRITICAL", "Title": "xz: malicious code in liblzma"}
                      ]
                    },
                    {
                      "Target": "app/spring-core-6.1.14.jar",
                      "Vulnerabilities": [
                        {"VulnerabilityID": "CVE-2024-22243", "PkgName": "org.springframework:spring-web",
                         "InstalledVersion": "6.1.14", "Severity": "high"}
                      ]
                    }
                  ]
                }
                """);

        assertThat(parsed.usable()).isTrue();
        assertThat(parsed.problem()).isNull();

        SecurityReport report = parsed.report();
        assertThat(report.artifact()).isEqualTo("user-service:1.4.2");
        assertThat(report.findings()).hasSize(2);
        assertThat(report.highestSeverity()).isEqualTo("CRITICAL");
        assertThat(report.severityCounts())
                .containsEntry("CRITICAL", 1)
                .containsEntry("HIGH", 1)
                .doesNotContainKey("LOW");
        assertThat(report.findings().get(0).target()).isEqualTo("user-service:1.4.2 (ubuntu 22.04)");
        assertThat(report.findings().get(1).severity()).isEqualTo("HIGH");
        assertThat(report.fixableCount()).isEqualTo(1);
    }

    @Test
    void readsAnEmptyReportAsCleanRatherThanUnreadable() {
        SecurityReportParser.Parsed parsed = parser.parse("""
                {"SchemaVersion": 2, "ArtifactName": "user-service:1.4.2", "Results": []}
                """);

        assertThat(parsed.usable()).isTrue();
        assertThat(parsed.report().empty()).isTrue();
        assertThat(parsed.report().highestSeverity()).isEqualTo("NONE");
        assertThat(parsed.report().severityCounts()).isEmpty();
    }

    @Test
    void reportsAnEmptyFileAsAProblemRatherThanAsNoFindings() {
        SecurityReportParser.Parsed parsed = parser.parse("   ");

        assertThat(parsed.usable()).isFalse();
        assertThat(parsed.report()).isNull();
        assertThat(parsed.problem()).isEqualTo("The report is empty.");
    }

    @Test
    void reportsAnUnparseableFileAsAProblem() {
        SecurityReportParser.Parsed parsed = parser.parse("Vulnerability  Severity  Package\nxz-utils  CRITICAL");

        assertThat(parsed.usable()).isFalse();
        assertThat(parsed.problem()).contains("not valid JSON");
    }

    @Test
    void refusesJsonThatIsNotAScannerReport() {
        assertThat(parser.parse("[1, 2, 3]").problem())
                .isEqualTo("The report is not a JSON object, so it is not a scanner report.");
        assertThat(parser.parse("{\"SchemaVersion\": 2}").problem())
                .isEqualTo("The report has no 'Results' array, so it is not a Trivy report.");
    }

    @Test
    void leavesAMissingSeverityAsUnknownRatherThanInventingOne() {
        SecurityReportParser.Parsed parsed = parser.parse("""
                {"ArtifactName": "user-service:1.4.2", "Results": [
                  {"Vulnerabilities": [{"VulnerabilityID": "CVE-0000-0000", "PkgName": "libfoo"}]}]}
                """);

        assertThat(parsed.report().findings()).singleElement()
                .extracting(Finding::severity).isEqualTo("UNKNOWN");
        assertThat(parsed.report().highestSeverity()).isEqualTo("UNKNOWN");
        assertThat(parsed.report().severityCounts()).containsEntry("UNKNOWN", 1);
    }

    @Test
    void namesAnUnnamedArtifactRatherThanLeavingTheFieldBlank() {
        SecurityReportParser.Parsed parsed = parser.parse("{\"Results\": []}");

        assertThat(parsed.report().artifact()).isEqualTo("(unnamed artifact)");
    }
}
