package com.devsecops.agentservice.github;

import com.devsecops.agentservice.config.AgentProperties;
import com.devsecops.agentservice.error.AgentUpstreamException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The agent's read-and-comment access to GitHub Actions.
 *
 * <p>Four calls, all of them plain REST: read a run, list its jobs, download a
 * job's log, post a comment. No Octokit, for the same reason there is no Spring
 * AI - it is four requests, and the JDK already has an HTTP client.
 *
 * <p>The one genuinely non-obvious part is the log download, and it is the
 * reason this class exists instead of a one-liner in the service:
 *
 * <blockquote>GitHub does not return the log itself. It returns
 * {@code 302 Found} with a {@code Location} pointing at a short-lived signed
 * blob URL, and that URL is on a different host, which rejects the request if
 * the {@code Authorization} header is still attached.</blockquote>
 *
 * <p>{@code HttpClient.Redirect.NORMAL} would follow that redirect
 * automatically <em>and keep the header</em>, so the download fails with an
 * opaque 400 from the blob store. Redirects are therefore disabled here and
 * followed by hand: the signed URL is fetched with no credentials, which is both
 * what the blob store expects and the safer choice - a credential is never sent
 * to a host the agent did not choose.
 *
 * <p>The token is only ever read from configuration and placed in a header. It
 * is never logged, never put in an exception message, and never returned.
 */
@Component
public class GithubClient {

    private static final Logger log = LoggerFactory.getLogger(GithubClient.class);

    /** GitHub's documented version header; pinning it keeps responses stable. */
    private static final String GITHUB_API_VERSION = "2022-11-28";

    /** GitHub's API rejects requests without a UA, and it is handy in their logs. */
    private static final String USER_AGENT = "devsecops-agentic-platform";

    /**
     * A job log can be a few hundred KB; this is a ceiling on what will be read
     * into memory before the sanitiser truncates it. It is deliberately generous
     * - the guardrail that matters for the model is {@code max-log-chars}, and
     * cutting the download too early would cut it before the failure, which is
     * usually at the end.
     */
    private static final int MAX_LOG_DOWNLOAD_BYTES = 5 * 1024 * 1024;

    private static final int LOG_PAGE_SIZE = 100;

