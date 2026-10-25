package com.devsecops.agentservice.security;

import java.util.Locale;
import java.util.Optional;

/**
 * A scanner severity, in the order that matters.
 *
 * <p>Trivy reports severities as strings, and the whole point of reading them is
 * to sort by them - "the worst thing in here is a CRITICAL" is the first
 * sentence of any useful vulnerability summary. An enum with an explicit rank is
 * how that sort is done once rather than by a chain of string comparisons in
 * three places.
 *
 * <p>{@link #UNKNOWN} is ranked below {@link #LOW} rather than above it: a
 * finding with no severity is one nobody has triaged, and putting it above a
 * known MEDIUM would make an untriaged report look worse than a triaged one.
 */
public enum Severity {

    CRITICAL(4),
    HIGH(3),
    MEDIUM(2),
    LOW(1),
    UNKNOWN(0);

    private final int rank;

    Severity(int rank) {
        this.rank = rank;
    }

    /** Higher is worse. */
    public int rank() {
        return rank;
    }

    /** Whether this severity is at or above another. */
    public boolean atLeast(Severity other) {
        return rank >= other.rank();
    }

    /** Parses Trivy's text, which is upper-case but arrives from an untrusted file. */
    public static Severity parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        String candidate = raw.strip().toUpperCase(Locale.ROOT);
        for (Severity severity : values()) {
            if (severity.name().equals(candidate)) {
                return severity;
            }
        }
        return UNKNOWN;
    }

    /** Parses a threshold, if the caller supplied one. */
    public static Optional<Severity> parseThreshold(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(parse(raw));
    }
}
