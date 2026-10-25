package com.devsecops.agentservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.devsecops.agentservice.github.GithubClient;
import com.devsecops.agentservice.github.GithubDtos;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The whole Phase 8 path over a real socket: an HTTP request in, a diagnosis
 * out, with GitHub replaced and the offline `heuristic` engine answering.
 *
 * <p>This is the test that says the wiring works - the controller is bound, the
 * service is a bean, exactly one engine is in the context, and the response JSON
 * is the shape the README documents. Everything else in the suite tests a part;
 * this one tests the assembly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AgentEndpointIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @MockBean
    private GithubClient githubClient;

    @Test
    void diagnosesAFailedRunThroughTheHttpEndpoint() {
        when(githubClient.fetchRun(eq("example/platform"), eq(1234L))).thenReturn(new GithubDtos.WorkflowRun(
                1234L, "CI", "fix: order lookup", "feature/x", "9c65d35abcdef", "completed", "failure",
                "https://github.com/example/platform/actions/runs/1234",
                List.of(new GithubDtos.PullRequestRef(7, "https://github.com/example/platform/pull/7",
                        new GithubDtos.PullRequestRef.Head("feature/x")))));
        when(githubClient.fetchFailedJobs(eq("example/platform"), eq(1234L), anyInt()))
                .thenReturn(List.of(new GithubDtos.WorkflowJob(
                        9L, "build", "completed", "failure", "https://example/job/9")));
        when(githubClient.fetchJobLog(eq("example/platform"), anyLong())).thenReturn(
                "[ERROR] Compilation failure\n[ERROR] App.java:[42,13] cannot find symbol");

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/analyze-ci",
                Map.of("repository", "example/platform", "runId", 1234),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("provider")).isEqualTo("heuristic");
        assertThat(body.get("workflowName")).isEqualTo("CI");
        assertThat(body.get("failedJobCount")).isEqualTo(1);

        @SuppressWarnings("unchecked")
        Map<String, Object> diagnosis = (Map<String, Object>) body.get("primaryDiagnosis");
        assertThat(diagnosis.get("root_cause")).isEqualTo("The code did not compile.");
        assertThat(asList(diagnosis.get("evidence")))
                .containsExactly("[ERROR] Compilation failure", "[ERROR] App.java:[42,13] cannot find symbol");

        @SuppressWarnings("unchecked")
        Map<String, Object> comment = (Map<String, Object>) body.get("comment");
        // No token is configured in the test environment, so the agent says so
        // instead of pretending it commented.
        assertThat(comment.get("posted")).isEqualTo(false);
        assertThat((String) comment.get("reason")).contains("No GitHub token is configured");
    }

    @Test
    void rejectsAnInvalidRequestBeforeCallingGithub() {
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/analyze-ci", Map.of("repository", "not-a-repo", "runId", 1), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "invalid_request");
    }

    @Test
    void exposesItsHealthToTheOrchestrator() {
        ResponseEntity<Map> response = restTemplate.getForEntity("/actuator/health", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "UP");
    }

    /** Untyped JSON arrays deserialise to {@code List<Object>}; this keeps the assertion readable. */
    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        return (List<Object>) value;
    }
}
