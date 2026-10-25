package com.devsecops.agentservice.security;

/**
 * The body of {@code POST /analyze-security}.
 *
 * <p>Both fields are optional, and the defaults are the ones a trigger wants:
 * with no {@code reportPath} the agent reads the report the deployment was
 * configured with, and with no {@code severityThreshold} it reports everything.
 *
 * <p>{@code reportPath} is not a free pass to the filesystem. It is resolved
 * inside {@code agent.security.report-directory} by the tool, which refuses
 * anything that escapes it - including through a symlink. A request cannot talk
 * the agent into reading {@code /etc/shadow} by asking nicely.
 */
public record SecurityAnalysisRequest(
        /** The scanner report to read; blank means {@code agent.security.report-path}. */
        String reportPath,

        /** {@code CRITICAL}, {@code HIGH}, {@code MEDIUM}, {@code LOW}: only list findings at or above it. */
        String severityThreshold) {
}
