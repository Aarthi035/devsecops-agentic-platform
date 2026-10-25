package com.devsecops.agentservice.tracing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renders one {@link AgentTrace} as a single JSON log line.
 *
 * <p>Shared by the {@code log} publisher and by the Langfuse publisher's
 * fallback, so "we could not reach the trace store" still leaves a queryable
 * record rather than nothing. One line, one object, no stack trace: Promtail
 * ships it to Loki unchanged and it is then a LogQL query away, which on a
 * laptop is what a trace UI would otherwise be for.
 *
 * <p>A logger named {@code com.devsecops.agentservice.trace} rather than the
 * class logger, so a deployment can turn traces up or down with one level
 * setting without touching the agent's own log lines.
 */
final class TraceLog {

    private static final Logger log = LoggerFactory.getLogger("com.devsecops.agentservice.trace");

    private TraceLog() {
        // Static utility.
    }

    /**
     * Writes the trace, or reports that it could not be serialised.
     *
     * <p>The two inputs are always the same objects the agent already has, so
     * serialisation cannot realistically fail; if it does, the agent must still
     * answer the original request, which is why nothing here throws.
     */
    static void write(ObjectMapper mapper, AgentTrace trace, String sink) {
        try {
            log.info(mapper.writeValueAsString(new Envelope(sink, trace)));
        } catch (JsonProcessingException e) {
            log.warn("Could not serialise a trace for sink '{}': {}", sink, e.getMessage());
        }
    }

    /** The trace plus which sink produced the line, so a query can tell them apart. */
    private record Envelope(String sink, AgentTrace trace) {
    }
}
