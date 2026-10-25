package com.devsecops.agentservice.incident;

import com.devsecops.agentservice.audit.AuditLog;
import com.devsecops.agentservice.config.AgentProperties;
import com.devsecops.agentservice.diagnosis.EvidenceBundle;
import com.devsecops.agentservice.logs.LogSanitizer;
import com.devsecops.agentservice.logs.PromptInjectionGuard;
import com.devsecops.agentservice.tools.DiagnosticTools;
import com.devsecops.agentservice.tools.ToolBudget;
import com.devsecops.agentservice.tools.ToolResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The Phase 9 loop: <em>plan → call → observe → reason</em>, bounded.
 *
 * <p>Where Phase 8's {@code CiAnalysisService} walks a fixed sequence, this
 * picks a plan per subject and stops when the budget or the evidence runs out.
 * The plan for an alert is short and deliberate:
 *
 * <ol>
 *   <li><b>Is it up?</b> {@code up{service="..."}} - a one-line answer to "is
 *       this a scrape failure or a working service behaving badly".</li>
 *   <li><b>What does the object say?</b> {@code kubectl describe} on the target,
 *       which returns its state <em>and</em> the events about it. This is where
 *       {@code CrashLoopBackOff}, {@code OOMKilled} and probe failures show
 *       up.</li>
 *   <li><b>What does it say for itself?</b> A Loki range query over the
 *       namespace or pod selector.</li>
 *   <li><b>Only if Loki had nothing,</b> read the container log directly. The
 *       two answer the same question through different paths, so fetching both
 *       on every incident would spend a step to learn nothing.</li>
 *   <li><b>If budget remains,</b> the error-rate metric, which tells a
 *       crash-loop apart from a service that is up but failing requests.</li>
 * </ol>
 *
 * <p>Steps 4 and 5 are conditional, which is the "reason" in the loop: each
 * step's inclusion depends on what the previous ones observed.
 *
 * <p>Three rules hold throughout:
 *
 * <ul>
 *   <li><b>The budget is shared by the whole request.</b> {@code
 *       agent.limits.max-tool-calls} is spent across every alert in the group,
 *       not per alert - otherwise a large alert group multiplies the limit.</li>
 *   <li><b>A failed tool is an observation, not an error.</b> It becomes a fact
 *       ("a reading is missing: Prometheus did not answer") so a diagnosis built
 *       on four of five readings says so.</li>
 *   <li><b>Nothing reaches an engine unsanitized.</b> Every body passes through
 *       {@link LogSanitizer} - including the agent's own credentials, which are
 *       redacted by literal value - and then through {@link
 *       PromptInjectionGuard}, whose flags join the facts.</li>
 * </ul>
 */
@Service
public class IncidentInvestigator {

    private static final Logger log = LoggerFactory.getLogger(IncidentInvestigator.class);

    private final DiagnosticTools tools;
    private final LogSanitizer sanitizer;
    private final PromptInjectionGuard injectionGuard;
    private final AgentProperties properties;
    private final AuditLog auditLog;

    public IncidentInvestigator(DiagnosticTools tools,
                                LogSanitizer sanitizer,
                                PromptInjectionGuard injectionGuard,
                                AgentProperties properties,
                                AuditLog auditLog) {
        this.tools = tools;
        this.sanitizer = sanitizer;
        this.injectionGuard = injectionGuard;
        this.properties = properties;
        this.auditLog = auditLog;
    }

    /** The tool mode in force, for the response and the audit trail. */
    public String mode() {
        return tools.mode();
    }

    /**
     * Investigates one alert group.
     *
     * @param subject the group's key, used as the bundle's subject
     * @param status  {@code firing} or {@code resolved}
     * @param alerts  the normalised alerts, already limited to {@code
     *                agent.incident.max-alerts}
     */
    public Investigation investigate(String subject, String status, List<NormalizedAlert> alerts) {
        ToolBudget budget = new ToolBudget(properties.limits().maxToolCalls());
        Collector collected = new Collector();

        int investigated = 0;
        for (NormalizedAlert alert : alerts) {
            if (!budget.hasRemaining()) {
                collected.notices.add("Stopped before investigating '" + alert.name()
                        + "': the tool budget of " + budget.limit() + " calls was spent.");
                break;
            }
            investigateOne(alert, budget, collected);
            investigated++;
        }
        if (investigated < alerts.size()) {
            collected.notices.add("Investigated " + investigated + " of " + alerts.size()
                    + " alert(s); the rest are in the same notification but exceeded the tool budget.");
        }

        EvidenceBundle bundle = new EvidenceBundle(
                "alert", subject, status, collected.facts, collected.evidence);

        log.info("Investigated {} alert(s) with {} of {} tool call(s){}.", investigated,
                budget.used(), budget.limit(), budget.hasRemaining() ? "" : " (budget exhausted)");

        return new Investigation(bundle, collected.readings, budget.used(), budget.limit(),
                !budget.hasRemaining(), collected.notices);
    }

