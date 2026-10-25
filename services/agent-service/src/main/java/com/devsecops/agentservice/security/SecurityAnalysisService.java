package com.devsecops.agentservice.security;

import com.devsecops.agentservice.audit.AuditLog;
import com.devsecops.agentservice.config.AgentProperties;
import com.devsecops.agentservice.diagnosis.Diagnosis;
import com.devsecops.agentservice.diagnosis.DiagnosisEngine;
import com.devsecops.agentservice.diagnosis.EvidenceBundle;
import com.devsecops.agentservice.logs.LogSanitizer;
import com.devsecops.agentservice.logs.PromptInjectionGuard;
import com.devsecops.agentservice.tools.DiagnosticTools;
import com.devsecops.agentservice.tools.ToolResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The {@code POST /analyze-security} pipeline: <em>read the report → flatten it
 * → summarize it → diagnose it</em>.
 *
 * <p>Shorter than the incident loop on purpose. A scanner report is already the
 * finished output of a tool that did the gathering - there is nothing to go and
 * look up - so this service reads one thing, counts it, and reasons about it.
 * Giving it tools to call would be inventing work.
 *
 * <p>Two decisions carry the honesty of the endpoint:
 *
 * <ul>
 *   <li><b>It always returns a diagnosis, and {@code accepted} says whether it
 *       is a diagnosis of the report or of the failure to read it.</b> A security
 *       endpoint that answered "no vulnerabilities" when it could not open the
 *       file would be worse than one that errored, because the caller cannot
 *       tell the difference between a clean image and a missing report. So a
 *       missing file, a file above the size limit and a file that is not JSON all
 *       produce {@code accepted: false} with the reason in the diagnosis and a
 *       confidence of zero.</li>
 *   <li><b>Every finding in the response came out of the report.</b> Nothing is
 *       inferred from a package name, no advisory is looked up, and a severity
 *       the report did not state is reported as {@code UNKNOWN} rather than
 *       guessed. The spec's rule - do not claim a vulnerability exists without
 *       evidence from the report - is enforced by there being nothing in this
 *       class that could produce a finding the report did not contain.</li>
 * </ul>
 *
 * <p>Report text is treated as untrusted throughout: it is sanitized and scanned
 * for injection before it becomes evidence, exactly as a container log is,
 * because a package name or a title is written by whoever built the image.
 */
