package com.devsecops.agentservice.logs;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LogSanitizerTest {

    private final LogSanitizer sanitizer = new LogSanitizer();

    @Test
    void redactsKnownTokensAndSecretShapedValues() {
        String sanitized = sanitizer.sanitize(
                "token=ghp_example-secret-value password=super-secret",
                "ghp_example-secret-value",
                1_000);

        assertThat(sanitized).doesNotContain("ghp_example-secret-value", "super-secret");
        assertThat(sanitized).contains("token=[REDACTED]", "password=[REDACTED]");
    }

    @Test
    void retainsTheBeginningAndEndWhenTruncating() {
        String sanitized = sanitizer.sanitize("START\n" + "x".repeat(200) + "\nEND", null, 120);

        assertThat(sanitized).startsWith("START");
        assertThat(sanitized).contains("log truncated by the agent");
        assertThat(sanitized).endsWith("END");
        assertThat(sanitized.length()).isLessThanOrEqualTo(120);
    }
}