package com.devsecops.agentservice.analysis;

import com.devsecops.agentservice.audit.AuditLog;
import com.devsecops.agentservice.config.AgentProperties;
import com.devsecops.agentservice.diagnosis.Diagnosis;
import com.devsecops.agentservice.diagnosis.DiagnosisEngine;
import com.devsecops.agentservice.diagnosis.DiagnosisRequest;
import com.devsecops.agentservice.error.AgentUpstreamException;
import com.devsecops.agentservice.github.GithubClient;
import com.devsecops.agentservice.github.GithubDtos;
import com.devsecops.agentservice.logs.LogSanitizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The Phase 8 pipeline, in one readable place:
 * <em>read the run → find its failed jobs → fetch and sanitize their logs →
 * diagnose → comment on the pull request</em>.
 *
 * <p>This is the v1 of the agent: it is given a target and it works through that
 * fixed sequence. Phase 9 replaces the sequence with a loop that chooses its own
 * tools; keeping the v1 version linear is what makes it possible to say exactly
 * what it will do, which is a property worth having in something that comments
 * on pull requests.
 *
 * <p>Three decisions are worth knowing before reading the code:
 *
 * <ul>
 *   <li><b>Cost is bounded up front.</b> At most
 *       {@code agent.limits.max-failed-jobs} logs are downloaded, and each is
 *       truncated to {@code agent.limits.max-log-chars} before any engine sees
 *       it. The spec's "max steps" and "max token" guardrails are applied here,
 *       at the point where the work would otherwise be unbounded.</li>
 *   <li><b>A failed comment does not fail the request.</b> The diagnosis is the
 *       valuable output and the comment is a delivery mechanism; throwing it
 *       away because GitHub was unavailable would be the wrong trade. The
 *       response records why no comment appeared.</li>
 *   <li><b>Every external call and every conclusion is audited.</b> One JSON
 *       line each on the {@code AUDIT} logger, with no secret values - see
 *       {@link AuditLog}, which enforces that last part itself.</li>
 * </ul>
 */
