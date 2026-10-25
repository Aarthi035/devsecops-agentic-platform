package com.devsecops.agentservice.incident;

import com.devsecops.agentservice.action.ActionProposal;
import com.devsecops.agentservice.action.ActionType;
import com.devsecops.agentservice.action.ApprovalService;
import com.devsecops.agentservice.audit.AuditLog;
import com.devsecops.agentservice.config.AgentProperties;
import com.devsecops.agentservice.diagnosis.Diagnosis;
import com.devsecops.agentservice.diagnosis.DiagnosisEngine;
import com.devsecops.agentservice.logs.LogSanitizer;
import com.devsecops.agentservice.tools.DiagnosticTools;
import com.devsecops.agentservice.tools.ToolResult;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The {@code POST /alert} pipeline: <em>normalise → deduplicate → investigate →
 * diagnose → propose</em>.
 *
 * <p>It is short because the two hard parts are elsewhere: {@link
 * IncidentInvestigator} runs the bounded read loop, and {@link ApprovalService}
 * owns the decision to act. What is decided here is what an incoming
 * notification is allowed to cause.
 *
 * <p><b>Nothing in this class executes anything.</b> The most it does is ask
 * {@link ApprovalService} to register a proposal, which records a suggestion and
 * an audit entry. Execution requires a token that this request never carries.
 * That is the spec's rule - a diagnosis must not perform state-changing actions -
 * expressed as a property of the code rather than a promise.
 *
 * <p><b>Deduplication is per subject and per status, not per payload.</b>
 * Alertmanager repeats an unacknowledged notification, and a re-send carries a
 * new timestamp but the same alert: those are one incident. A {@code resolved}
 * notification after a {@code firing} one is <em>not</em> a duplicate, because
 * it is the news that the incident ended. The key therefore includes the status,
 * and a repeat within {@code agent.incident.dedup-window-seconds} returns the
 * earlier diagnosis with {@code deduplicated: true} instead of paying for the
 * investigation twice.
 *
 * <p><b>No action is proposed for a resolved alert.</b> "Restart the workload"
 * is nonsense advice about a condition that has already cleared, and a
 * proposal is a request for a human decision that should not be spent on one.
 */
