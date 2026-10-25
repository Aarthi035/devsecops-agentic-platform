package com.devsecops.agentservice.web;

import com.devsecops.agentservice.error.AgentRequestException;
import com.devsecops.agentservice.incident.AlertmanagerWebhook;
import com.devsecops.agentservice.incident.IncidentAnalysisResponse;
import com.devsecops.agentservice.incident.IncidentAnalysisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Phase 9 trigger: {@code POST /alert}, the Alertmanager webhook receiver.
 *
 * <p>The path and the payload are exactly what Alertmanager sends, and the
 * payload type is bound without {@code @Valid} on purpose: Alertmanager's body
 * is a documented contract that the agent reads field by field, and the fields
 * that must be present - a usable status and at least one alert - are checked by
 * {@code AlertNormalizer}, which is also where the 400 comes from. Splitting that
 * check between bean validation annotations here and the normalizer there would
 * mean two places to look when an alert is refused.
 *
 * <p>Alertmanager retries a notification it does not get a 2xx for, so this
 * endpoint answers 200 with {@code accepted: true} for a duplicate rather than a
 * 409. A non-2xx would make Alertmanager retry harder, which is the opposite of
 * what deduplication is for.
 */
@RestController
public class AlertController {

    private static final Logger log = LoggerFactory.getLogger(AlertController.class);

    private final IncidentAnalysisService incidentAnalysisService;

    public AlertController(IncidentAnalysisService incidentAnalysisService) {
        this.incidentAnalysisService = incidentAnalysisService;
    }

    /**
     * Analyzes an alert notification.
     *
     * <p>A payload that is not an alert notification becomes 400 through {@link
     * AgentExceptionHandler}; an unreachable monitoring service does not fail the
     * request at all, because a missing reading is recorded as a missing reading
     * and the diagnosis proceeds on what was available.
     */
    @PostMapping(
            path = "/alert",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public IncidentAnalysisResponse alert(@RequestBody AlertmanagerWebhook webhook) {
        if (webhook == null) {
            throw new AgentRequestException("The alert payload is empty.");
        }
        log.info("Received an /alert notification '{}' from {} with {} alert(s).",
                webhook.groupKey(), webhook.receiver(),
                webhook.alerts() == null ? 0 : webhook.alerts().size());
        return incidentAnalysisService.analyze(webhook);
    }
}
