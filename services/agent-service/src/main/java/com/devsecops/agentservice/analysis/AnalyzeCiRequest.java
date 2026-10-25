package com.devsecops.agentservice.analysis;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * The body of {@code POST /analyze-ci}: which run to look at, and whether to
 * write anything back.
 *
 * <p>{@code repository} is validated with a pattern rather than only as
 * {@code NotBlank}, because it is interpolated straight into GitHub API paths.
 * Constraining it to {@code owner/name} shapes stops a malformed value from
 * producing a request to an unintended endpoint.
 *
 * <p>{@code pullNumber} and {@code comment} are both optional. Leaving
 * {@code pullNumber} out asks the agent to find the pull request from the run
 * itself, which is what a trigger usually wants; passing it is the escape hatch
 * for a run that has no pull request association. Leaving {@code comment} out
 * defers to {@code agent.github.comment-enabled}, so the default behaviour is a
 * deployment decision rather than a per-call one.
 */
public record AnalyzeCiRequest(

        @NotBlank(message = "repository is required, in owner/name form")
        @Pattern(regexp = "[\\w.-]+/[\\w.-]+",
                message = "repository must look like owner/name")
        String repository,

        @NotNull(message = "runId is required")
        @Positive(message = "runId must be a positive number")
        Long runId,

        @Positive(message = "pullNumber must be a positive number")
        Integer pullNumber,

        Boolean comment) {
}
