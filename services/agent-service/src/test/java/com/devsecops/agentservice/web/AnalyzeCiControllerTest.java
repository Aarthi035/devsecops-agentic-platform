package com.devsecops.agentservice.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devsecops.agentservice.analysis.AnalyzeCiResponse;
import com.devsecops.agentservice.analysis.CiAnalysisService;
import com.devsecops.agentservice.diagnosis.Diagnosis;
import com.devsecops.agentservice.error.AgentUpstreamException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The HTTP surface of {@code POST /analyze-ci}: binding, status codes and the
 * error mapping.
 *
 * <p>The service is mocked because everything worth asserting here happens
 * before or after it - a malformed body is a 400, an unreachable GitHub is a
 * 502, and neither of those is the service's business.
 */
@WebMvcTest(AnalyzeCiController.class)
class AnalyzeCiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CiAnalysisService ciAnalysisService;

    @Test
    void returnsTheDiagnosisForAValidRequest() throws Exception {
        when(ciAnalysisService.analyze(any())).thenReturn(successResponse());

        mockMvc.perform(post("/analyze-ci")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repository\":\"example/platform\",\"runId\":1234}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repository").value("example/platform"))
                .andExpect(jsonPath("$.provider").value("heuristic"))
                .andExpect(jsonPath("$.primaryDiagnosis.root_cause").value("The code did not compile."))
                .andExpect(jsonPath("$.comment.posted").value(false));
    }

    @Test
    void rejectsAMissingRepository() throws Exception {
        mockMvc.perform(post("/analyze-ci")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runId\":1234}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"))
                .andExpect(jsonPath("$.details[0]").value(org.hamcrest.Matchers.containsString("repository")));
    }

    @Test
    void rejectsARepositoryThatIsNotOwnerSlashName() throws Exception {
        mockMvc.perform(post("/analyze-ci")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repository\":\"../etc/passwd\",\"runId\":1234}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    @Test
    void rejectsANonPositiveRunId() throws Exception {
        mockMvc.perform(post("/analyze-ci")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repository\":\"example/platform\",\"runId\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    @Test
    void rejectsABodyThatIsNotJson() throws Exception {
        mockMvc.perform(post("/analyze-ci")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repository\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    @Test
    void mapsAnUnreachableUpstreamToBadGateway() throws Exception {
        when(ciAnalysisService.analyze(any()))
                .thenThrow(new AgentUpstreamException("GitHub returned 404. Check the repository name and the run id."));

        mockMvc.perform(post("/analyze-ci")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repository\":\"example/platform\",\"runId\":999}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("upstream_error"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("404")));
    }

    private static AnalyzeCiResponse successResponse() {
        Diagnosis diagnosis = new Diagnosis(
                "The code did not compile.", List.of("Compilation failure"),
                "Fix the reported compilation error.", "none", 0.85);
        return new AnalyzeCiResponse(
                "example/platform", 1234L, "CI",
                "https://github.com/example/platform/actions/runs/1234", "feature/x", "9c65d35",
                "failure",
                List.of(new AnalyzeCiResponse.JobOutcome(1L, "build", null, "failure", 100, false, diagnosis)),
                diagnosis, 1, 1, "heuristic",
                new AnalyzeCiResponse.CommentOutcome(false, null, null, "No GitHub token is configured"));
    }
}
