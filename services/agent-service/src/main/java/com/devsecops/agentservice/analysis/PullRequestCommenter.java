package com.devsecops.agentservice.analysis;

import com.devsecops.agentservice.diagnosis.Diagnosis;
import com.devsecops.agentservice.github.GithubClient;
import org.springframework.stereotype.Component;

/**
 * Turns a diagnosis into the Markdown comment that goes on the pull request, and
 * posts it.
 *
 * <p>Kept apart from the orchestration in {@link CiAnalysisService} because the
 * audience is different: the service decides <em>what</em> the answer is, this
 * class decides how to say it to a developer who is looking at a red check and
 * wants to know two things - what broke, and what to do about it. Building the
 * text here means the wording can be reviewed and tested without a network call.
 *
 * <p>The comment always says which engine produced it and how confident it is.
 * A generated diagnosis on a pull request is easy to mistake for a considered
 * one, and a reviewer is entitled to know the difference before acting on it.
 */
@Component
public class PullRequestCommenter {

    /** The same cap the engines use; the comment should not be longer than the answer. */
    private static final int MAX_EVIDENCE_LINES = 5;

    private final GithubClient githubClient;

    public PullRequestCommenter(GithubClient githubClient) {
        this.githubClient = githubClient;
    }

    /**
     * Builds the Markdown body.
     *
     * <p>Pure: no network, so it is directly testable, which is what the Phase 8
     * tests do with a literal response object.
     */
    public String buildBody(AnalyzeCiResponse response) {
        Diagnosis diagnosis = response.primaryDiagnosis();

        StringBuilder body = new StringBuilder();
        body.append("## CI diagnosis: ").append(orUnknown(response.workflowName())).append("\n\n");
        body.append("**Run:** [").append(shortSha(response.headSha())).append("](")
                .append(orUnknown(response.runUrl())).append(") on `")
                .append(response.headBranch() == null ? "unknown" : response.headBranch()).append("`")
                .append(" - conclusion `").append(orUnknown(response.conclusion())).append("`\n\n");

        body.append("### Root cause\n\n").append(orUnknown(diagnosis.rootCause())).append("\n\n");
        body.append("**Confidence:** ").append(Math.round(diagnosis.confidence() * 100)).append("%\n\n");

        if (!diagnosis.evidence().isEmpty()) {
            body.append("### Evidence\n\n");
            diagnosis.evidence().stream().limit(MAX_EVIDENCE_LINES).forEach(line ->
                    body.append("- `").append(inlineCodeSafe(line)).append("`\n"));
            body.append('\n');
        }

        body.append("### Suggested fix\n\n").append(orUnknown(diagnosis.suggestedFix())).append("\n\n");
        body.append("**Recommended action:** `").append(diagnosis.recommendedAction()).append("`\n\n");

        body.append("<sub>Diagnosed by the DevSecOps agent using the `")
                .append(response.provider())
                .append("` engine");
        if (response.truncatedAnyLog()) {
            body.append("; at least one log was truncated before analysis");
        }
        body.append(". This comment is generated - verify the diagnosis before acting on it.</sub>\n");

        return body.toString();
    }

    /**
     * Posts the body and returns the comment's html_url, or null if GitHub did
     * not return one.
     *
     * @throws com.devsecops.agentservice.error.AgentUpstreamException if the post
     *         fails - the caller decides whether that should fail the request
     */
    public String post(String repository, int pullNumber, String body) {
        return githubClient.postPullRequestComment(repository, pullNumber, body);
    }

    /**
     * Makes a log line safe inside a Markdown inline code span.
     *
     * <p>A backtick in the evidence would close the span early and leave the rest
     * of the line rendered as Markdown - which is how a log line ends up
     * formatted as a heading in a pull request comment. Replacing backticks with
     * an apostrophe is lossy and deliberate: the alternative is escaping, and a
     * comment is not the place for an escape-sequence bug hunt.
     */
    private static String inlineCodeSafe(String text) {
        if (text == null) {
            return "";
        }
        return text.replace('`', '\'').replace("\r", " ").replace("\n", " ");
    }

    private static String shortSha(String sha) {
        if (sha == null || sha.isBlank()) {
            return "the run";
        }
        return sha.length() > 7 ? sha.substring(0, 7) : sha;
    }

    private static String orUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