    private final AgentProperties.Github github;
    private final int timeoutSeconds;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public GithubClient(AgentProperties properties, ObjectMapper objectMapper) {
        this.github = properties.github();
        this.timeoutSeconds = properties.limits().githubTimeoutSeconds();
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, Math.min(10, timeoutSeconds))))
                // See the class comment: redirects are handled by hand so the
                // token is not carried to the blob store.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * Reads one workflow run.
     *
     * @param repository {@code owner/name}
     * @param runId      the run's numeric id, as on the run's URL
     */
    public GithubDtos.WorkflowRun fetchRun(String repository, long runId) {
        JsonNode node = getJson("/repos/" + repository + "/actions/runs/" + runId);
        return objectMapper.convertValue(node, GithubDtos.WorkflowRun.class);
    }

    /**
     * Lists the run's jobs that did not succeed, newest first as GitHub returns
     * them, capped at {@code limit}.
     *
     * <p>The cap is the spec's "max steps" guardrail applied here: each failed
     * job costs one log download and, under the Ollama engine, one model call, so
     * an unbounded list would be an unbounded bill.
     */
    public List<GithubDtos.WorkflowJob> fetchFailedJobs(String repository, long runId, int limit) {
        JsonNode node = getJson("/repos/" + repository + "/actions/runs/" + runId
                + "/jobs?filter=latest&per_page=" + LOG_PAGE_SIZE);
        GithubDtos.WorkflowJobsResponse response =
                objectMapper.convertValue(node, GithubDtos.WorkflowJobsResponse.class);

        if (response.jobs() == null) {
            return List.of();
        }
        return response.jobs().stream()
                .filter(GithubDtos.WorkflowJob::isFailed)
                .limit(Math.max(1, limit))
                .toList();
    }

    /**
     * Downloads one job's log as text.
     *
     * <p>Follows GitHub's redirect to the signed blob URL without the token (see
     * the class comment) and refuses to read more than
     * {@link #MAX_LOG_DOWNLOAD_BYTES}.
     */
    public String fetchJobLog(String repository, long jobId) {
        String url = apiUrl() + "/repos/" + repository + "/actions/jobs/" + jobId + "/logs";

        HttpResponse<String> response = execute(url, true);
        if (isRedirect(response.statusCode())) {
            String location = response.headers().firstValue("Location")
                    .orElseThrow(() -> new AgentUpstreamException(
                            "GitHub redirected the log download for job " + jobId
                                    + " without a Location header."));
            log.debug("Following GitHub's log redirect for job {}.", jobId);
            response = execute(location, false);
        }

        if (response.statusCode() / 100 != 2) {
            throw new AgentUpstreamException("Could not download the log for job " + jobId
                    + ": GitHub returned HTTP " + response.statusCode() + ".");
        }
        String body = response.body() == null ? "" : response.body();
        if (body.length() > MAX_LOG_DOWNLOAD_BYTES) {
            log.warn("Job {} log was {} characters; keeping the first {} and the sanitiser will truncate further.",
                    jobId, body.length(), MAX_LOG_DOWNLOAD_BYTES);
            body = body.substring(0, MAX_LOG_DOWNLOAD_BYTES);
        }
        return body;
    }

    /**
     * Posts a comment on a pull request and returns its html_url.
     *
     * <p>Nothing else in the platform writes to GitHub, and this is the only
     * method that does not read. It is a separate call so a caller can decide not
     * to make it - {@code agent.github.comment-enabled} exists for exactly that,
     * and the dry run is what makes the endpoint safe to exercise against a real
     * repository.
     *
     * @throws AgentUpstreamException if the repository or the pull request number
     *                               is wrong, or the token lacks {@code issues: write}
     */
    public String postPullRequestComment(String repository, int pullNumber, String body) {
        String url = apiUrl() + "/repos/" + repository + "/issues/" + pullNumber + "/comments";
        String payload;
        try {
            payload = objectMapper.writeValueAsString(Map.of("body", body));
        } catch (JacksonException e) {
            throw new IllegalStateException("Could not serialise the comment body.", e);
        }

        HttpResponse<String> response = execute(url, true, payload);
        if (response.statusCode() / 100 != 2) {
            throw new AgentUpstreamException("Could not comment on " + repository + "#" + pullNumber
                    + ": GitHub returned HTTP " + response.statusCode()
                    + ". Check that the token has issues: write permission.");
        }
        try {
            return objectMapper.readTree(response.body()).path("html_url").asText(null);
        } catch (JacksonException e) {
            // The comment was posted; only reading its URL back failed, which is
            // not worth failing the whole analysis over.
            log.warn("Comment on {}#{} was posted but its URL could not be read back.", repository, pullNumber);
            return null;
        }
    }

    /** Whether a token is configured; the caller uses this to explain a dry run. */
    public boolean hasToken() {
        return github.hasToken();
    }

    private String apiUrl() {
        String base = github.apiUrl();
        return base == null || base.isBlank() ? "https://api.github.com" : base.replaceAll("/+$", "");
    }

    /** GETs a JSON document from the API, with the token. */
    private JsonNode getJson(String path) {
        HttpResponse<String> response = execute(apiUrl() + path, true);
        int status = response.statusCode();

        if (status == 401 || status == 403) {
            throw new AgentUpstreamException("GitHub rejected the request (" + status + ") for " + path
                    + ". Check that GITHUB_TOKEN is set and has actions: read and, to comment, issues: write.");
        }
        if (status == 404) {
            throw new AgentUpstreamException("GitHub returned 404 for " + path
                    + ". Check the repository name and the run id.");
        }
        if (status / 100 != 2) {
            throw new AgentUpstreamException("GitHub returned HTTP " + status + " for " + path + ".");
        }
        try {
            return objectMapper.readTree(response.body());
        } catch (JacksonException e) {
            throw new AgentUpstreamException("GitHub returned something that is not JSON for " + path + ".", e);
        }
    }

    private HttpResponse<String> execute(String url, boolean authenticate) {
        return execute(url, authenticate, null);
    }

    /**
     * Sends one request. {@code payload} null means GET, otherwise POST.
     *
     * <p>{@code authenticate} is a parameter rather than a field read because the
     * two kinds of URL this class touches are not the same trust domain: the API
     * wants the token, the signed blob URL must not see it.
     */
    private HttpResponse<String> execute(String url, boolean authenticate, String payload) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", GITHUB_API_VERSION)
                .header("User-Agent", USER_AGENT);

        if (authenticate && github.hasToken()) {
            builder.header("Authorization", "Bearer " + github.token());
        }
        if (payload == null) {
            builder.GET();
        } else {
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
        }

        try {
            return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            // The path is named, the token is not - and the URL of a signed blob
            // is not logged either, because it is itself a credential.
            throw new AgentUpstreamException("Could not reach the GitHub API: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentUpstreamException("The GitHub API call was interrupted.", e);
        }
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }
}
