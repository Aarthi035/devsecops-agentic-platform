package com.devsecops.agentservice.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The tracing switch and the cost estimate.
 *
 * <p>Two things are worth pinning down here. The first is that the mode
 * predicates are the only place a mode string is interpreted, so "LANGFUSE" and
 * "langfuse" cannot mean different things in different classes. The second is
 * that the cost estimate is an <em>estimate</em>: it is a round of arithmetic on
 * two configured rates, it never goes negative, and a deployment on a local
 * model can set both rates to zero and get an honest zero rather than a
 * plausible-looking number.
 */
class TracingPropertiesTest {

    private static TracingProperties properties(String mode, String publicKey, String secretKey) {
        return new TracingProperties(mode, "http://langfuse.invalid:3000", publicKey, secretKey,
                5, "release", "test", 0.0002, 0.00002);
    }

    @Test
    void recognisesItsModeRegardlessOfCase() {
        assertThat(properties("langfuse", "", "").langfuse()).isTrue();
        assertThat(properties("LANGFUSE", "", "").langfuse()).isTrue();
        assertThat(properties("log", "", "").logs()).isTrue();
        assertThat(properties("OFF", "", "").langfuse()).isFalse();
        assertThat(properties("OFF", "", "").logs()).isFalse();
    }

    @Test
    void needsBothKeysBeforeItClaimsToHaveCredentials() {
        assertThat(properties("langfuse", "pk", "sk").hasCredentials()).isTrue();
        assertThat(properties("langfuse", "pk", "").hasCredentials()).isFalse();
        assertThat(properties("langfuse", "", "sk").hasCredentials()).isFalse();
        assertThat(properties("langfuse", "   ", "sk").hasCredentials()).isFalse();
        assertThat(properties("langfuse", null, null).hasCredentials()).isFalse();
    }

    @Test
    void estimatesCostFromTheTwoCallCounts() {
        TracingProperties props = properties("log", "", "");

        // 3 model calls at 0.0002 plus 12 tool calls at 0.00002.
        assertThat(props.estimateCostUsd(3, 12)).isEqualTo(0.00084);
        assertThat(props.estimateCostUsd(1, 0)).isEqualTo(0.0002);
        assertThat(props.estimateCostUsd(0, 0)).isZero();
    }

    @Test
    void treatsNegativeCallCountsAsZeroRatherThanCrediting() {
        TracingProperties props = properties("log", "", "");

        assertThat(props.estimateCostUsd(-5, -5)).isZero();
        assertThat(props.estimateCostUsd(-5, 10)).isEqualTo(0.0002);
    }

    @Test
    void aDeploymentOnALocalModelCanReportAnHonestZero() {
        TracingProperties local = new TracingProperties("log", "http://langfuse.invalid:3000", "", "",
                5, "release", "test", 0, 0);

        assertThat(local.estimateCostUsd(10, 100)).isZero();
    }
}
