package com.devsecops.agentservice.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.devsecops.agentservice.TestProperties;
import com.devsecops.agentservice.audit.AuditLog;
import com.devsecops.agentservice.config.AgentProperties;
import com.devsecops.agentservice.diagnosis.Diagnosis;
import com.devsecops.agentservice.diagnosis.DiagnosisEngine;
import com.devsecops.agentservice.diagnosis.DiagnosisRequest;
import com.devsecops.agentservice.diagnosis.EvidenceBundle;
import com.devsecops.agentservice.error.AgentUpstreamException;
import com.devsecops.agentservice.github.GithubClient;
import com.devsecops.agentservice.github.GithubDtos;
import com.devsecops.agentservice.logs.LogSanitizer;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The Phase 8 pipeline, with the two things it talks to replaced.
 *
 * <p>GitHub is a mock and the engine is a stub, so what is left under test is
 * the orchestration itself - which is where the interesting failures live: a run
 * with no failed job, a comment that must not be posted, a comment that fails
 * without taking the diagnosis down with it, and the ordering rule that nothing
 * reaches the engine before the sanitiser has seen it.
 */
class CiAnalysisServiceTest {

    private static final String REPOSITORY = "example/platform";
    private static final long RUN_ID = 1234L;

    private GithubClient githubClient;
    private StubEngine engine;
    private AgentProperties properties;
    private CiAnalysisService service;

    @BeforeEach
    void setUp() {
        githubClient = mock(GithubClient.class);
        // A configured token by default: the "no token" case is its own test, and
        // every other test would otherwise pass for the wrong reason.
        when(githubClient.hasToken()).thenReturn(true);
        engine = new StubEngine();
        properties = properties(true, "github-token");
        service = new CiAnalysisService(
                githubClient,
                engine,
                new LogSanitizer(),
                new PullRequestCommenter(githubClient),
                properties,
                new AuditLog(new ObjectMapper()));
    }

    @Test
    void diagnosesTheFirstFailedJobAndCommentsOnItsPullRequest() {
        givenRunWith(runWithPullRequest(7));
        givenFailedJobs(job(11, "build"), job(12, "test"));
        when(githubClient.fetchJobLog(anyString(), anyLong())).thenReturn("COMPILATION FAILURE");
        when(githubClient.postPullRequestComment(eq(REPOSITORY), eq(7), anyString()))
                .thenReturn("https://github.com/example/platform/pull/7#issuecomment-1");

        AnalyzeCiResponse response = service.analyze(request(null, null));

        assertThat(response.repository()).isEqualTo(REPOSITORY);
        assertThat(response.conclusion()).isEqualTo("failure");
        assertThat(response.primaryDiagnosis().rootCause()).isEqualTo("The code did not compile.");
        assertThat(response.provider()).isEqualTo("stub");
        assertThat(response.failedJobCount()).isEqualTo(2);
        assertThat(response.analyzedJobCount()).isEqualTo(2);
        assertThat(response.comment().posted()).isTrue();
        assertThat(response.comment().pullNumber()).isEqualTo(7);
        assertThat(response.comment().url()).contains("issuecomment-1");
    }

    @Test
    void sanitizesAndTruncatesTheLogBeforeTheEngineSeesIt() {
        givenRunWith(runWithPullRequest(7));
        givenFailedJobs(job(11, "build"));
        // 2 KB of log with a token in it, against a 400 character budget.
        when(githubClient.fetchJobLog(anyString(), anyLong()))
                .thenReturn("token=github-token\n" + "y".repeat(2_000));
        service = new CiAnalysisService(githubClient, engine, new LogSanitizer(),
                new PullRequestCommenter(githubClient), properties(true, "github-token", 400, 3),
                new AuditLog(new ObjectMapper()));

        AnalyzeCiResponse response = service.analyze(request(null, false));

        DiagnosisRequest seen = engine.lastRequest.get();
        assertThat(seen.logs()).doesNotContain("github-token");
        assertThat(seen.logs()).contains("[REDACTED]");
        assertThat(seen.logs().length()).isLessThanOrEqualTo(400);
        assertThat(response.truncatedAnyLog()).isTrue();
    }

