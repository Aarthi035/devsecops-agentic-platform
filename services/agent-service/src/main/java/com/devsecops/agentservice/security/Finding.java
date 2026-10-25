package com.devsecops.agentservice.security;

/**
 * One vulnerability, as reported by the scanner.
 *
 * <p>A flattened view of Trivy's finding rather than its JSON shape, because
 * the agent reads a Trivy report today and the response should not change shape
 * if it reads another scanner tomorrow. Everything here is copied from the
 * report - nothing is inferred, and no finding is reported that the report did
 * not contain.
 *
 * <p>{@code fixAvailable} is the single most actionable field: a vulnerability
 * with a fixed version is a dependency bump, and one without is a mitigation
 * decision. Keeping it explicit means a summary can separate them without
 * re-parsing the version strings.
 */
public record Finding(
        /** The CVE (or the scanner's own id). */
        String id,
        /** {@code CRITICAL}, {@code HIGH}, {@code MEDIUM}, {@code LOW} or {@code UNKNOWN}. */
        String severity,
        /** The affected package. */
        String pkgName,
        /** The version that is installed. */
        String installedVersion,
        /** The version that fixes it, or blank when there is none. */
        String fixedVersion,
        /** What the scanner called the finding. */
        String title,
        /** The image, language bundle or host the finding is in. */
        String target) {

    /** Whether the report named a version that fixes this. */
    public boolean fixAvailable() {
        return fixedVersion != null && !fixedVersion.isBlank();
    }

    /** One line, in the shape a summary and the evidence block both use. */
    public String describe() {
        return (id == null || id.isBlank() ? "unnamed finding" : id)
                + " [" + severity + "] " + pkgName + " " + installedVersion
                + (fixAvailable() ? " -> fixed in " + fixedVersion : " (no fix published yet)")
                + (title == null || title.isBlank() ? "" : ": " + title);
    }
}