    private void investigateOne(NormalizedAlert alert, ToolBudget budget, Collector collected) {
        collected.facts.add("alert=" + alert.name() + " severity=" + alert.severity()
                + " status=" + alert.status() + " subject=" + alert.subject());
        collected.facts.add("summary: " + alert.summary());
        collected.facts.addAll(alert.descriptionLines());
        if (alert.inferred()) {
            collected.facts.add("the target was inferred from the alert's labels, not from a verified read; "
                    + "confirm it before acting on it.");
        }

        boolean targetReadable = !"unknown".equals(alert.workloadKind());
        String service = alert.workloadName();

        // 1 - is it being scraped at all?
        call(budget, collected, "queryPrometheus", "up{service=\"" + service + "\"}",
                () -> tools.queryPrometheus("up{service=\"" + service + "\"}"), false);

        // 2 - the object's own state and the events about it.
        if (targetReadable) {
            call(budget, collected, "kubectlReadOnly",
                    "describe " + alert.subject(),
                    () -> tools.kubectlDescribe(alert.workloadKind(), alert.namespace(), alert.workloadName()),
                    true);
        }

        // 3 - what the application logged.
        ToolResult logs = call(budget, collected, "queryLoki",
                "logs " + alert.logSelector(),
                () -> tools.queryLoki(alert.logSelector(), properties.tools().maxLogLines()),
                true);

        // 4 - the log endpoint, but only when Loki had nothing to show. Step 4
        // depends on what step 3 observed; that dependency is the loop's
        // reasoning, not a fixed script.
        boolean lokiWasEmpty = logs != null && logs.ok() && logs.body().isBlank();
        if (targetReadable && lokiWasEmpty) {
            call(budget, collected, "kubectlReadOnly",
                    "logs of pod " + alert.namespace() + "/" + alert.workloadName(),
                    () -> tools.kubectlLogs(alert.namespace(), alert.workloadName(), null),
                    true);
        }

        // 5 - the error rate, which separates "down" from "up and failing".
        call(budget, collected, "queryPrometheus",
                "server error rate for " + service,
                () -> tools.queryPrometheus("sum(rate(http_server_requests_seconds_count{service=\""
                        + service + "\",status=~\"5..\"}[5m]))"),
                false);
    }

    /**
     * Runs one step, or reports that it was skipped.
     *
     * <p>The single place where a tool call is made, so the budget, the
     * sanitizer, the injection scan and the audit entry cannot be forgotten by
     * a step added later. {@code addEvidence} is false for the metric readings:
     * a Prometheus answer is a number, and the summary line already carries it,
     * so pasting the series into the evidence block only spends context.
     *
     * @return the tool result, or null when the step was skipped for budget
     */
    private ToolResult call(ToolBudget budget,
                            Collector collected,
                            String tool,
                            String what,
                            Supplier<ToolResult> step,
                            boolean addEvidence) {
        if (!budget.hasRemaining()) {
            collected.notices.add("Skipped '" + what + "': the step budget of " + budget.limit()
                    + " tool calls was reached.");
            return null;
        }

        ToolResult result = step.get();
        budget.spend();
        collected.readings.add(Reading.of(tool, result.ok(), result.summary()));

        if (result.ok()) {
            collected.facts.add(result.summary());
            if (!result.body().isBlank()) {
                String clean = sanitize(result.body());
                if (addEvidence) {
                    collected.addEvidence(clean, properties.limits().maxLogChars());
                }
                collected.facts.addAll(injectionGuard.scan(tool, clean));
            }
        } else {
            collected.facts.add("a reading is missing - " + result.summary());
        }

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("tool", tool);
        fields.put("what", what);
        fields.put("mode", tools.mode());
        fields.put("ok", result.ok());
        fields.put("summary", result.summary());
        fields.put("bodyChars", result.body().length());
        auditLog.record(AuditLog.TOOL_CALL, fields);
        return result;
    }

    /**
     * Redacts the agent's own credentials by literal value, then the known
     * secret shapes.
     *
     * <p>A tool's answer can legitimately contain a token - a Kubernetes object
     * read includes the {@code type: kubernetes.io/service-account-token} Secret
     * if the agent is pointed at one, and a log can echo a header - so the
     * values the agent itself holds are removed first, without relying on their
     * shape.
     */
    private String sanitize(String body) {
        String clean = body;
        for (String secret : ownCredentials()) {
            clean = sanitizer.redactKnownSecret(clean, secret);
        }
        return sanitizer.sanitize(clean, null, properties.limits().maxLogChars());
    }

    private List<String> ownCredentials() {
        List<String> secrets = new ArrayList<>();
        addIfPresent(secrets, properties.github().token());
        addIfPresent(secrets, properties.tools().kubernetesToken());
        addIfPresent(secrets, properties.tools().prometheusToken());
        addIfPresent(secrets, properties.approval().token());
        return secrets;
    }

    private static void addIfPresent(List<String> secrets, String candidate) {
        if (candidate != null && !candidate.isBlank()) {
            secrets.add(candidate);
        }
    }

    /**
     * The evidence being assembled, with a running size.
     *
     * <p>A mutable holder rather than a growing list of parameters, and not a
     * record, because the assembled pieces are interdependent: the character
     * count decides whether the next body fits, so they have to be updated
     * together.
     */
    private static final class Collector {

        private final List<String> facts = new ArrayList<>();
        private final List<String> evidence = new ArrayList<>();
        private final List<Reading> readings = new ArrayList<>();
        private final List<String> notices = new ArrayList<>();
        private int evidenceChars;

        /**
         * Adds one body to the evidence, up to a total budget.
         *
         * <p>Bounded as a whole rather than per body: five readings of
         * {@code max-log-chars} each would put five times the size limit into
         * the prompt, which is precisely the failure the limit exists to
         * prevent.
         */
        void addEvidence(String text, int cap) {
            if (evidenceChars >= cap) {
                notices.add("Further evidence was dropped: the evidence block reached its "
                        + cap + "-character limit.");
                return;
            }
            evidence.add(text);
            evidenceChars += text.length();
        }
    }
}
