package com.devsecops.agentservice.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * The handful of GitHub API objects the agent reads and writes, in one place.
 *
 * <p>Everything is {@code ignoreUnknown}: GitHub adds fields to these objects
 * whenever it likes, and a new one must never turn into a deserialisation
 * failure. Only the fields the agent actually uses are declared, so the records
 * double as documentation of how little of the API this feature depends on.
 */
public final class GithubDtos {

    private GithubDtos() {
        // Holder for the nested types; never instantiated.
    }

    /**
     * A workflow run - what {@code GET /repos/{owner}/{repo}/actions/runs/{id}}
     * returns.
     *
     * <p>{@code pullRequests} is how the agent finds the pull request to comment
     * on: for a run triggered by a PR, GitHub lists it here. A run triggered by a
     * push to a branch has an empty list, which is why the caller can also pass
     * the number in explicitly.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WorkflowRun(
            @JsonProperty("id") long id,
            @JsonProperty("name") String name,
            @JsonProperty("display_title") String displayTitle,
            @JsonProperty("head_branch") String headBranch,
            @JsonProperty("head_sha") String headSha,
            @JsonProperty("status") String status,
            @JsonProperty("conclusion") String conclusion,
            @JsonProperty("html_url") String htmlUrl,
            @JsonProperty("pull_requests") List<PullRequestRef> pullRequests) {

        /** The branch, or a placeholder, so a log line or a comment is never "null". */
        public String branchOrUnknown() {
            return headBranch == null || headBranch.isBlank() ? "unknown" : headBranch;
        }
    }

    /** The PR reference embedded in a run: enough to comment on it. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PullRequestRef(
            @JsonProperty("number") int number,
            @JsonProperty("html_url") String htmlUrl,
            @JsonProperty("head") Head head) {

        /** Only {@code ref} (the branch name) is read, out of a large nested object. */
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Head(@JsonProperty("ref") String ref) {
        }
    }

    /** One job within a run. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WorkflowJob(
            @JsonProperty("id") long id,
            @JsonProperty("name") String name,
            @JsonProperty("status") String status,
            @JsonProperty("conclusion") String conclusion,
            @JsonProperty("html_url") String htmlUrl) {

        /**
         * Whether this job is one whose log is worth reading.
         *
         * <p>Defined by exclusion rather than by listing "failure": a job can end
         * as {@code timed_out} or {@code action_required} and still be the reason
         * the run is red, while {@code skipped} and {@code neutral} are jobs that
         * did not run and whose (empty) log would tell the agent nothing.
         */
        public boolean isFailed() {
            if (conclusion == null || conclusion.isBlank()) {
                return false;
            }
            return !"success".equals(conclusion)
                    && !"skipped".equals(conclusion)
                    && !"neutral".equals(conclusion);
        }
    }

    /** The jobs listing's envelope: {@code {"total_count": n, "jobs": [...]}}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WorkflowJobsResponse(
            @JsonProperty("total_count") int totalCount,
            @JsonProperty("jobs") List<WorkflowJob> jobs) {
    }
}
