package com.devsecops.agentservice.web;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devsecops.agentservice.diagnosis.Diagnosis;
import com.devsecops.agentservice.error.AgentRequestException;
import com.devsecops.agentservice.incident.IncidentAnalysisResponse;
import com.devsecops.agentservice.incident.IncidentAnalysisService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The HTTP surface of {@code POST /alert} - the Alertmanager webhook receiver.
 *
 * <p>Two properties are worth pinning here. An unusable payload is a 400 that
 * names the problem, because a webhook that silently 200s a body it could not
 * read is a webhook nobody can debug. And a duplicate is a 200, not a conflict,
 * because Alertmanager retries anything it does not see a 2xx for - answering
 * a re-send with an error would make it retry harder.
 */
@WebMvcTest(AlertController.class)
class AlertControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IncidentAnalysisService incidentAnalysisService;

    @Test
    void returnsTheInvestigationForAValidNotification() throws Exception {
        when(incidentAnalysisService.analyze(any())).thenReturn(response(false));

        mockMvc.perform(post("/alert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(notification()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.subject").value("deployment devsecops/user-service"))
                .andExpect(jsonPath("$.provider").value("heuristic"))
                .andExpect(jsonPath("$.toolCalls").value(2));
    }

    @Test
    void answers200ForADuplicateRatherThanAConflict() throws Exception {
        when(incidentAnalysisService.analyze(any())).thenReturn(response(true));

        mockMvc.perform(post("/alert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(notification()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deduplicated").value(true));
    }

    @Test
    void rejectsAPayloadThatCannotBeReadAsAnAlertNotification() throws Exception {
        // The body is a required @RequestBody, so a JSON "null" is refused by the
        // message converter before the controller runs; either way the caller sees
        // a 400 invalid_request and the service is never reached.
        mockMvc.perform(post("/alert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("null"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));

        verify(incidentAnalysisService, never()).analyze(any());
    }

    @Test
    void refusesANullWebhookIfTheControllerIsInvokedDirectly() {
        AlertController controller = new AlertController(incidentAnalysisService);

        assertThatThrownBy(() -> controller.alert(null))
                .isInstanceOf(AgentRequestException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void mapsAnUnusableAlertToBadRequest() throws Exception {
        when(incidentAnalysisService.analyze(any()))
                .thenThrow(new AgentRequestException(
                        "The alert status must be 'firing' or 'resolved', not 'banana'."));

        mockMvc.perform(post("/alert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(notification()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("firing")));
    }

    @Test
    void rejectsABodyThatIsNotJson() throws Exception {
        mockMvc.perform(post("/alert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    private static String notification() {
        return "{\"version\":\"4\",\"status\":\"firing\",\"groupKey\":\"g1\","
                + "\"receiver\":\"agent\",\"alerts\":[{\"status\":\"firing\","
                + "\"labels\":{\"alertname\":\"ServiceCrashLooping\",\"service\":\"user-service\","
                + "\"namespace\":\"devsecops\",\"severity\":\"critical\"},"
                + "\"fingerprint\":\"fp-1\"}]}";
    }

    private static IncidentAnalysisResponse response(boolean deduplicated) {
        Diagnosis diagnosis = new Diagnosis(
                "The container is crash-looping.", List.of("Back-off restarting failed container"),
                "Restart the workload.", "restart", 0.8);
        return new IncidentAnalysisResponse(
                true, deduplicated, "g1", "firing", "deployment devsecops/user-service",
                1, 1, List.of(), List.of("up{service=\"user-service\"} = 0"), List.of(),
                2, 12, false, "mock", "mock", false, "heuristic",
                diagnosis, null, List.of());
    }
}
