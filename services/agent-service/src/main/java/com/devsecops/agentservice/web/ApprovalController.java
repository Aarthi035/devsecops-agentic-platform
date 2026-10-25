package com.devsecops.agentservice.web;

import com.devsecops.agentservice.action.ApprovalRequest;
import com.devsecops.agentservice.action.ApprovalResult;
import com.devsecops.agentservice.action.ApprovalService;
import com.devsecops.agentservice.error.AgentRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Phase 9 human gate: {@code POST /approve/{actionId}}.
 *
 * <p>The only endpoint in the service that can cause a state change, and it
 * causes one only if the caller presents the approval token. Everything that
 * decides whether an action may run is in {@link ApprovalService}; this class
 * does two things - it gets the token into the service from wherever the caller
 * put it, and it refuses a request that did not say what it wanted.
 *
 * <p><b>Where the token comes from.</b> An {@code Authorization: Bearer} header
 * is checked first, then the body's {@code token} field. The header is preferred
 * because request bodies are far more likely to end up in a proxy's log or a
 * browser's developer tools; the body field exists so a curl in a demo script
 * stays readable. Both are compared against the configured value in constant
 * time, and neither is ever logged - the response to a wrong token says the
 * token was wrong, not what it was.
 *
 * <p><b>A 401 and a 400 mean different things here.</b> 400 is "there is no such
 * action, or you did not say whether you approve" - the request is malformed.
 * 401 is "this deployment will not accept approvals, or yours was wrong" - the
 * request was well formed and was refused. An operator debugging an approval
 * needs to be able to tell those apart at a glance.
 */
@RestController
public class ApprovalController {

    private static final Logger log = LoggerFactory.getLogger(ApprovalController.class);

    private static final String BEARER = "Bearer ";

    private final ApprovalService approvalService;

    public ApprovalController(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    /**
     * Approves or rejects a proposed action.
     *
     * @param actionId      the id from the {@code /alert} response
     * @param authorization {@code Bearer <token>}, if the caller sent one there
     * @param request       the decision, and optionally the token and the approver
     */
    @PostMapping(
            path = "/approve/{actionId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApprovalResult approve(@PathVariable String actionId,
                                  @RequestHeader(value = "Authorization", required = false)
                                  String authorization,
                                  @RequestBody ApprovalRequest request) {
        if (request == null || request.approve() == null) {
            throw new AgentRequestException("Say whether you approve: the body must contain "
                    + "\"approve\": true or \"approve\": false.");
        }

        String token = tokenFrom(authorization, request.token());
        boolean approve = request.approve();
        String approver = request.approver();

        // The id and the decision, never the token.
        log.info("Received an /approve request for action {} (approve={}).", actionId, approve);
        return approvalService.decide(actionId, approve, token, approver);
    }

    /** The bearer token from the header if there is one, otherwise whatever the body carried. */
    private static String tokenFrom(String authorization, String bodyToken) {
        if (authorization != null && authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return authorization.substring(BEARER.length()).strip();
        }
        return bodyToken;
    }
}
