package com.devsecops.agentservice.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.devsecops.agentservice.diagnosis.Diagnosis;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The comment's wording, asserted without a network call.
 *
 * <p>This is the one artefact of the agent that a human actually reads, so the
 * things worth pinning down are: that the reviewer is told which engine answered
 * and how confident it is, and that a log line containing a backtick cannot
 * escape its code span and reformat the rest of the comment.
 */
class PullRequestCommenterTest {

    private final PullRequestCommenter commenter = new PullRequestCommenter(null);

    @Test
    void rendersTheDiagnosisWithItsProvenance() {
        String body = commenter.buildBody(response(false));

        assertThat(body).contains("## CI diagnosis: CI");
        assertThat(body).contains("### Root cause");
        assertThat(body).contains("The code did not compile.");
        assertThat(body).contains("**Confidence:** 85%");
        assertThat(body).contains("App.java:42: cannot find symbol");
        assertThat(body).contains("### Suggested fix");
        assertThat(body).contains("**Recommended action:** `none`");
        assertThat(body).contains("`heuristic` engine");
        assertThat(body).contains("verify the diagnosis before acting on it");
        // Nothing was cut, so the disclaimer must not appear.
        assertThat(body).doesNotContain("truncated");
    }

    @Test
    void saysSoWhenALogWasTruncated() {
        assertThat(commenter.buildBody(response(true)))
                .contains("at least one log was truncated before analysis");
    }

    @Test
    void cannotBeBrokenOutOfItsCodeSpanByABacktick() {
        Diagnosis hostile = new Diagnosis(
                "root", List.of("evil` **bold** `line"), "fix", "none", 0.5);
        AnalyzeCiResponse response = new AnalyzeCiResponse(
                "example/platform", 1L, "CI", "https://example/run/1", "main", "abc1234",
                "failure",
                List.of(new AnalyzeCiResponse.JobOutcome(1L, "build", null, "failure", 10, false, hostile)),
                hostile, 1, 1, "heuristic", new AnalyzeCiResponse.CommentOutcome(false, null, null, "n/a"));

        String body = commenter.buildBody(response);

        assertThat(body).contains("- `evil' **bold** 'line`");
        assertThat(body).doesNotContain("`evil`");
    }

    private static AnalyzeCiResponse response(boolean truncated) {
        Diagnosis diagnosis = new Diagnosis(
                "The code did not compile.",
                List.of("Compilation failure", "App.java:42: cannot find symbol"),
                "Fix the reported compilation error.",
                "none",
                0.85);
        return new AnalyzeCiResponse(
                "example/platform",
                1234L,
                "CI",
                "https://github.com/example/platform/actions/runs/1234",
                "feature/x",
                "9c65d35abcdef",
                "failure",
                List.of(new AnalyzeCiResponse.JobOutcome(9L, "build", null, "failure", 4_000, truncated, diagnosis)),
                diagnosis,
                1,
                1,
                "heuristic",
                new AnalyzeCiResponse.CommentOutcome(false, null, null, "no pull request"));
    }
}
