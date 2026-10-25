package com.devsecops.agentservice.audit;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The audit trail's one non-negotiable property: it cannot be used to log a
 * secret.
 *
 * <p>The guarantee is deliberately about the field's <em>name</em> rather than
 * its value, so it holds for a field nobody has thought of yet. This test pins
 * that behaviour down, because the day someone "helpfully" starts logging values
 * the whole promise is gone and nothing else in the suite would notice.
 */
class AuditLogTest {

    private final AuditLog auditLog = new AuditLog(new ObjectMapper());
    private Logger auditLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        auditLogger = (Logger) LoggerFactory.getLogger("AUDIT");
        appender = new ListAppender<>();
        appender.start();
        auditLogger.addAppender(appender);
        auditLogger.setLevel(Level.INFO);
    }

    @AfterEach
    void detachAppender() {
        auditLogger.detachAppender(appender);
    }

    @Test
    void replacesCredentialShapedFieldsWithoutReadingThem() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("repository", "example/platform");
        fields.put("githubToken", "super-secret-value");
        fields.put("api_key", "also-secret");
        fields.put("authorization", "Bearer abc");
        fields.put("password", "hunter2");

        auditLog.record(AuditLog.TOOL_CALL, fields);

        String line = lastLine();
        assertThat(line).contains("\"repository\":\"example/platform\"");
        assertThat(line).contains("\"event\":\"agent.tool_call\"");
        assertThat(line).doesNotContain("super-secret-value", "also-secret", "Bearer abc", "hunter2");
        assertThat(line).contains("[REDACTED]");
    }

    @Test
    void writesOneJsonLineWithATimestampForEveryEvent() {
        auditLog.record(AuditLog.DECISION, Map.of("confidence", 0.85, "provider", "heuristic"));

        String line = lastLine();
        assertThat(line).contains("\"event\":\"agent.decision\"");
        assertThat(line).contains("\"confidence\":\"0.85\"");
        assertThat(line).contains("\"timestamp\":\"");
    }

    @Test
    void recordsNullValuesRatherThanDroppingThem() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("rootCause", null);

        auditLog.record(AuditLog.DECISION, fields);

        // A missing field and a null field answer different questions when the
        // log is queried afterwards, so the null is kept as text.
        assertThat(lastLine()).contains("\"rootCause\":\"null\"");
    }

    private String lastLine() {
        assertThat(appender.list).hasSize(1);
        return appender.list.get(0).getFormattedMessage();
    }
}
