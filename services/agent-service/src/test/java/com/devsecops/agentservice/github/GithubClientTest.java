package com.devsecops.agentservice.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devsecops.agentservice.config.AgentProperties;
import com.devsecops.agentservice.error.AgentUpstreamException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Drives {@link GithubClient} against a real HTTP server on the loopback
 * interface.
 *
 * <p>A mock would prove that the class calls the methods the test expects, which
 * is the part that is not in doubt. The parts worth testing are the ones a mock
 * erases: whether the token is attached to the API call but <em>not</em> to the
 * signed blob download, whether a redirect is followed by hand, and what a 404
 * turns into. All three need a socket, so this test opens one.
 */
class GithubClientTest {

    private static final String REPOSITORY = "example/platform";

    private HttpServer server;
    private GithubClient client;
    private String baseUrl;

    /** The Authorization header seen for each path, so header handling can be asserted. */
    private final Map<String, String> authorizationByPath = new ConcurrentHashMap<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            authorizationByPath.put(path, String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            // No route registered for the path: 404, which is what the client's
            // error mapping needs to see for the missing-resource tests.
            respond(exchange, 404, "{\"message\":\"Not Found\"}");
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        client = new GithubClient(properties("token-value"), new ObjectMapper());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void readsARunAndMapsItsFields() {
        serve("/repos/" + REPOSITORY + "/actions/runs/42", exchange -> respond(exchange, 200, """
                {"id":42,"name":"CI","display_title":"fix: order lookup","head_branch":"feature/x",
                 "head_sha":"9c65d35abcdef","status":"completed","conclusion":"failure",
                 "html_url":"https://github.com/example/platform/actions/runs/42",
                 "pull_requests":[{"number":7,"html_url":"https://github.com/example/platform/pull/7",
                                   "head":{"ref":"feature/x"}}],
                 "unknown_future_field":true}
                """));

        GithubDtos.WorkflowRun run = client.fetchRun(REPOSITORY, 42);

        assertThat(run.name()).isEqualTo("CI");
        assertThat(run.conclusion()).isEqualTo("failure");
        assertThat(run.branchOrUnknown()).isEqualTo("feature/x");
        assertThat(run.pullRequests()).singleElement()
                .satisfies(pr -> assertThat(pr.number()).isEqualTo(7));
        assertThat(authorizationByPath)
                .containsEntry("/repos/" + REPOSITORY + "/actions/runs/42", "Bearer token-value");
    }

    @Test
    void keepsOnlyFailedJobsAndHonoursTheLimit() {
        serve("/repos/" + REPOSITORY + "/actions/runs/42/jobs", exchange -> respond(exchange, 200, """
                {"total_count":4,"jobs":[
                  {"id":1,"name":"build","status":"completed","conclusion":"failure"},
                  {"id":2,"name":"lint","status":"completed","conclusion":"success"},
                  {"id":3,"name":"test","status":"completed","conclusion":"timed_out"},
                  {"id":4,"name":"docs","status":"completed","conclusion":"skipped"}]}
                """));

        List<GithubDtos.WorkflowJob> jobs = client.fetchFailedJobs(REPOSITORY, 42, 1);

        // success and skipped are excluded; the cap of 1 keeps the first real failure.
        assertThat(jobs).extracting(GithubDtos.WorkflowJob::id).containsExactly(1L);
    }

    @Test
    void followsTheLogRedirectByHandAndDropsTheToken() {
        String blobPath = "/blob/job-77.txt";
        serve("/repos/" + REPOSITORY + "/actions/jobs/77/logs", exchange -> {
            exchange.getResponseHeaders().add("Location", baseUrl + blobPath);
            respond(exchange, 302, "");
        });
        serve(blobPath, exchange -> respond(exchange, 200, "Maven output\nBUILD FAILURE"));

        String log = client.fetchJobLog(REPOSITORY, 77);

        assertThat(log).contains("BUILD FAILURE");
        // The API call is authenticated...
        assertThat(authorizationByPath)
                .containsEntry("/repos/" + REPOSITORY + "/actions/jobs/77/logs", "Bearer token-value");
        // ...and the signed blob request is not: the credential is never handed
        // to a host the agent did not choose.
        assertThat(authorizationByPath).containsEntry(blobPath, "null");
    }

    @Test
    void reportsAMissingRunAsAnUpstreamProblem() {
        assertThatThrownBy(() -> client.fetchRun(REPOSITORY, 999))
                .isInstanceOf(AgentUpstreamException.class)
                .hasMessageContaining("404")
                .hasMessageContaining("Check the repository name and the run id");
    }

    @Test
    void reportsARejectedTokenWithoutEchoingIt() {
        GithubClient rejected = new GithubClient(properties("super-secret-token"), new ObjectMapper());
        serve("/repos/" + REPOSITORY + "/actions/runs/1", exchange -> respond(exchange, 401, "{}"));

        assertThatThrownBy(() -> rejected.fetchRun(REPOSITORY, 1))
                .isInstanceOf(AgentUpstreamException.class)
                .hasMessageContaining("401")
                .hasMessageNotContaining("super-secret-token");
    }

    @Test
    void postsAPullRequestCommentAndReadsItsUrlBack() {
        serve("/repos/" + REPOSITORY + "/issues/7/comments", exchange -> {
            assertThat(readBody(exchange)).contains("\"body\"");
            respond(exchange, 201, "{\"html_url\":\"https://github.com/example/platform/pull/7#issuecomment-1\"}");
        });

        String url = client.postPullRequestComment(REPOSITORY, 7, "## CI diagnosis\n");

        assertThat(url).isEqualTo("https://github.com/example/platform/pull/7#issuecomment-1");
    }

    @Test
    void reportsAForbiddenCommentWithThePermissionHint() {
        serve("/repos/" + REPOSITORY + "/issues/7/comments", exchange -> respond(exchange, 403, "{}"));

        assertThatThrownBy(() -> client.postPullRequestComment(REPOSITORY, 7, "body"))
                .isInstanceOf(AgentUpstreamException.class)
                .hasMessageContaining("issues: write");
    }

    @Test
    void reportsTheTokenAsAbsentWhenNoneIsConfigured() {
        assertThat(new GithubClient(properties(""), new ObjectMapper()).hasToken()).isFalse();
        assertThat(client.hasToken()).isTrue();
    }

    // --- helpers -----------------------------------------------------------

    /** Registers a handler, replacing the catch-all 404 registered in {@code startServer}. */
    private void serve(String path, Consumer<HttpExchange> handler) {
        // The header is recorded here rather than in the catch-all, because
        // HttpServer dispatches to the longest matching prefix: a path with its
        // own handler never reaches "/", and the assertion would silently see
        // nothing instead of failing.
        server.createContext(path, exchange -> {
            authorizationByPath.put(exchange.getRequestURI().getPath(),
                    String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            handler.accept(exchange);
        });
    }

    private static void respond(HttpExchange exchange, int status, String body) {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
            exchange.close();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String readBody(HttpExchange exchange) {
        try {
            return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private AgentProperties properties(String token) {
        return new AgentProperties(
                new AgentProperties.Llm("heuristic", baseUrl, "llama3.2:3b", 5, 700, 0.1),
                new AgentProperties.Github(baseUrl, token, true),
                new AgentProperties.Limits(12_000, 3, 5));
    }
}
