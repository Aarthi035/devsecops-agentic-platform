package com.devsecops.agentservice.tracing;

/**
 * Where a finished trace goes.
 *
 * <p>One interface and three implementations, chosen by {@code
 * agent.tracing.mode}, following the same pattern as the diagnosis engines and
 * the diagnostic tools: the alternative is an {@code if (mode.equals(...))}
 * inside the publisher, which is the same decision made in a less testable
 * place.
 *
 * <p><b>A publisher never fails a request.</b> Tracing is a record of work that
 * has already been done; losing a trace must cost nothing, and an unreachable
 * Langfuse must not turn a working diagnosis into a 500. Every implementation
 * is therefore allowed to swallow its own failures and is expected to log them.
 */
public interface AgentTracePublisher {

    /** The mode this publisher implements: {@code off}, {@code log} or {@code langfuse}. */
    String mode();

    /**
     * Records one finished request.
     *
     * @param trace what happened; never null, and never contains a secret
     */
    void publish(AgentTrace trace);
}
