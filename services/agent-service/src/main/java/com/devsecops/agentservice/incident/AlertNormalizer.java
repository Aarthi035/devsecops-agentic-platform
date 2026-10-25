package com.devsecops.agentservice.incident;

import com.devsecops.agentservice.config.AgentProperties;
import com.devsecops.agentservice.error.AgentRequestException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Turns an Alertmanager payload into {@link NormalizedAlert}s, or refuses it.
 *
 * <p>This is the boundary where unvalidated input stops. Everything
 * downstream - the investigator, the diagnosis, the proposed action - works on
 * {@code NormalizedAlert} values whose namespace and target have already been
 * decided, so none of them can disagree about what the alert is about.
 *
 * <p><b>How the target is chosen.</b> Alertmanager labels are strings a rule
 * author wrote; nothing guarantees a label called {@code service} names a
 * Deployment. The order below is therefore a documented convention rather than
 * a lookup:
 *
 * <ol>
 *   <li>{@code deployment}, {@code statefulset}, {@code daemonset} - the author
 *       named the workload kind explicitly, so it is believed.</li>
 *   <li>{@code service} - this platform's convention: the scrape config sets
 *       {@code service: user-service}, and the Deployment is named
 *       {@code user-service}. Mapped to a Deployment, and marked
 *       {@code inferred}.</li>
 *   <li>{@code app}, {@code job} - the same convention under other common
 *       names, also inferred.</li>
 *   <li>{@code pod} - believed last, because restarting a Pod is a narrower act
 *       than restarting the workload that owns it.</li>
 * </ol>
 *
 * <p>{@code inferred} is carried on the record and printed in the response, so
 * "the agent restarted the wrong thing" is diagnosable from the response itself
 * rather than by reading this file. When nothing matches, the target is left
 * {@code unknown} and the investigator skips the object reads entirely - a
 * guess that reads the wrong pod is worse than no read at all.
 */
@Component
public class AlertNormalizer {

    /** Labels naming a workload kind outright, in the order they are trusted. */
    private static final List<String> EXPLICIT_KINDS =
            List.of("deployment", "statefulset", "daemonset", "replicaset");

    /** Labels that name an application rather than a Kubernetes kind. */
    private static final List<String> APP_LABELS = List.of("service", "app", "job");

    /** What a label is called -> the Kubernetes kind it is read as. */
    private static final Map<String, String> LABEL_KIND = Map.of(
            "deployment", "Deployment",
            "statefulset", "StatefulSet",
            "daemonset", "DaemonSet",
            "replicaset", "ReplicaSet",
            "service", "Deployment",
            "app", "Deployment",
            "job", "Deployment",
            "pod", "Pod");

    private final AgentProperties.Incident incident;

    public AlertNormalizer(AgentProperties properties) {
        this.incident = properties.incident();
    }

    /**
     * Normalises a payload.
     *
     * @throws AgentRequestException when the payload cannot describe an incident:
     *         an unknown top-level status, or no alerts at all
     */
    public List<NormalizedAlert> normalize(AlertmanagerWebhook webhook) {
        if (webhook == null) {
            throw new AgentRequestException("The alert payload is empty.");
        }
        String status = webhook.status() == null ? "" : webhook.status().strip().toLowerCase(Locale.ROOT);
        if (!status.equals("firing") && !status.equals("resolved")) {
            throw new AgentRequestException("The alert status must be 'firing' or 'resolved', not '"
                    + webhook.status() + "'.");
        }
        if (webhook.alerts() == null || webhook.alerts().isEmpty()) {
            throw new AgentRequestException("The alert payload contains no alerts.");
        }

        int limit = Math.max(1, incident.maxAlerts());
        List<NormalizedAlert> normalized = new ArrayList<>();
        for (AlertmanagerWebhook.Alert alert : webhook.alerts()) {
            if (normalized.size() >= limit) {
                break;
            }
            if (alert == null) {
                continue;
            }
            normalized.add(normalizeOne(alert, webhook, status));
        }
        if (normalized.isEmpty()) {
            throw new AgentRequestException("The alert payload contains no usable alert.");
        }
        return List.copyOf(normalized);
    }

    /** Truncates an over-long group to the configured maximum, reporting how many were dropped. */
    public int droppedCount(AlertmanagerWebhook webhook) {
        int sent = webhook == null || webhook.alerts() == null ? 0 : webhook.alerts().size();
        return Math.max(0, sent - Math.max(1, incident.maxAlerts()));
    }

