package com.devsecops.agentservice.incident;

import java.util.List;

/**
 * One alert, reduced to the fields the agent actually uses.
 *
 * <p>Alertmanager's payload is deliberately generic: labels are whatever the
 * Prometheus rule chose to attach, and nothing enforces that a label called
 * {@code pod} really names a pod. Normalising here - once, in one place - means
 * the investigator, the diagnosis, the action proposal and the audit entries all
 * agree on what the subject is, instead of each re-deriving it from raw labels
 * and disagreeing at the edges.
 *
 * <p>{@code subject} is the single most important value in this record: it is
 * what a proposed action will be aimed at. Anything derived from labels rather
 * than a verified read is recorded as {@code inferred} so a wrong guess is
 * visible in the response instead of silently becoming a restart of the wrong
 * workload.
 */
public record NormalizedAlert(
        /** The alert's name, from the {@code alertname} label. */
        String name,
        /** {@code firing} or {@code resolved}. */
        String status,
        /** The namespace the alert is about; {@code agent.incident.namespace} when the label is absent. */
        String namespace,
        /** A Kubernetes kind the agent can read: {@code Pod}, {@code Deployment}, ... */
        String workloadKind,
        /** The object's name. */
        String workloadName,
        /** {@code critical}, {@code warning}, ... from the {@code severity} label. */
        String severity,
        /** The one-line description: the {@code summary} annotation, or a synthesised fallback. */
        String summary,
        /** The longer description: the {@code description} annotation, possibly multiline. */
        String description,
        /** Alertmanager's fingerprint, or a derived key when the sender omitted one. */
        String fingerprint,
        /** When the alert started firing, as sent. */
        String startsAt,
        /** The log selector to use for this subject, e.g. {@code {namespace="devsecops"}}. */
        String logSelector,
        /** True when the kind or name came from a label the agent had to interpret. */
        boolean inferred) {

    /** {@code deployment devsecops/user-service} - the phrase used in facts and responses. */
    public String subject() {
        return workloadKind.toLowerCase(java.util.Locale.ROOT) + " " + namespace + "/" + workloadName;
    }

    /** A stable identity for a subject, used as the action's target key. */
    public String targetKey() {
        return workloadKind + "/" + namespace + "/" + workloadName;
    }

    /** The description split into lines, so a multiline description is evidence, not a blob. */
    public List<String> descriptionLines() {
        if (description == null || description.isBlank()) {
            return List.of();
        }
        return description.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
    }
}
