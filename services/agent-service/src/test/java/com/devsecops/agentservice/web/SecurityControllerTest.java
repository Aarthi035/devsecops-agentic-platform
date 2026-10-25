package com.devsecops.agentservice.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devsecops.agentservice.diagnosis.Diagnosis;
import com.devsecops.agentservice.security.SecurityAnalysisResponse;
import com.devsecops.agentservice.security.SecurityAnalysisService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The HTTP surface of {@code POST /analyze-security}.
 *
 * <p>The property this class exists to pin is that the endpoint answers 200
 * whether or not the report could be used. {@code accepted} in the body is what
 * distinguishes "there is no vulnerability" from "I could not find out", and an
 * error status would erase that distinction for any caller that only reads the
 * status code - which is most callers.
 */
@WebMvcTest(SecurityController.class)
class SecurityControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SecurityAnalysisService securityAnalysisService;

    @Test
    void returnsTheSummaryForAUsableReport() throws Exception {
        when(securityAnalysisService.analyze(any())).thenReturn(accepted());

        mockMvc.perform(post("/analyze-security")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reportPath\":\"/reports/trivy.json\",\"severityThreshold\":\"HIGH\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.highestSeverity").value("CRITICAL"))
                .andExpect(jsonPath("$.findingCount").value(3))
                .andExpect(jsonPath("$.listedFindings").value(2))
                .andExpect(jsonPath("$.provider").value("heuristic"));
    }

    @Test
    void answers200WithAcceptedFalseForAnUnusableReport() throws Exception {
        when(securityAnalysisService.analyze(any())).thenReturn(unusable());

        mockMvc.perform(post("/analyze-security")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reportPath\":\"/reports/missing.json\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.findingCount").value(0))
                .andExpect(jsonPath("$.highestSeverity").value("NONE"))
                .andExpect(jsonPath("$.diagnosis.confidence").value(0.0));
    }

    @Test
    void acceptsARequestWithNoBody() throws Exception {
        when(securityAnalysisService.analyze(any())).thenReturn(accepted());

        mockMvc.perform(post("/analyze-security")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true));

        verify(securityAnalysisService).analyze(null);
    }

    @Test
    void rejectsABodyThatIsNotJson() throws Exception {
        mockMvc.perform(post("/analyze-security")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reportPath\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    private static SecurityAnalysisResponse accepted() {
        Diagnosis diagnosis = new Diagnosis(
                "The image contains a CRITICAL vulnerability.", List.of("CVE-2024-3094"),
                "Upgrade xz-utils.", "rollback", 0.8);
        return new SecurityAnalysisResponse(
                true, "/reports/trivy.json", "user-service:1.4.2", 3,
                Map.of("CRITICAL", 1, "HIGH", 1, "MEDIUM", 1), "CRITICAL", 2, true,
                "mock", "heuristic", diagnosis, List.of(),
                List.of("Only findings at or above HIGH are listed; the counts cover the whole report."));
    }

    private static SecurityAnalysisResponse unusable() {
        Diagnosis diagnosis = new Diagnosis(
                "The report could not be read.", List.of(),
                "Check agent.security.report-path.", "none", 0.0);
        return new SecurityAnalysisResponse(
                false, "/reports/missing.json", "", 0, Map.of(), "NONE", 0, false,
                "mock", "heuristic", diagnosis, List.of(),
                List.of("The security report could not be read: it is missing."));
    }
}