    @Test
    void honoursTheFailedJobCap() {
        givenRunWith(runWithPullRequest(7));
        givenFailedJobs(job(11, "build"));
        when(githubClient.fetchJobLog(anyString(), anyLong())).thenReturn("COMPILATION FAILURE");

        service.analyze(request(null, false));

        // The cap is the spec's "max steps" guardrail, applied at the only place
        // the work would otherwise grow without bound.
        org.mockito.Mockito.verify(githubClient).fetchFailedJobs(REPOSITORY, RUN_ID, 3);
    }

    @Test
    void doesNotCommentWhenACallAsksItNotTo() {
        givenRunWith(runWithPullRequest(7));
        givenFailedJobs(job(11, "build"));
        when(githubClient.fetchJobLog(anyString(), anyLong())).thenReturn("COMPILATION FAILURE");

        AnalyzeCiResponse response = service.analyze(request(null, false));

        assertThat(response.comment().posted()).isFalse();
        assertThat(response.comment().reason()).contains("Commenting is disabled");
        org.mockito.Mockito.verify(githubClient, org.mockito.Mockito.never())
                .postPullRequestComment(anyString(), anyInt(), anyString());
    }

    @Test
    void saysWhyThereIsNoCommentRatherThanSkippingSilently() {
        givenRunWith(runWithoutPullRequest());
        givenFailedJobs(job(11, "build"));
        when(githubClient.fetchJobLog(anyString(), anyLong())).thenReturn("COMPILATION FAILURE");

        AnalyzeCiResponse response = service.analyze(request(null, null));

        assertThat(response.comment().posted()).isFalse();
        assertThat(response.comment().reason()).contains("not associated with a pull request");
    }

    @Test
    void doesNotTryToCommentWithoutAToken() {
        service = new CiAnalysisService(githubClient, engine, new LogSanitizer(),
                new PullRequestCommenter(githubClient), properties(true, ""), new AuditLog(new ObjectMapper()));
        givenRunWith(runWithPullRequest(7));
        givenFailedJobs(job(11, "build"));
        when(githubClient.fetchJobLog(anyString(), anyLong())).thenReturn("COMPILATION FAILURE");
        when(githubClient.hasToken()).thenReturn(false);

        AnalyzeCiResponse response = service.analyze(request(null, null));

        assertThat(response.comment().posted()).isFalse();
        assertThat(response.comment().reason()).contains("No GitHub token is configured");
    }

    @Test
    void aFailedCommentStillReturnsTheDiagnosis() {
        givenRunWith(runWithPullRequest(7));
        givenFailedJobs(job(11, "build"));
        when(githubClient.fetchJobLog(anyString(), anyLong())).thenReturn("COMPILATION FAILURE");
        when(githubClient.postPullRequestComment(anyString(), anyInt(), anyString()))
                .thenThrow(new AgentUpstreamException("GitHub returned HTTP 403"));

        AnalyzeCiResponse response = service.analyze(request(null, null));

        // The diagnosis is the valuable output; losing it because GitHub was
        // unavailable would be the wrong trade.
        assertThat(response.primaryDiagnosis().rootCause()).isEqualTo("The code did not compile.");
        assertThat(response.comment().posted()).isFalse();
        assertThat(response.comment().reason()).contains("403");
    }

    @Test
    void explainsARunThatIsRedWithoutAFailedJob() {
        givenRunWith(runWithPullRequest(7));
        givenFailedJobs();

        AnalyzeCiResponse response = service.analyze(request(null, false));

        assertThat(response.analyzedJobCount()).isZero();
        assertThat(response.primaryDiagnosis().confidence()).isEqualTo(0.1);
        assertThat(response.primaryDiagnosis().rootCause()).contains("no failed job");
    }

    @Test
    void propagatesAnUnreachableGithub() {
        when(githubClient.fetchRun(anyString(), anyLong()))
                .thenThrow(new AgentUpstreamException("GitHub returned 404"));

        assertThatThrownBy(() -> service.analyze(request(null, false)))
                .isInstanceOf(AgentUpstreamException.class)
                .hasMessageContaining("404");
    }

