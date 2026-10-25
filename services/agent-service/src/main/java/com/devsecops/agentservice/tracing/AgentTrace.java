package com.devsecops.agentservice.tracing;

import java.util.List;

/**
 * One agent request, as it is exported to a trace sink.
 *
 * <p>Shaped by what the spec asks a trace to answer about the agent: what it was
 * asked, what it concluded, how many steps it took, and what that cost. It is
 * deliberately <em>not</em> the response the caller received - a trace is a
 * record of how the answer was reached, so it carries the diagnosis's provider
 * and confidence, the recommended action, and the tool-call count, and it
 * carries no request or response body at all. Nothing here is a secret: the
 * bodies have already been sanitized for the audit log, but a third-party trace
 * sink is the wrong place to be careful about that.
 *
 * <p>{@code estimatedCostUsd} is an estimate and is named as one. See {@link
 * com.devsecops.agentservice.config.TracingProperties#estimateCostUsd(int, int)}
 * for how it is derived and why a local deployment's answer is near zero rather
 * than absent.
 */
public record AgentTrace(
        /** A stable-ish id for the trace, so a log line and a Langfuse trace can be joined. */
        String id,
        /** What was traced: {@code analyze-ci}, {@code alert}, {@code analyze-security}, {@code approve}. */
        String name,
        /** The subject, e.g. a repository, a service or a report path. */
        String subject,
        /** The request path, as it arrived. */
        String endpoint,
        /** {@code accepted}, {@code rejected}, {@code failed} or {@code error}. */
        String outcome,
        /** Which engine produced the diagnosis, when one was produced. */
        String provider,
        /** {@code restart}, {@code scale}, {@code rollback} or {@code none}. */
        String recommendedAction,
        /** The diagnosis's confidence, or {@code -1} when there was no diagnosis. */
        double confidence,
        /** How many tool calls the request spent. */
        int toolCalls,
        /** {@code live} or {@code mock}, or blank when no tools were involved. */
        String toolsMode,
        /** How long the request took, in milliseconds. */
        long durationMillis,
        /** The estimated cost of the request in US dollars. */
        double estimatedCostUsd,
        /** Anything else worth recording: a deduplication, a missing reading, a refusal. */
        List<String> notes) {

    public AgentTrace {
        notes = notes == null ? List.of() : List.copyOf(notes);
    }
}