@Service
public class CiAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(CiAnalysisService.class);

    private final GithubClient githubClient;
    private final DiagnosisEngine diagnosisEngine;
    private final LogSanitizer logSanitizer;
    private final PullRequestCommenter commenter;
    private final AgentProperties properties;
    private final AuditLog auditLog;

    public CiAnalysisService(GithubClient githubClient,
                             DiagnosisEngine diagnosisEngine,
                             LogSanitizer logSanitizer,
                             PullRequestCommenter commenter,
                             AgentProperties properties,
                             AuditLog auditLog) {
        this.githubClient = githubClient;
        this.diagnosisEngine = diagnosisEngine;
        this.logSanitizer = logSanitizer;
        this.commenter = commenter;
        this.properties = properties;
        this.auditLog = auditLog;
    }

    /** Runs the whole pipeline for one request. */
    public AnalyzeCiResponse analyze(AnalyzeCiRequest request) {
        String repository = request.repository();
        long runId = request.runId();

        audit("analyze-ci.request", Map.of(
                "repository", repository,
                "runId", runId,
                "pullNumber", String.valueOf(request.pullNumber())));

        GithubDtos.WorkflowRun run = githubClient.fetchRun(repository, runId);
        int maxFailedJobs = properties.limits().maxFailedJobs();
        List<GithubDtos.WorkflowJob> failedJobs = githubClient.fetchFailedJobs(repository, runId, maxFailedJobs);

        log.info("Run {} in {} concluded {} with {} failed job(s); analyzing at most {}.",
                runId, repository, run.conclusion(), failedJobs.size(), maxFailedJobs);

        List<AnalyzeCiResponse.JobOutcome> outcomes = new ArrayList<>();
        for (GithubDtos.WorkflowJob job : failedJobs) {
            outcomes.add(analyzeJob(repository, run, job));
        }

        Diagnosis primary = outcomes.isEmpty()
                ? noFailedJobDiagnosis(run)
                : outcomes.get(0).diagnosis();

        // Built once before the comment so the commenter can render the real
        // job outcomes - including whether any log was truncated - and once after
        // to carry the outcome of posting. The record is immutable, so this is
        // how a value that depends on its own rendering gets assembled.
        AnalyzeCiResponse beforeComment = new AnalyzeCiResponse(
                repository,
                runId,
                run.name(),
                run.htmlUrl(),
                run.branchOrUnknown(),
                run.headSha(),
                run.conclusion(),
                List.copyOf(outcomes),
                primary,
                failedJobs.size(),
                outcomes.size(),
                diagnosisEngine.provider(),
                null);

        AnalyzeCiResponse.CommentOutcome commentOutcome =
                deliverComment(repository, run, request, beforeComment);

        AnalyzeCiResponse response = new AnalyzeCiResponse(
                beforeComment.repository(),
                beforeComment.runId(),
                beforeComment.workflowName(),
                beforeComment.runUrl(),
                beforeComment.headBranch(),
                beforeComment.headSha(),
                beforeComment.conclusion(),
                beforeComment.jobs(),
                beforeComment.primaryDiagnosis(),
                beforeComment.failedJobCount(),
                beforeComment.analyzedJobCount(),
                beforeComment.provider(),
                commentOutcome);

        audit(AuditLog.DECISION, decisionFields(request, run, response, outcomes.size()));
        return response;
    }

    /**
     * Fetches one job's log, sanitizes it, and diagnoses it.
     *
     * <p>The order matters and is the whole point of the method: nothing reaches
     * the engine before {@link LogSanitizer} has both redacted it and cut it to
     * size, so no engine can be given a secret or an unbounded prompt even by
     * mistake.
     */
    private AnalyzeCiResponse.JobOutcome analyzeJob(String repository,
                                                   GithubDtos.WorkflowRun run,
                                                   GithubDtos.WorkflowJob job) {
        String rawLog = githubClient.fetchJobLog(repository, job.id());
        int maxLogChars = properties.limits().maxLogChars();
        boolean truncated = rawLog.length() > maxLogChars;

        String sanitized = logSanitizer.sanitize(rawLog, properties.github().token(), maxLogChars);

        audit(AuditLog.TOOL_CALL, Map.of(
                "tool", "fetchCiLogs",
                "repository", repository,
                "runId", run.id(),
                "jobId", job.id(),
                "jobName", String.valueOf(job.name()),
                "rawChars", rawLog.length(),
                "analyzedChars", sanitized.length(),
                "truncated", truncated));

        Diagnosis diagnosis = diagnosisEngine.diagnose(new DiagnosisRequest(
                repository,
                run.name(),
                job.name(),
                job.conclusion(),
                sanitized));

        return new AnalyzeCiResponse.JobOutcome(
                job.id(),
                job.name(),
                job.htmlUrl(),
                job.conclusion(),
                sanitized.length(),
                truncated,
                diagnosis);
    }

    /**
     * Works out the pull request to comment on, posts the comment, and returns an
     * outcome that always says what happened.
     *
     * <p>A posting failure is caught rather than propagated: the caller still
     * gets the diagnosis, and the {@code reason} says the comment did not land -
     * which is the honest answer, since a 502 here would suggest the analysis
     * itself failed when it did not.
     */
    private AnalyzeCiResponse.CommentOutcome deliverComment(String repository,
                                                            GithubDtos.WorkflowRun run,
                                                            AnalyzeCiRequest request,
                                                            AnalyzeCiResponse rendered) {
        Integer pullNumber = request.pullNumber() != null
                ? request.pullNumber()
                : firstPullRequestNumber(run);

        if (pullNumber == null) {
            return new AnalyzeCiResponse.CommentOutcome(false, null, null,
                    "The run is not associated with a pull request; pass pullNumber to comment anyway.");
        }

        boolean wanted = request.comment() != null
                ? request.comment()
                : properties.github().commentEnabled();
        if (!wanted) {
            return new AnalyzeCiResponse.CommentOutcome(false, pullNumber, null,
                    "Commenting is disabled (agent.github.comment-enabled=false, or comment=false on the request).");
        }
        if (!githubClient.hasToken()) {
            return new AnalyzeCiResponse.CommentOutcome(false, pullNumber, null,
                    "No GitHub token is configured; set GITHUB_TOKEN to enable comments.");
        }

        String body = commenter.buildBody(rendered);

        try {
            String url = commenter.post(repository, pullNumber, body);
            audit(AuditLog.TOOL_CALL, Map.of(
                    "tool", "postGithubComment",
                    "repository", repository,
                    "pullNumber", pullNumber,
                    "outcome", "posted"));
            return new AnalyzeCiResponse.CommentOutcome(true, pullNumber, url, "posted");
        } catch (AgentUpstreamException e) {
            log.warn("Could not comment on {}#{}: {}", repository, pullNumber, e.getMessage());
            audit(AuditLog.TOOL_CALL, Map.of(
                    "tool", "postGithubComment",
                    "repository", repository,
                    "pullNumber", pullNumber,
                    "outcome", "failed"));
            return new AnalyzeCiResponse.CommentOutcome(false, pullNumber, null,
                    "GitHub rejected the comment: " + e.getMessage());
        }
    }

    /**
     * The diagnosis for a run that is red but has no failed job to read - a
     * cancelled run, or one that failed before its jobs were created.
     *
     * <p>Returned as a normal diagnosis with a very low confidence rather than an
     * error, so the caller gets a well-formed response and an explanation instead
     * of a bare 404 that says nothing about what the agent found.
     */
    private Diagnosis noFailedJobDiagnosis(GithubDtos.WorkflowRun run) {
        return new Diagnosis(
                "The run concluded '" + run.conclusion() + "', but GitHub reported no failed job, so there is "
                        + "no failed step for the agent to read.",
                List.of(),
                "Open the run's timeline: a cancelled run, or one that failed before its jobs started, "
                        + "leaves nothing to diagnose from the job logs.",
                "none",
                0.1);
    }

    private static Integer firstPullRequestNumber(GithubDtos.WorkflowRun run) {
        if (run.pullRequests() == null || run.pullRequests().isEmpty()) {
            return null;
        }
        int number = run.pullRequests().get(0).number();
        return number > 0 ? number : null;
    }

    /** The fields of the closing audit entry - ids and conclusions, never log text. */
    private Map<String, Object> decisionFields(AnalyzeCiRequest request,
                                               GithubDtos.WorkflowRun run,
                                               AnalyzeCiResponse response,
                                               int analyzedJobs) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("repository", request.repository());
        fields.put("runId", run.id());
        fields.put("runConclusion", String.valueOf(run.conclusion()));
        fields.put("failedJobCount", response.failedJobCount());
        fields.put("analyzedJobCount", analyzedJobs);
        fields.put("provider", diagnosisEngine.provider());
        fields.put("rootCause", response.primaryDiagnosis().rootCause());
        fields.put("recommendedAction", response.primaryDiagnosis().recommendedAction());
        fields.put("confidence", response.primaryDiagnosis().confidence());
        fields.put("commentPosted", response.comment().posted());
        return fields;
    }

    /** Convenience so every audit call in this class has the same shape. */
    private void audit(String event, Map<String, ?> fields) {
        auditLog.record(event, fields);
    }
}
