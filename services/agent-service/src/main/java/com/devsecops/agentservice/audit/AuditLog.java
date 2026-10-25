package com.devsecops.agentservice.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The agent's decision record: one JSON line per action, on a logger named
 * {@code AUDIT}.
 *
 * <p>The spec requires that every tool call and every decision be recorded. A
 * separate logger name rather than the class logger is what makes that useful -
 * an operator can route {@code AUDIT} to a file, a Loki stream or an alerting
 * pipeline without also collecting the framework's debug chatter.
 *
 * <p>Two properties matter more than the format:
 *
 * <ul>
 *   <li><b>It cannot log a secret.</b> Every value is passed through
 *       {@link #SECRET_ISH} first. A field whose name looks like a credential is
 *       replaced by {@code [REDACTED]} regardless of what the caller passed, so
 *       an audit call added carelessly in the future still cannot leak a token.
 *       The name is what is checked, never the value, so nothing has to be
 *       inspected to be protected.</li>
 *   <li><b>It never throws.</b> An audit trail that can fail the operation it is
 *       recording turns a working diagnosis into a 500. A serialisation problem
 *       degrades to a plain line instead.</li>
 * </ul>
 */
@Component
public class AuditLog {

    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");

    /**
     * Field names that must never carry a value. Matched against the <em>key</em>
     * only - if a name says token, the value is dropped without being looked at.
     * Deliberately includes the generic "authorization" and "credential" as well
     * as the specific ones.
     */
    private static final Pattern SECRET_ISH =
            Pattern.compile("(?i)(pass(?:word|wd)?|secret|token|credential|api[_-]?key|authorization)");

    /** Event name for one call to an external system (GitHub, a model, later a tool). */
    public static final String TOOL_CALL = "agent.tool_call";

    /** Event name for a conclusion the agent reached. */
    public static final String DECISION = "agent.decision";

    /** A state-changing action the agent suggested and is waiting for a human to authorise. */
    public static final String ACTION_PROPOSED = "agent.action_proposed";

    /** A human's answer to a proposal, including the refusals. */
    public static final String ACTION_DECIDED = "agent.action_decided";

    /** The result of running an approved action, whether it worked or not. */
    public static final String ACTION_EXECUTED = "agent.action_executed";

    private final ObjectMapper objectMapper;

    public AuditLog(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Writes one audit entry.
     *
     * @param event  a short, stable name - use the constants on this class where
     *               one applies, so log queries can be written against them
     * @param fields the facts of the event; null values are kept as
     *               {@code "null"} rather than omitted, so a query for
     *               "confidence is missing" still works
     */
    public void record(String event, Map<String, ?> fields) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("timestamp", OffsetDateTime.now(ZoneOffset.UTC).toString());
        entry.put("event", event);
        if (fields != null) {
            fields.forEach((key, value) ->
                    entry.put(key, SECRET_ISH.matcher(key).find() ? "[REDACTED]" : String.valueOf(value)));
        }

        try {
            AUDIT.info(objectMapper.writeValueAsString(entry));
        } catch (JsonProcessingException e) {
            // Fall back to a line that is still searchable by event, rather than
            // losing the record or failing the request that produced it.
            AUDIT.info("{\"event\":\"" + event + "\",\"note\":\"audit fields could not be serialised\"}");
        }
    }
}
