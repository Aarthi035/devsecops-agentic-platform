package com.devsecops.agentservice.incident;

/**
 * One tool call as it appears in a response: what was asked, whether it
 * answered, and the one-line reading.
 *
 * <p>The body is deliberately not included. A response body is read by a
 * pipeline or a human, and pasting a hundred kilobytes of logs into it would
 * bury the diagnosis under the evidence for it. The summary is the part worth
 * showing; the full text goes into the evidence bundle the diagnosis was built
 * from, and the audit trail records that the call happened.
 */
public record Reading(
        String tool,
        boolean ok,
        String summary) {

    public static Reading of(String tool, boolean ok, String summary) {
        return new Reading(tool, ok, summary == null ? "" : summary);
    }
}
