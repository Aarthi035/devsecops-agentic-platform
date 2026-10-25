package com.devsecops.agentservice.security;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Turns a scanner's JSON into a {@link SecurityReport}.
 *
 * <p>Reads Trivy's schema - {@code ArtifactName} and a {@code Results} array of
 * {@code Target}/{@code Vulnerabilities} - and nothing else. The agent's own
 * {@link Finding} is a flattened view rather than Trivy's shape, so the day
 * another scanner is read the response does not change with it.
 *
 * <p><b>The report is untrusted.</b> It is a file the agent reads off a volume,
 * produced by a tool run against an image, and every string in it is attacker-
 * influenced in exactly the way the layer names are - a package name can contain
 * anything the person who built the image chose. So this class extracts text and
 * nothing more: it does not interpret, does not follow a field that looks like an
 * instruction, and does not invent a finding that is not in the file. Sanitizing
 * and the injection scan happen in the service, on the lines that become
 * evidence, so a report cannot reach a model unfiltered.
 *
 * <p>{@link #parse} returns a {@link Parsed} rather than throwing, for the same
 * reason {@code DiagnosticTools} returns a {@code ToolResult}: "this report is
 * not readable" is an outcome the caller must report, not an error that should
 * become a 500. It is also the outcome that must never be silently rendered as
 * "no vulnerabilities found" - hence the explicit problem string.
 */
@Component
public class SecurityReportParser {

    /** Descending, so the counts map reads in the order a reader cares about. */
    private static final List<Severity> BY_SEVERITY = List.of(
            Severity.CRITICAL, Severity.HIGH, Severity.MEDIUM, Severity.LOW, Severity.UNKNOWN);

    private final ObjectMapper objectMapper;

    public SecurityReportParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * A parsed report, or the reason there is not one.
     *
     * <p>{@code problem} is phrased for whoever reads the response - it is the
     * whole explanation a caller gets for why a security question could not be
     * answered, so it names what was wrong with the file rather than a stack
     * trace or a bare "parse error".
     */
    public record Parsed(SecurityReport report, String problem) {

        static Parsed of(SecurityReport report) {
            return new Parsed(report, null);
        }

        static Parsed unusable(String problem) {
            return new Parsed(null, problem);
        }

        /** Whether a report was produced. */
        public boolean usable() {
            return report != null;
        }
    }

    /** Parses {@code json}, or explains why it cannot be used. */
    public Parsed parse(String json) {
        if (json == null || json.isBlank()) {
            return Parsed.unusable("The report is empty.");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (JacksonException e) {
            return Parsed.unusable("The report is not valid JSON (" + e.getOriginalMessage() + ").");
        }
        if (root == null || !root.isObject()) {
            return Parsed.unusable("The report is not a JSON object, so it is not a scanner report.");
        }
        if (!root.has("Results")) {
            return Parsed.unusable("The report has no 'Results' array, so it is not a Trivy report.");
        }

        List<Finding> findings = new ArrayList<>();
        for (JsonNode result : root.path("Results")) {
            String target = text(result, "Target");
            for (JsonNode vulnerability : result.path("Vulnerabilities")) {
                findings.add(toFinding(vulnerability, target));
            }
        }

        String artifact = text(root, "ArtifactName");
        return Parsed.of(new SecurityReport(
                artifact.isBlank() ? "(unnamed artifact)" : artifact,
                findings,
                countBySeverity(findings),
                highestSeverity(findings)));
    }

    /**
     * One Trivy vulnerability as a {@link Finding}.
     *
     * <p>Nothing is defaulted to a made-up value: a missing severity stays
     * {@code UNKNOWN} and a missing id stays blank, so the response shows a gap
     * in the report instead of filling it. {@code Target} is carried in because
     * it is the only thing that says <em>which</em> image layer or bundle a
     * finding came from, and remediation differs between an OS package and a jar.
     */
    private static Finding toFinding(JsonNode vulnerability, String target) {
        return new Finding(
                text(vulnerability, "VulnerabilityID"),
                Severity.parse(text(vulnerability, "Severity")).name(),
                text(vulnerability, "PkgName"),
                text(vulnerability, "InstalledVersion"),
                text(vulnerability, "FixedVersion"),
                text(vulnerability, "Title"),
                target);
    }

    /** Counts by severity, worst first, leaving out the severities that are absent. */
    private static Map<String, Integer> countBySeverity(List<Finding> findings) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Severity severity : BY_SEVERITY) {
            int count = 0;
            for (Finding finding : findings) {
                if (Severity.parse(finding.severity()) == severity) {
                    count++;
                }
            }
            if (count > 0) {
                counts.put(severity.name(), count);
            }
        }
        return counts;
    }

    private static String highestSeverity(List<Finding> findings) {
        Severity worst = null;
        for (Finding finding : findings) {
            Severity severity = Severity.parse(finding.severity());
            if (worst == null || severity.rank() > worst.rank()) {
                worst = severity;
            }
        }
        return worst == null ? "NONE" : worst.name();
    }

    /**
     * A field as text.
     *
     * <p>{@code asText} on a missing node gives {@code ""}, and on a non-textual
     * node gives its JSON rendering - both of which are what the caller wants,
     * because an unreadable field should surface as content rather than abort the
     * whole report over one malformed entry.
     */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("").strip();
    }
}
