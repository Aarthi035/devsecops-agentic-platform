package com.devsecops.agentservice.security;

import java.util.List;
import java.util.Map;

/**
 * A scanner report after parsing, in the shape the agent reasons about.
 *
 * <p>Trivy's JSON is a report of <em>targets</em> - an OS package list, a jar, a
 * lockfile - each with its own findings. Almost nothing useful is phrased in
 * targets: "the worst thing in this image is a CRITICAL in xz-utils" is the
 * sentence a summary needs, and it needs the whole report flattened to say it.
 * So {@link SecurityReportParser} does that flattening once, here, and the
 * response is assembled from this record rather than from a JSON tree.
 *
 * <p>{@code severityCounts} covers every finding and {@code findings} is the
 * unfiltered list; the filtering by threshold and maximum belongs to the
 * service, because it is a decision about the response rather than a fact about
 * the report. Keeping them apart is what lets a response say "3 critical, 12
 * high" while listing only the criticals.
 *
 * <p>{@code highestSeverity} is {@code NONE} rather than {@code UNKNOWN} for an
 * empty report: a report with nothing in it is a report with no findings, not a
 * finding nobody triaged, and conflating the two would make a clean scan look
 * like an untriaged one.
 */
public record SecurityReport(
        /** The image, filesystem or repository the report describes. */
        String artifact,
        /** Every finding in the report, in report order. */
        List<Finding> findings,
        /** Counts by severity, in descending severity order, omitting the empty ones. */
        Map<String, Integer> severityCounts,
        /** The worst severity present, or {@code NONE} when the report is empty. */
        String highestSeverity) {

    public SecurityReport {
        findings = findings == null ? List.of() : List.copyOf(findings);
        severityCounts = severityCounts == null ? Map.of() : Map.copyOf(severityCounts);
    }

    /** Whether the report contained no findings at all. */
    public boolean empty() {
        return findings.isEmpty();
    }

    /**
     * The findings at or above {@code threshold}, worst first.
     *
     * <p>Sorted rather than left in report order because a reader scrolls the
     * top of a list; the CRITICAL belongs there whether Trivy happened to emit
     * the OS package list first or last. The sort is stable, so findings of equal
     * severity keep the report's own order.
     */
    public List<Finding> atLeast(Severity threshold) {
        Severity floor = threshold == null ? Severity.UNKNOWN : threshold;
        return findings.stream()
                .filter(finding -> Severity.parse(finding.severity()).atLeast(floor))
                .sorted((left, right) -> Integer.compare(
                        Severity.parse(right.severity()).rank(),
                        Severity.parse(left.severity()).rank()))
                .toList();
    }

    /** How many findings have a published fix. */
    public long fixableCount() {
        return findings.stream().filter(Finding::fixAvailable).count();
    }
}
