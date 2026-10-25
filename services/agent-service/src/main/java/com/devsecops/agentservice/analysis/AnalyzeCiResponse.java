package com.devsecops.agentservice.analysis;

import com.devsecops.agentservice.diagnosis.Diagnosis;
import java.util.List;

/**
 * The body of the {@code POST /analyze-ci} response.
 *
 * <p>Shaped as "what was asked, what was found, what the agent concluded, and
 * what it wrote back". The run metadata is included even though the caller
 * supplied the run id, because the response is also the audit record a human
 * reads afterwards - and it has to stand alone in a GitHub Actions log.
 *
 * <p>{@code primaryDiagnosis} is the diagnosis the PR comment is built from: the
 * first failed job's, since GitHub lists jobs in the order they ran and the
 * first failure is the one that caused the rest. Every analyzed job's own
 * diagnosis is still in {@code jobs}, so nothing is discarded.
 */
public record AnalyzeCiResponse(
        String repository,
        long runId,
        String workflowName,
        String runUrl,
        String headBranch,
        String headSha,
        String conclusion,
        List<JobOutcome> jobs,
        Diagnosis primaryDiagnosis,
        int failedJobCount,
        int analyzedJobCount,
        String provider,
        CommentOutcome comment) {

    /**
     * Whether any analyzed job's log was cut before the engine saw it.
     *
     * <p>Derived rather than stored, so it cannot disagree with the per-job flags
     * it summarises. The comment uses it to say so explicitly: a diagnosis drawn
     * from a truncated log is a different claim from one drawn from the whole
     * log, and the reader should be told which they have.
     */
    public boolean truncatedAnyLog() {
        return jobs != null && jobs.stream().anyMatch(JobOutcome::truncated);
    }

    /**
     * One failed job: its identity, what happened to its log, and the diagnosis
     * that came out of it.
     *
     * @param logChars  how much of the (sanitized) log was actually analyzed, so
     *                  a low-confidence answer can be read next to how little
     *                  evidence there was
     * @param truncated whether the log was cut before analysis; a caller that
     *                  sees {@code true} knows the diagnosis is based on part of
     *                  the log, not all of it
     */
    public record JobOutcome(
            long jobId,
            String name,
            String url,
            String conclusion,
            int logChars,
            boolean truncated,
            Diagnosis diagnosis) {
    }

    /**
     * What happened to the pull request comment.
     *
     * <p>{@code reason} is always populated, including on success ("posted").
     * That is the point: the caller - and the audit log - should never have to
     * guess why no comment appeared. A silent skip is the failure mode this
     * field exists to prevent.
     */
    public record CommentOutcome(
            boolean posted,
            Integer pullNumber,
            String url,
            String reason) {
    }
}