    @Test
    void usesTheExplicitPullNumberWhenTheRunHasNone() {
        givenRunWith(runWithoutPullRequest());
        givenFailedJobs(job(11, "build"));
        when(githubClient.fetchJobLog(anyString(), anyLong())).thenReturn("COMPILATION FAILURE");
        when(githubClient.postPullRequestComment(eq(REPOSITORY), eq(99), anyString()))
                .thenReturn("https://github.com/example/platform/pull/99#issuecomment-2");

        AnalyzeCiResponse response = service.analyze(request(99, null));

        assertThat(response.comment().posted()).isTrue();
        assertThat(response.comment().pullNumber()).isEqualTo(99);
    }

    // --- fixtures and helpers ---------------------------------------------

    private void givenRunWith(GithubDtos.WorkflowRun run) {
        when(githubClient.fetchRun(eq(REPOSITORY), eq(RUN_ID))).thenReturn(run);
    }

    private void givenFailedJobs(GithubDtos.WorkflowJob... jobs) {
        when(githubClient.fetchFailedJobs(eq(REPOSITORY), eq(RUN_ID), anyInt()))
                .thenReturn(List.of(jobs));
    }

    private AnalyzeCiRequest request(Integer pullNumber, Boolean comment) {
        return new AnalyzeCiRequest(REPOSITORY, RUN_ID, pullNumber, comment);
    }

    private static GithubDtos.WorkflowJob job(long id, String name) {
        return new GithubDtos.WorkflowJob(id, name, "completed", "failure",
                "https://github.com/example/platform/actions/runs/1234/job/" + id);
    }

    private static GithubDtos.WorkflowRun runWithPullRequest(int pullNumber) {
        return new GithubDtos.WorkflowRun(
                RUN_ID, "CI", "fix: order lookup", "feature/x", "9c65d35abcdef",
                "completed", "failure",
                "https://github.com/example/platform/actions/runs/1234",
                List.of(new GithubDtos.PullRequestRef(pullNumber,
                        "https://github.com/example/platform/pull/" + pullNumber,
                        new GithubDtos.PullRequestRef.Head("feature/x"))));
    }

    private static GithubDtos.WorkflowRun runWithoutPullRequest() {
        return new GithubDtos.WorkflowRun(
                RUN_ID, "CI", "fix: order lookup", "main", "9c65d35abcdef",
                "completed", "failure",
                "https://github.com/example/platform/actions/runs/1234",
                List.of());
    }

    private static AgentProperties properties(boolean commentEnabled, String token) {
        return properties(commentEnabled, token, 12_000, 3);
    }

    private static AgentProperties properties(boolean commentEnabled, String token, int maxLogChars, int maxJobs) {
        return new AgentProperties(
                new AgentProperties.Llm("heuristic", "http://localhost:11434", "llama3.2:3b", 60, 700, 0.1),
                new AgentProperties.Github("https://api.github.com", token, commentEnabled),
                new AgentProperties.Limits(maxLogChars, maxJobs, 30, 12),
                TestProperties.mockTools(),
                TestProperties.incident(),
                TestProperties.security(),
                TestProperties.approval());
    }

    /**
     * A stand-in for the engine: deterministic, and it remembers what it was asked.
     *
     * <p>{@code diagnoseEvidence} is implemented rather than stubbed with
     * {@code null} because the interface requires it of every engine - the CI
     * pipeline in this test never calls it, and a stub that silently returned
     * nothing would hide a future caller reaching it by mistake.
     */
    private static final class StubEngine implements DiagnosisEngine {

        private final AtomicReference<DiagnosisRequest> lastRequest = new AtomicReference<>();
        private final AtomicReference<EvidenceBundle> lastBundle = new AtomicReference<>();

        @Override
        public Diagnosis diagnose(DiagnosisRequest request) {
            lastRequest.set(request);
            return new Diagnosis(
                    "The code did not compile.",
                    List.of("COMPILATION FAILURE"),
                    "Fix the reported compilation error.",
                    "none",
                    0.85);
        }

        @Override
        public Diagnosis diagnoseEvidence(EvidenceBundle bundle) {
            lastBundle.set(bundle);
            return new Diagnosis(
                    "Stub diagnosis from evidence.",
                    bundle.evidence(),
                    "Stub fix.",
                    "none",
                    0.5);
        }

        @Override
        public String provider() {
            return "stub";
        }
    }
}