@Service
public class SecurityAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(SecurityAnalysisService.class);

    private final DiagnosticTools tools;
    private final SecurityReportParser parser;
    private final DiagnosisEngine diagnosisEngine;
    private final LogSanitizer sanitizer;
    private final PromptInjectionGuard injectionGuard;
    private final AgentProperties properties;
    private final AuditLog auditLog;

    public SecurityAnalysisService(DiagnosticTools tools,
                                   SecurityReportParser parser,
                                   DiagnosisEngine diagnosisEngine,
                                   LogSanitizer sanitizer,
                                   PromptInjectionGuard injectionGuard,
                                   AgentProperties properties,
                                   AuditLog auditLog) {
        this.tools = tools;
        this.parser = parser;
        this.diagnosisEngine = diagnosisEngine;
        this.sanitizer = sanitizer;
        this.injectionGuard = injectionGuard;
        this.properties = properties;
        this.auditLog = auditLog;
    }

    /** Runs the pipeline for one request. */
    public SecurityAnalysisResponse analyze(SecurityAnalysisRequest request) {
        AgentProperties.Security security = properties.security();

        String reportPath = request != null && request.reportPath() != null
                && !request.reportPath().isBlank()
                ? request.reportPath().strip()
                : security.reportPath();
        String requestedThreshold = request == null ? null : request.severityThreshold();
        Severity threshold = Severity.parseThreshold(requestedThreshold).orElse(Severity.UNKNOWN);

        audit("analyze-security.request", Map.of(
                "reportPath", reportPath,
                "severityThreshold", threshold.name(),
                "toolsMode", tools.mode()));

        ToolResult read = tools.readSecurityReport(reportPath);
        audit(AuditLog.TOOL_CALL, Map.of(
                "tool", "readSecurityReport",
                "mode", tools.mode(),
                "reportPath", reportPath,
                "ok", read.ok(),
                "chars", read.body().length()));

        if (!read.ok()) {
            // The tool already phrased this for an operator - "the report is N
            // bytes, above the limit", "the path escapes the report directory".
            // Passing it through unchanged is better than a second, vaguer
            // sentence written here.
            log.info("No security report at {}: {}", reportPath, read.error());
            return unusable(reportPath, threshold, read.error());
        }

        SecurityReportParser.Parsed parsed = parser.parse(read.body());
        if (!parsed.usable()) {
            log.info("The report at {} could not be used: {}", reportPath, parsed.problem());
            return unusable(reportPath, threshold, parsed.problem());
        }

        SecurityReport report = parsed.report();
        List<Finding> selected = report.atLeast(threshold);
        boolean truncatedByMax = selected.size() > security.maxFindings();
        List<Finding> listed = truncatedByMax
                ? selected.subList(0, security.maxFindings())
                : selected;
        boolean truncated = truncatedByMax || selected.size() < report.findings().size();

        List<String> notices = notices(report, threshold, listed.size(), truncatedByMax);
        List<String> facts = facts(report, threshold, listed.size());
        List<String> evidence = evidence(report, listed, facts, notices);

        Diagnosis diagnosis = diagnosisEngine.diagnoseEvidence(new EvidenceBundle(
                "security",
                report.artifact(),
                report.highestSeverity(),
                facts,
                evidence));

        SecurityAnalysisResponse response = new SecurityAnalysisResponse(
                true,
                reportPath,
                report.artifact(),
                report.findings().size(),
                report.severityCounts(),
                report.highestSeverity(),
                listed.size(),
                truncated,
                tools.mode(),
                diagnosisEngine.provider(),
                diagnosis,
                listed,
                notices);

        audit(AuditLog.DECISION, decisionFields(response, threshold));
        return response;
    }

    // --- the unusable path -------------------------------------------------

    /**
     * The response for a report that could not be read.
     *
     * <p>{@code accepted: false} and a zero-confidence diagnosis rather than an
     * HTTP error, because the caller needs to distinguish "nothing found" from
     * "could not look" and an error status collapses that into "something went
     * wrong somewhere". The diagnosis carries the same text as a root cause so a
     * caller that only reads {@code diagnosis} still learns what happened.
     */
    private SecurityAnalysisResponse unusable(String reportPath, Severity threshold, String problem) {
        String reason = problem == null || problem.isBlank()
                ? "The security report could not be read."
                : problem;

        Diagnosis diagnosis = new Diagnosis(
                reason,
                List.of(),
                "Confirm the report exists where the agent is configured to look ("
                        + properties.security().reportPath() + "), that it is under "
                        + properties.security().maxReportBytes() + " bytes, and that it is the JSON output of "
                        + "the scanner rather than its human-readable table output.",
                "none",
                0.0);

        SecurityAnalysisResponse response = new SecurityAnalysisResponse(
                false,
                reportPath,
                "",
                0,
                Map.of(),
                "NONE",
                0,
                false,
                tools.mode(),
                diagnosisEngine.provider(),
                diagnosis,
                List.of(),
                List.of(reason));

        // Audited as a decision rather than a tool call: "the agent answered
        // that it could not read the report" is a conclusion worth being able
        // to find later, not just a failed read.
        audit(AuditLog.DECISION, decisionFields(response, threshold));
        return response;
    }

    // --- facts, evidence and notices ---------------------------------------

    /** The agent's own observations, which are trusted because it counted them itself. */
    private static List<String> facts(SecurityReport report, Severity threshold, int listed) {
        List<String> facts = new ArrayList<>();
        facts.add("The report describes " + report.artifact() + ".");
        facts.add("It contains " + report.findings().size() + " finding(s): " + render(report.severityCounts()) + ".");
        facts.add("The highest severity present is " + report.highestSeverity() + ".");
        facts.add(report.fixableCount() + " of " + report.findings().size()
                + " finding(s) name a version that fixes them.");

        long fixableListed = report.atLeast(threshold).stream().filter(Finding::fixAvailable).count();
        facts.add(listed + " finding(s) at or above " + threshold.name() + " are listed, of which "
                + fixableListed + " have a published fix.");
        return facts;
    }

    /**
     * The evidence block, sanitized, scanned and returned.
     *
     * <p>Mutates {@code facts} and {@code notices} to carry what the scan found,
     * which is why it is not a pure function: an injection attempt inside a
     * package name is a fact about the report, and a fact is where a reviewer
     * will actually see it.
     *
     * @return the single sanitized evidence line the engine is given
     */
    private List<String> evidence(SecurityReport report,
                                  List<Finding> listed,
                                  List<String> facts,
                                  List<String> notices) {
        if (listed.isEmpty()) {
            // Saying nothing is the correct evidence for "nothing matched": an
            // empty block would let an engine treat "the report was filtered to
            // nothing" as "the report was empty".
            facts.add("No finding in the report is at or above the requested severity, so none is listed.");
            return List.of();
        }

        String raw = listed.stream().map(Finding::describe).reduce((a, b) -> a + "\n" + b).orElse("");
        String clean = sanitizer.sanitize(raw, properties.github().token(), properties.limits().maxLogChars());
        if (clean.length() != raw.length()) {
            notices.add("Some report text was redacted before it was analyzed; read the report itself for the "
                    + "unredacted values.");
        }

        List<String> flags = injectionGuard.scan("the security report for " + report.artifact(), clean);
        flags.forEach(flag -> facts.add("Possible prompt injection in the report: " + flag));

        return List.of(injectionGuard.fence("SECURITY REPORT " + report.artifact(), clean));
    }

    /** Everything the caller should know about how the report was summarized. */
    private List<String> notices(SecurityReport report,
                                 Severity threshold,
                                 int listed,
                                 boolean truncatedByMax) {
        List<String> notices = new ArrayList<>();

        if (report.empty()) {
            notices.add("The report contains no findings at all. This is a clean scan, not a report the agent "
                    + "failed to read (accepted is true).");
            return notices;
        }

        if (threshold != Severity.UNKNOWN) {
            notices.add("Only findings at or above " + threshold.name() + " are listed; the counts cover the "
                    + "whole report.");
        }
        if (truncatedByMax) {
            notices.add("More findings passed the threshold than agent.security.max-findings ("
                    + properties.security().maxFindings() + "); the " + listed
                    + " most severe are listed, worst first.");
        }
        return notices;
    }

    private static String render(Map<String, Integer> counts) {
        if (counts.isEmpty()) {
            return "none at any severity";
        }
        return counts.entrySet().stream()
                .map(entry -> entry.getValue() + " " + entry.getKey())
                .reduce((left, right) -> left + ", " + right)
                .orElse("none at any severity");
    }

    /** The fields of the closing audit entry - a summary, never the report text. */
    private Map<String, Object> decisionFields(SecurityAnalysisResponse response, Severity threshold) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("reportPath", response.reportPath());
        fields.put("accepted", response.accepted());
        fields.put("artifact", response.artifact());
        fields.put("findingCount", response.findingCount());
        fields.put("highestSeverity", response.highestSeverity());
        fields.put("severityThreshold", threshold.name());
        fields.put("listedFindings", response.listedFindings());
        fields.put("truncated", response.truncated());
        fields.put("toolsMode", response.toolsMode());
        fields.put("provider", response.provider());
        fields.put("rootCause", response.diagnosis().rootCause());
        fields.put("recommendedAction", response.diagnosis().recommendedAction());
        fields.put("confidence", response.diagnosis().confidence());
        return fields;
    }

    /** Convenience so every audit call in this class has the same shape. */
    private void audit(String event, Map<String, ?> fields) {
        auditLog.record(event, fields);
    }
}
