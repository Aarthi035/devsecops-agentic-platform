package com.devsecops.agentservice.diagnosis;

/**
 * Everything an engine is given about a failed job.
 *
 * <p>Deliberately small and flat. An engine is a pure function of this and
 * nothing else: it cannot fetch, cannot look at the clock and cannot read the
 * environment, which is what makes both engines unit-testable with a literal and
 * what keeps the GitHub and model code out of the same class.
 *
 * <p>{@code logs} is already sanitized and truncated by the time it arrives (see
 * {@code LogSanitizer}), so an engine must not have to remember either rule.
 */
public record DiagnosisRequest(
        String repository,
        String workflowName,
        String jobName,
        String conclusion,
        String logs) {
}