@Service
public class IncidentAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(IncidentAnalysisService.class);

    /**
     * How many past incidents are remembered for deduplication.
     *
     * <p>Bounded so a long-running pod cannot accumulate a map of every alert it
     * has ever seen. Larger than {@code incident.max-alerts} on purpose: this is
     * a history across requests, not a limit on one payload.
     */
    private static final int DEDUP_HISTORY = 256;

    /** Long enough to be a readable reason, short enough to be a reason. */
    private static final int MAX_REASON_CHARS = 300;

    private final AlertNormalizer normalizer;
    private final IncidentInvestigator investigator;
    private final DiagnosisEngine diagnosisEngine;
    private final ApprovalService approvalService;
    private final DiagnosticTools tools;
    private final LogSanitizer sanitizer;
    private final AgentProperties properties;
    private final AuditLog auditLog;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** fingerprint|status -> the diagnosis already reached for it, and when. */
    private final Map<String, Remembered> remembered = new ConcurrentHashMap<>();

    private record Remembered(Instant at, String subject, Diagnosis diagnosis, int alertCount) {
    }

    public IncidentAnalysisService(AlertNormalizer normalizer,
                                   IncidentInvestigator investigator,
                                   DiagnosisEngine diagnosisEngine,
                                   ApprovalService approvalService,
                                   DiagnosticTools tools,
                                   LogSanitizer sanitizer,
                                   AgentProperties properties,
                                   AuditLog auditLog,
                                   ObjectMapper objectMapper,
                                   Clock clock) {
        this.normalizer = normalizer;
        this.investigator = investigator;
        this.diagnosisEngine = diagnosisEngine;
        this.approvalService = approvalService;
        this.tools = tools;
        this.sanitizer = sanitizer;
        this.properties = properties;
        this.auditLog = auditLog;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** Runs the pipeline for one Alertmanager notification. */
    public IncidentAnalysisResponse analyze(AlertmanagerWebhook webhook) {
        String groupKey = groupKey(webhook);
        String status = webhook.status() == null ? "" : webhook.status().strip().toLowerCase(Locale.ROOT);

        audit("alert.request", Map.of(
                "groupKey", groupKey,
                "status", status,
                "sender", String.valueOf(webhook.receiver()),
                "sentAlerts", webhook.alerts() == null ? 0 : webhook.alerts().size()));

        List<NormalizedAlert> alerts = normalizer.normalize(webhook);
        int dropped = normalizer.droppedCount(webhook);

        String dedupKey = dedupKey(alerts, status);
        Optional<IncidentAnalysisResponse> duplicate = deduplicate(dedupKey, groupKey, status, alerts);
        if (duplicate.isPresent()) {
            return duplicate.get();
        }

        String subject = alerts.get(0).subject();
        Investigation investigation = investigator.investigate(subject, status, alerts);
        Diagnosis diagnosis = diagnosisEngine.diagnoseEvidence(investigation.bundle());

        List<String> notices = new ArrayList<>(investigation.notices());
        if (dropped > 0) {
            notices.add(dropped + " alert(s) were not investigated: the group carries more than "
                    + "agent.incident.max-alerts (" + properties.incident().maxAlerts() + ").");
        }
        if (!investigation.budgetExhausted() && investigation.toolCalls() == 0) {
            notices.add("No tool call was made; the diagnosis rests on the alert's own labels and annotations.");
        }

        Proposal proposal = propose(diagnosis, alerts.get(0), status, notices);

        IncidentAnalysisResponse response = new IncidentAnalysisResponse(
                true,
                false,
                groupKey,
                status,
                subject,
                alerts.size(),
                alerts.size(),
                alerts,
                investigation.bundle().facts(),
                investigation.readings(),
                investigation.toolCalls() + proposal.extraToolCalls(),
                investigation.toolBudget(),
                investigation.budgetExhausted(),
                tools.mode(),
                approvalService.executorMode(),
                approvalService.approvalsEnabled(),
                diagnosisEngine.provider(),
                diagnosis,
                proposal.action(),
                notices);

        remember(dedupKey, new Remembered(clock.instant(), subject, diagnosis, alerts.size()));
        audit(AuditLog.DECISION, decisionFields(response, proposal));
        return response;
    }

    // --- deduplication -----------------------------------------------------

    /**
     * The identity of an incident: its subjects and its status.
     *
     * <p>Built from the normalised alerts' fingerprints rather than from the
     * payload's {@code groupKey}, because Alertmanager derives the group key from
     * the route's grouping labels - two groups routed differently are the same
     * incident, and two repeats of one group have the same key either way. The
     * fingerprints are sorted so the same set in a different order is the same
     * key.
     */
    private static String dedupKey(List<NormalizedAlert> alerts, String status) {
        String fingerprints = alerts.stream()
                .map(NormalizedAlert::fingerprint)
                .sorted()
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        return status + "|" + fingerprints;
    }

    /** The earlier answer for this incident, if it is still inside the window. */
    private Optional<IncidentAnalysisResponse> deduplicate(String dedupKey,
                                                           String groupKey,
                                                           String status,
                                                           List<NormalizedAlert> alerts) {
        Remembered previous = remembered.get(dedupKey);
        if (previous == null) {
            return Optional.empty();
        }

        Duration window = Duration.ofSeconds(Math.max(0, properties.incident().dedupWindowSeconds()));
        Instant now = clock.instant();
        if (window.isZero() || Duration.between(previous.at(), now).compareTo(window) > 0) {
            // Outside the window: the incident has recurred, and a recurrence is
            // a new event that deserves its own investigation.
            remembered.remove(dedupKey);
            return Optional.empty();
        }

        String subject = alerts.get(0).subject();
        log.info("Ignoring a repeat notification for '{}' from {}: already analyzed at {}.",
                subject, groupKey, previous.at());

        List<String> notices = new ArrayList<>();
        notices.add("This notification repeats an incident already analyzed at " + previous.at()
                + ", inside the " + properties.incident().dedupWindowSeconds()
                + "-second deduplication window. It was not investigated again and no second action was "
                + "proposed; the diagnosis below is the earlier one, unchanged.");
        if (!subject.equals(previous.subject())) {
            // Same fingerprints, different normalised subject: worth knowing,
            // because it means the label-to-target mapping is not stable.
            notices.add("The subject now resolves to '" + subject + "' but resolved to '"
                    + previous.subject() + "' when it was analyzed.");
        }

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("groupKey", groupKey);
        fields.put("status", status);
        fields.put("subject", subject);
        fields.put("deduplicated", true);
        fields.put("firstSeenAt", previous.at().toString());
        auditLog.record(AuditLog.DECISION, fields);

        return Optional.of(new IncidentAnalysisResponse(
                true,
                true,
                groupKey,
                status,
                subject,
                Math.max(alerts.size(), previous.alertCount()),
                alerts.size(),
                alerts,
                List.of(),
                List.of(),
                0,
                properties.limits().maxToolCalls(),
                false,
                tools.mode(),
                approvalService.executorMode(),
                approvalService.approvalsEnabled(),
                diagnosisEngine.provider(),
                previous.diagnosis(),
                null,
                notices));
    }

    private void remember(String dedupKey, Remembered value) {
        remembered.put(dedupKey, value);
        if (remembered.size() <= DEDUP_HISTORY) {
            return;
        }
        remembered.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(
                        java.util.Comparator.comparing(Remembered::at)))
                .limit(remembered.size() - DEDUP_HISTORY)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(remembered::remove);
    }

    // --- proposing an action -----------------------------------------------

    /** What was proposed, and what it cost to decide the parameters. */
    private record Proposal(ActionProposal action, int extraToolCalls) {

        static Proposal none(int extraToolCalls, List<String> notices, String why) {
            if (why != null) {
                notices.add(why);
            }
            return new Proposal(null, extraToolCalls);
        }
    }

    /**
     * Registers a proposal when - and only when - the diagnosis recommends one
     * and its parameters can be measured.
     *
     * <p>Three gates, all deliberate:
     *
     * <ol>
     *   <li><b>The diagnosis has to recommend an action.</b> The engine's
     *       {@code recommended_action} is {@code none}, {@code retry} or
     *       {@code restart}/{@code scale}/{@code rollback}. Only the last three
     *       map to an {@link ActionType}; {@code retry} deliberately does not,
     *       because "run it again" is not a state change the agent should be
     *       asking a human to authorise.</li>
     *   <li><b>The action has to apply to the target's kind.</b> Rolling back a
     *       DaemonSet is refused by {@link ActionType#supportsKind}, and the
     *       reason is recorded rather than the request failing.</li>
     *   <li><b>{@code scale} needs a number, and the number has to be
     *       read.</b> A replica count is not something to guess: the service
     *       reads the workload's {@code spec.replicas} and proposes one more.
     *       That read is the only extra tool call this class makes, it happens at
     *       most once per incident, and it is reported in the response's counts.
     *       If the read fails, no proposal is made and a notice says why - an
     *       unparameterised guess is worse than no suggestion.</li>
     * </ol>
     */
    private Proposal propose(Diagnosis diagnosis,
                             NormalizedAlert alert,
                             String status,
                             List<String> notices) {
        if ("resolved".equals(status)) {
            return Proposal.none(0, notices, "The alert has resolved, so no action is proposed.");
        }

        Optional<ActionType> wanted = actionFor(diagnosis.recommendedAction());
        if (wanted.isEmpty()) {
            return Proposal.none(0, notices,
                    "The diagnosis recommends '" + diagnosis.recommendedAction() + "', which is not a "
                            + "state-changing action the agent proposes.");
        }
        ActionType type = wanted.get();

        if ("unknown".equals(alert.workloadKind())) {
            return Proposal.none(0, notices,
                    "No action is proposed: the alert does not name a workload the agent can identify, so "
                            + "there is nothing to aim '" + type.wireName() + "' at.");
        }
        if (!type.supportsKind(alert.workloadKind())) {
            return Proposal.none(0, notices,
                    "No action is proposed: " + type.wireName() + " does not apply to a " + alert.workloadKind()
                            + " (it applies to " + String.join(", ", type.supportedKinds()) + ").");
        }

        int extraToolCalls = 0;
        Map<String, Object> parameters = Map.of();
        if (type == ActionType.SCALE_WORKLOAD) {
            ToolResult current = tools.kubectlGet(alert.workloadKind(), alert.namespace(), alert.workloadName());
            extraToolCalls = 1;
            auditLog.record(AuditLog.TOOL_CALL, Map.of(
                    "tool", "kubectlReadOnly",
                    "what", "spec.replicas of " + alert.subject(),
                    "mode", tools.mode(),
                    "ok", current.ok(),
                    "summary", current.summary()));

            Optional<Integer> replicas = current.ok() ? replicasOf(current.body()) : Optional.empty();
            if (replicas.isEmpty()) {
                return Proposal.none(extraToolCalls, notices,
                        "No action is proposed: the current replica count of " + alert.subject()
                                + " could not be read, and the agent will not propose a number it has not "
                                + "measured.");
            }
            int target = replicas.get() + 1;
            if (target > 20) {
                return Proposal.none(extraToolCalls, notices,
                        "No action is proposed: " + alert.subject() + " is already at " + replicas.get()
                                + " replicas, and the agent will not scale past the limit it enforces (20).");
            }
            parameters = Map.of("replicas", target);
            notices.add("The proposal scales to " + target + " replica(s), one more than the " + replicas.get()
                    + " currently configured.");
        }

        List<String> evidence = diagnosis.evidence().isEmpty()
                ? List.of("The " + alert.name() + " alert is " + alert.status() + ".")
                : diagnosis.evidence();

        try {
            ActionProposal proposal = approvalService.propose(
                    type,
                    alert.workloadKind(),
                    alert.namespace(),
                    alert.workloadName(),
                    parameters,
                    reason(diagnosis),
                    evidence,
                    alert.subject());
            return new Proposal(proposal, extraToolCalls);
        } catch (com.devsecops.agentservice.error.AgentRequestException e) {
            // The proposal was refused by its own validation. That is a fact
            // about the suggestion, not a failure of the request: the diagnosis
            // is still the answer the caller asked for.
            notices.add("No action was proposed: " + e.getMessage());
            return new Proposal(null, extraToolCalls);
        }
    }

    /** The engine's {@code recommended_action} as one of the three actions. */
    private static Optional<ActionType> actionFor(String recommended) {
        if (recommended == null) {
            return Optional.empty();
        }
        return switch (recommended.strip().toLowerCase(Locale.ROOT)) {
            case "restart" -> Optional.of(ActionType.RESTART_WORKLOAD);
            case "scale" -> Optional.of(ActionType.SCALE_WORKLOAD);
            case "rollback" -> Optional.of(ActionType.ROLLBACK_DEPLOYMENT);
            default -> Optional.empty();
        };
    }

    /**
     * The reason attached to the proposal.
     *
     * <p>The diagnosis's own root cause, because that is the sentence the human
     * approver needs to read, cut to a length a notification can render. A
     * truncated reason is still a true reason; a synthesised one would not be.
     */
    private static String reason(Diagnosis diagnosis) {
        String rootCause = diagnosis.rootCause() == null ? "" : diagnosis.rootCause().strip();
        if (rootCause.isEmpty()) {
            return "The agent recommends this action but recorded no root cause.";
        }
        return rootCause.length() <= MAX_REASON_CHARS
                ? rootCause
                : rootCause.substring(0, MAX_REASON_CHARS) + "...";
    }

    /** {@code spec.replicas} out of an API object, if it is there and is a number. */
    private Optional<Integer> replicasOf(String body) {
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode replicas = objectMapper.readTree(body).path("spec").path("replicas");
            return replicas.isNumber() ? Optional.of(replicas.asInt()) : Optional.empty();
        } catch (JacksonException e) {
            // The read succeeded but was not JSON, which is worth knowing and
            // not worth failing an alert over.
            log.warn("Could not read a replica count from the API's answer: {}", e.getOriginalMessage());
            return Optional.empty();
        }
    }

    // --- plumbing ----------------------------------------------------------

    /** Alertmanager's group key, or one derived from the alerts when it is absent. */
    private static String groupKey(AlertmanagerWebhook webhook) {
        if (webhook == null) {
            return "";
        }
        if (webhook.groupKey() != null && !webhook.groupKey().isBlank()) {
            return webhook.groupKey();
        }
        if (webhook.alerts() == null || webhook.alerts().isEmpty()) {
            return "";
        }
        return webhook.alerts().stream()
                .map(alert -> alert.fingerprint() == null ? "" : alert.fingerprint())
                .sorted()
                .reduce((left, right) -> left + "," + right)
                .orElse("");
    }

    /** The fields of the closing audit entry - a summary, never the log text. */
    private Map<String, Object> decisionFields(IncidentAnalysisResponse response, Proposal proposal) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("groupKey", response.groupKey());
        fields.put("status", response.status());
        fields.put("subject", response.subject());
        fields.put("alertCount", response.alertCount());
        fields.put("toolCalls", response.toolCalls());
        fields.put("toolBudget", response.toolBudget());
        fields.put("budgetExhausted", response.budgetExhausted());
        fields.put("toolsMode", response.toolsMode());
        fields.put("executorMode", response.executorMode());
        fields.put("provider", response.provider());
        fields.put("rootCause", response.diagnosis().rootCause());
        fields.put("recommendedAction", response.diagnosis().recommendedAction());
        fields.put("confidence", response.diagnosis().confidence());
        fields.put("proposedActionId", proposal.action() == null ? "" : proposal.action().id());
        fields.put("proposedAction", proposal.action() == null ? "none" : proposal.action().describe());
        return fields;
    }

    /** Convenience so every audit call in this class has the same shape. */
    private void audit(String event, Map<String, ?> fields) {
        auditLog.record(event, fields);
    }
}
