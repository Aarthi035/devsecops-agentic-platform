package com.devsecops.agentservice.web;

import com.devsecops.agentservice.security.SecurityAnalysisRequest;
import com.devsecops.agentservice.security.SecurityAnalysisResponse;
import com.devsecops.agentservice.security.SecurityAnalysisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Phase 9 trigger: {@code POST /analyze-security}.
 *
 * <p>The body is optional, and that is the point of the endpoint. With no body
 * at all the agent reads the report its deployment was configured with and
 * summarizes everything in it, which is what a scheduled scan wants; with a
 * {@code reportPath} it reads a specific report, and with a
 * {@code severityThreshold} it lists only the findings that matter to that
 * caller.
 *
 * <p>Nothing here can widen the agent's reach: the path is resolved by {@code
 * DiagnosticTools} inside the configured report directory, which refuses
 * anything that escapes it. A caller cannot use this endpoint to read a file the
 * deployment did not put there for it.
 */
@RestController
public class SecurityController {

    private static final Logger log = LoggerFactory.getLogger(SecurityController.class);

    private final SecurityAnalysisService securityAnalysisService;

    public SecurityController(SecurityAnalysisService securityAnalysisService) {
        this.securityAnalysisService = securityAnalysisService;
    }

    /**
     * Analyzes a scanner report.
     *
     * <p>Answers 200 whether or not the report could be used - {@code accepted}
     * in the body carries that answer, together with a diagnosis of why not.
     * That is deliberate: a security question that the agent could not answer
     * must be distinguishable from one whose answer was "nothing found", and an
     * error status would make the two look the same to a caller that only
     * inspects the status code.
     */
    @PostMapping(
            path = "/analyze-security",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public SecurityAnalysisResponse analyzeSecurity(
            @RequestBody(required = false) SecurityAnalysisRequest request) {
        String reportPath = request == null || request.reportPath() == null || request.reportPath().isBlank()
                ? "(configured default)"
                : request.reportPath();
        log.info("Received an /analyze-security request for {} (threshold {}).",
                reportPath, request == null ? "none" : request.severityThreshold());
        return securityAnalysisService.analyze(request);
    }
}
