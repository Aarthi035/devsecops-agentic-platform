package com.devsecops.agentservice.web;

import com.devsecops.agentservice.analysis.AnalyzeCiRequest;
import com.devsecops.agentservice.analysis.AnalyzeCiResponse;
import com.devsecops.agentservice.analysis.CiAnalysisService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Phase 8 trigger: {@code POST /analyze-ci}.
 *
 * <p>The path is exactly the spec's, with no {@code /api} prefix even though the
 * other three services are namespaced under one. That prefix belongs to the
 * platform's public API surface, which the Ingress routes by path; this is an
 * internal trigger for a pipeline, and freezing the path the spec names means an
 * example written against the spec keeps working.
 *
 * <p>The controller is deliberately thin - validate, delegate, return. All of the
 * behaviour worth testing lives in {@link CiAnalysisService}, which is why the
 * controller's own test only has to prove that binding and status codes are
 * wired up.
 */
@RestController
public class AnalyzeCiController {

    private static final Logger log = LoggerFactory.getLogger(AnalyzeCiController.class);

    private final CiAnalysisService ciAnalysisService;

    public AnalyzeCiController(CiAnalysisService ciAnalysisService) {
        this.ciAnalysisService = ciAnalysisService;
    }

    /**
     * Analyzes a failed workflow run and comments the diagnosis on its pull
     * request.
     *
     * <p>Returns 200 with the diagnosis. An unreachable GitHub or model endpoint
     * becomes 502 through {@link AgentExceptionHandler}, and a malformed body
     * becomes 400 - the agent never answers 200 with an empty or partial
     * diagnosis.
     */
    @PostMapping(
            path = "/analyze-ci",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public AnalyzeCiResponse analyzeCi(@Valid @RequestBody AnalyzeCiRequest request) {
        log.info("Received an /analyze-ci request for {} run {}.", request.repository(), request.runId());
        return ciAnalysisService.analyze(request);
    }
}
