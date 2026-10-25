package com.devsecops.agentservice.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The two decisions the report makes about its own findings: which ones pass a
 * threshold, and whether a fix exists for them.
 *
 * <p>Ordering is asserted rather than membership, because a reader scrolls the
 * top of a list - the CRITICAL has to be there whether the scanner emitted the
 * OS package list first or the jar.
 */
class SecurityReportTest {

    private static final Finding CRITICAL = finding("CVE-1", "CRITICAL", "5.2.5", "5.2.5.1");
    private static final Finding HIGH = finding("CVE-2", "HIGH", "6.1.14", "6.1.17");
    private static final Finding MEDIUM_NO_FIX = finding("CVE-3", "MEDIUM", "6.1.14", "");
    private static final Finding UNKNOWN = finding("CVE-4", "UNKNOWN", "1.0", "");

    private static Finding finding(String id, String severity, String installed, String fixed) {
        return new Finding(id, severity, "pkg-" + id, installed, fixed, "title-" + id, "target");
    }

    private static SecurityReport report(List<Finding> findings) {
        return new SecurityReport("artifact", findings, Map.of(), "CRITICAL");
    }

    @Test
    void listsTheWorstFindingsFirst() {
        SecurityReport report = report(List.of(MEDIUM_NO_FIX, HIGH, CRITICAL, UNKNOWN));

        assertThat(report.atLeast(Severity.UNKNOWN))
                .extracting(Finding::id)
                .containsExactly("CVE-1", "CVE-2", "CVE-3", "CVE-4");
    }

    @Test
    void dropsEverythingBelowTheThreshold() {
        SecurityReport report = report(List.of(MEDIUM_NO_FIX, HIGH, CRITICAL, UNKNOWN));

        assertThat(report.atLeast(Severity.HIGH))
                .extracting(Finding::id)
                .containsExactly("CVE-1", "CVE-2");
    }

    @Test
    void treatsAMissingThresholdAsNoThreshold() {
        SecurityReport report = report(List.of(UNKNOWN));

        assertThat(report.atLeast(null)).hasSize(1);
    }

    @Test
    void countsOnlyTheFindingsThatNameAFix() {
        SecurityReport report = report(List.of(MEDIUM_NO_FIX, HIGH, CRITICAL, UNKNOWN));

        assertThat(report.fixableCount()).isEqualTo(2);
    }

    @Test
    void describesAFindingWithAndWithoutAPublishedFix() {
        assertThat(CRITICAL.describe())
                .isEqualTo("CVE-1 [CRITICAL] pkg-CVE-1 5.2.5 -> fixed in 5.2.5.1: title-CVE-1");
        assertThat(MEDIUM_NO_FIX.describe())
                .isEqualTo("CVE-3 [MEDIUM] pkg-CVE-3 6.1.14 (no fix published yet): title-CVE-3");
    }

    @Test
    void anEmptyReportIsEmptyRatherThanAFindingNobodyTriaged() {
        SecurityReport report = new SecurityReport("artifact", List.of(), Map.of(), "NONE");

        assertThat(report.empty()).isTrue();
        assertThat(report.atLeast(Severity.CRITICAL)).isEmpty();
        assertThat(report.highestSeverity()).isEqualTo("NONE");
    }
}