    private NormalizedAlert normalizeOne(AlertmanagerWebhook.Alert alert,
                                         AlertmanagerWebhook webhook,
                                         String groupStatus) {
        Map<String, String> labels = merge(webhook.commonLabels(), alert.labels());
        Map<String, String> annotations = merge(webhook.commonAnnotations(), alert.annotations());

        String name = firstNonBlank(labels.get("alertname"), "UnknownAlert");
        String namespace = firstNonBlank(labels.get("namespace"), incident.namespace());
        String severity = firstNonBlank(labels.get("severity"), "unknown");

        Target target = resolveTarget(labels, name);
        String status = alert.status() == null || alert.status().isBlank()
                ? groupStatus
                : alert.status().strip().toLowerCase(Locale.ROOT);

        String summary = firstNonBlank(annotations.get("summary"),
                name + " on " + target.kind().toLowerCase(Locale.ROOT) + " "
                        + namespace + "/" + target.name());
        String description = firstNonBlank(annotations.get("description"), "");
        String fingerprint = firstNonBlank(alert.fingerprint(),
                deriveFingerprint(name, target.key(namespace), labels));

        return new NormalizedAlert(
                name,
                status,
                namespace,
                target.kind(),
                target.name(),
                severity,
                summary,
                description,
                fingerprint,
                firstNonBlank(alert.startsAt(), ""),
                logSelector(namespace, labels),
                target.inferred());
    }

    /**
     * The log selector for a subject.
     *
     * <p>Narrowed to the pod when the alert names one - a namespace-wide query
     * on a busy cluster returns far more than the incident - and to the
     * namespace otherwise. Built here rather than by the caller so every alert
     * is queried the same way.
     */
    private static String logSelector(String namespace, Map<String, String> labels) {
        String pod = labels.get("pod");
        if (pod != null && !pod.isBlank()) {
            return "{namespace=\"" + namespace + "\", pod=\"" + pod + "\"}";
        }
        return "{namespace=\"" + namespace + "\"}";
    }

    /** The target chosen for an alert, and whether the choice was an interpretation. */
    private record Target(String kind, String name, boolean inferred) {

        String key(String namespace) {
            return kind + "/" + namespace + "/" + name;
        }
    }

    private static Target resolveTarget(Map<String, String> labels, String alertName) {
        for (String label : EXPLICIT_KINDS) {
            String value = labels.get(label);
            if (value != null && !value.isBlank()) {
                return new Target(LABEL_KIND.get(label), value.strip(), false);
            }
        }
        for (String label : APP_LABELS) {
            String value = labels.get(label);
            if (value != null && !value.isBlank()) {
                // A scrape job called "prometheus" is not a workload; the
                // internal jobs are filtered out rather than turned into a
                // restart target that cannot exist.
                if (value.equalsIgnoreCase("prometheus") || value.equalsIgnoreCase("alertmanager")) {
                    continue;
                }
                return new Target(LABEL_KIND.get(label), value.strip(), true);
            }
        }
        String pod = labels.get("pod");
        if (pod != null && !pod.isBlank()) {
            return new Target("Pod", pod.strip(), true);
        }
        // Nothing to aim at. Recorded as unknown so the investigator skips the
        // object reads and no action can be proposed against it.
        return new Target("unknown", alertName, true);
    }

    /**
     * A fingerprint for senders that omit one.
     *
     * <p>Derived from the alert's identity - name, target, namespace - and not
     * from a timestamp, so the same alert re-sent a minute later deduplicates
     * correctly instead of looking like a new incident.
     */
    private static String deriveFingerprint(String name, String targetKey, Map<String, String> labels) {
        String identity = name + "|" + targetKey + "|" + firstNonBlank(labels.get("namespace"), "");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every JVM; this cannot happen, but a
            // fingerprint is not worth failing an alert over.
            return Integer.toHexString(identity.hashCode());
        }
    }

    /** Alert-specific labels win over the group's shared ones. */
    private static Map<String, String> merge(Map<String, String> shared, Map<String, String> specific) {
        Map<String, String> merged = new LinkedHashMap<>();
        if (shared != null) {
            merged.putAll(shared);
        }
        if (specific != null) {
            specific.forEach((key, value) -> {
                if (value != null) {
                    merged.put(key, value);
                }
            });
        }
        return merged;
    }

    /** The first value that is present and not blank, or the fallback. */
    private static String firstNonBlank(String... candidates) {
        for (int i = 0; i < candidates.length - 1; i++) {
            String candidate = candidates[i];
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return candidates[candidates.length - 1];
    }
}
