package com.devsecops.agentservice.security;

import com.devsecops.agentservice.diagnosis.Diagnosis;
import java.util.List;
import java.util.Map;

/**
 * The answer to {@code POST /analyze-security}.
 *
 * <p>{@code accepted} is the field to read first. False means the agent could not
 * use the report at all - it was missing, unreadable, too large or not JSON -
 * and in that case {@code diagnosis} explains why with a confidence of zero.
 * That shape is deliberate: this endpoint always returns a diagnosis, so a
 * caller never has to distinguish "there is no vulnerability" from "I could not
 * find out", which is the difference that matters most in a security report.
 *
 * <p>{@code severityCounts} covers the whole report; {@code findings} covers the
 * subset that passed the caller's threshold and the configured maximum. That
 * split is what lets a summary say "3 critical, 12 high" while the list shows
 * only the criticals.
 */
public record SecurityAnalysisResponse(
        /** Whether the report was read and parsed. */
        boolean accepted,
        /** The report that was read. */
        String reportPath,
        /** The image or artifact the report describes. */
        String artifact,
        /** How many findings the report contained. */
        int findingCount,
        /** Counts by severity over the whole report. */
        Map<String, Integer> severityCounts,
        /** The worst severity present, or {@code NONE}. */
        String highestSeverity,
        /** How many findings are listed below. */
        int listedFindings,
        /** Whether the list was cut short by the threshold or the maximum. */
        boolean truncated,
        /** The tools mode in force. */
        String toolsMode,
        /** Which engine produced the diagnosis. */
        String provider,
        /** The agent's structured read of the report. */
        Diagnosis diagnosis,
        /** The listed findings. */
        List<Finding> findings,
        /** Everything the agent wants the caller to know: cap, threshold, missing data. */
        List<String> notices) {

    public SecurityAnalysisResponse {
        severityCounts = severityCounts == null ? Map.of() : Map.copyOf(severityCounts);
        findings = findings == null ? List.of() : List.copyOf(findings);
        notices = notices == null ? List.of() : List.copyOf(notices);
    }
}
