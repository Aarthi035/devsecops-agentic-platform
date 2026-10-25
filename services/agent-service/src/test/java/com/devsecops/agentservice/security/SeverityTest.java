package com.devsecops.agentservice.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The ordering, because everything downstream is a sort by it.
 *
 * <p>Trivy's severity strings arrive from a file the agent does not control, so
 * the two cases worth pinning are the ones a report actually contains: a
 * severity in some unexpected case or spelling, and a severity that is missing
 * altogether. {@code UNKNOWN} ranking below {@code LOW} is the decision that
 * keeps an untriaged finding from reading as worse than a known MEDIUM.
 */
class SeverityTest {

    @Test
    void ranksFromCriticalDownToUnknown() {
        assertThat(Severity.CRITICAL.rank()).isGreaterThan(Severity.HIGH.rank());
        assertThat(Severity.HIGH.rank()).isGreaterThan(Severity.MEDIUM.rank());
        assertThat(Severity.MEDIUM.rank()).isGreaterThan(Severity.LOW.rank());
        assertThat(Severity.LOW.rank()).isGreaterThan(Severity.UNKNOWN.rank());
    }

    @Test
    void parsesTheTextAScannerWritesWhateverTheCase() {
        assertThat(Severity.parse("CRITICAL")).isEqualTo(Severity.CRITICAL);
        assertThat(Severity.parse("high")).isEqualTo(Severity.HIGH);
        assertThat(Severity.parse("  Medium  ")).isEqualTo(Severity.MEDIUM);
    }

    @Test
    void treatsAMissingOrUnrecognisedSeverityAsUnknownRatherThanGuessing() {
        assertThat(Severity.parse(null)).isEqualTo(Severity.UNKNOWN);
        assertThat(Severity.parse("")).isEqualTo(Severity.UNKNOWN);
        assertThat(Severity.parse("urgent")).isEqualTo(Severity.UNKNOWN);
    }

    @Test
    void anUnknownSeverityIsBelowEveryKnownOne() {
        assertThat(Severity.UNKNOWN.atLeast(Severity.LOW)).isFalse();
        assertThat(Severity.LOW.atLeast(Severity.UNKNOWN)).isTrue();
        assertThat(Severity.CRITICAL.atLeast(Severity.CRITICAL)).isTrue();
    }

    @Test
    void parsesAThresholdOnlyWhenTheCallerSuppliedOne() {
        assertThat(Severity.parseThreshold(null)).isEmpty();
        assertThat(Severity.parseThreshold("  ")).isEmpty();
        assertThat(Severity.parseThreshold("critical")).contains(Severity.CRITICAL);
    }
}
