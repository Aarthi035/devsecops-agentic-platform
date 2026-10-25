package com.devsecops.agentservice.incident;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * The payload Alertmanager posts to a webhook receiver.
 *
 * <p>Modelled rather than parsed loosely, because the shape is a documented
 * contract and the endpoint should reject what does not match it instead of
 * guessing. Field names are Alertmanager's, which are camel-cased with one
 * exception: {@code externalURL}.
 *
 * <p>{@code ignoreUnknown} is set so an Alertmanager upgrade that adds a field
 * does not start dropping alerts - a webhook that 400s because the sender got
 * newer is a monitoring outage caused by the monitoring.
 *
 * <p>The three label maps matter and are not interchangeable: {@code
 * groupLabels} is what the alerts in this notification have in common, {@code
 * commonLabels}/{@code commonAnnotations} are the shared ones, and each {@link
 * Alert} carries its own. The agent reads the per-alert maps and falls back to
 * the common ones, which is what Alertmanager's own documentation recommends.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AlertmanagerWebhook(
        String version,
        String groupKey,
        String status,
        String receiver,
        Map<String, String> groupLabels,
        Map<String, String> commonLabels,
        Map<String, String> commonAnnotations,
        @JsonProperty("externalURL") String externalUrl,
        List<Alert> alerts) {

    /**
     * One alert in the notification.
     *
     * <p>{@code status} is {@code firing} or {@code resolved}; {@code endsAt} is
     * set when a firing alert resolves. Both are read rather than assumed,
     * because a receiver configured with {@code send_resolved: true} gets told
     * about recoveries as well as failures, and treating a recovery as an
     * incident would have the agent investigate something that is already over.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Alert(
            String status,
            Map<String, String> labels,
            Map<String, String> annotations,
            String startsAt,
            String endsAt,
            String generatorURL,
            String fingerprint) {
    }
}
