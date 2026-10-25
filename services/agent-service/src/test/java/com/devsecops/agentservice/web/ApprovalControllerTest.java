package com.devsecops.agentservice.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devsecops.agentservice.action.ActionStatus;
import com.devsecops.agentservice.action.ApprovalResult;
import com.devsecops.agentservice.action.ApprovalService;
import com.devsecops.agentservice.error.AgentRequestException;
import com.devsecops.agentservice.error.AgentUnauthorizedException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The HTTP surface of {@code POST /approve/{actionId}} - the human gate.
 *
 * <p>The service is mocked, because what this class has to prove is not how a
 * decision is made but where the token is read from and which status code each
 * refusal gets. The 400-versus-401 split is the thing an operator reads at a
 * glance, and the header-before-body rule is the difference between a token in
 * a proxy log and one that is not.
 */
@WebMvcTest(ApprovalController.class)
class ApprovalControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ApprovalService approvalService;

    @Test
    void refusesABodyThatDoesNotSayWhetherItApproves() throws Exception {
        mockMvc.perform(post("/approve/act-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("\"approve\"")));

        verify(approvalService, never()).decide(any(), anyBoolean(), any(), any());
    }

    @Test
    void mapsADisabledOrWrongTokenToUnauthorized() throws Exception {
        when(approvalService.decide(eq("act-1"), eq(true), any(), any()))
                .thenThrow(new AgentUnauthorizedException(
                        "The approval token is missing or wrong; the action was not executed."));

        mockMvc.perform(post("/approve/act-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approve\":true,\"token\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("not executed")));
    }

    @Test
    void prefersTheBearerHeaderOverTheBodyToken() throws Exception {
        when(approvalService.decide(any(), anyBoolean(), any(), any())).thenReturn(executed());

        mockMvc.perform(post("/approve/act-1")
                        .header("Authorization", "Bearer header-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approve\":true,\"token\":\"body-token\"}"))
                .andExpect(status().isOk());

        assertThat(capturedToken()).isEqualTo("header-token");
    }

    @Test
    void usesTheBodyTokenWhenThereIsNoHeader() throws Exception {
        when(approvalService.decide(any(), anyBoolean(), any(), any())).thenReturn(executed());

        mockMvc.perform(post("/approve/act-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approve\":true,\"token\":\"body-token\"}"))
                .andExpect(status().isOk());

        assertThat(capturedToken()).isEqualTo("body-token");
    }

    @Test
    void passesTheApproverNameThroughForTheAuditTrail() throws Exception {
        when(approvalService.decide(any(), anyBoolean(), any(), any())).thenReturn(executed());

        mockMvc.perform(post("/approve/act-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approve\":true,\"token\":\"t\",\"approver\":\"alice\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(jsonPath("$.executed").value(true));

        verify(approvalService).decide(eq("act-1"), eq(true), eq("t"), eq("alice"));
    }

    @Test
    void mapsAnUnknownActionIdToBadRequest() throws Exception {
        when(approvalService.decide(any(), anyBoolean(), any(), any()))
                .thenThrow(new AgentRequestException("There is no proposed action with id 'nope'."));

        mockMvc.perform(post("/approve/nope")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approve\":true,\"token\":\"t\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("no proposed action")));
    }

    private String capturedToken() {
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(approvalService).decide(any(), anyBoolean(), token.capture(), any());
        return token.getValue();
    }

    private static ApprovalResult executed() {
        return new ApprovalResult(
                "act-1", "APPROVED", ActionStatus.EXECUTED, true,
                "The action was approved and executed.",
                "alice", Instant.parse("2026-10-25T09:00:00Z"), null, null);
    }
}
