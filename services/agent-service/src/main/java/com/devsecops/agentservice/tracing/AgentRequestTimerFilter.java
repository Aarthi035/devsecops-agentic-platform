package com.devsecops.agentservice.tracing;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Stamps the start time of an agent request so the trace that is built after the
 * handler can report how long it took.
 *
 * <p>It does nothing else, and deliberately has no dependencies: the metrics and
 * the trace are recorded in {@link AgentTelemetryAdvice}, which runs once the
 * response body exists. All this filter has to do is remember when the request
 * arrived, because by the time the advice is called that moment has already
 * passed and is not recoverable from the request.
 *
 * <p>Only the agent's own four routes are stamped. Actuator scrapes and health
 * probes are not traces and should not be.
 */
@Component
public class AgentRequestTimerFilter extends OncePerRequestFilter {

    /** The request attribute the advice reads. Public so the two cannot drift apart. */
    public static final String START_NANOS_ATTRIBUTE = "com.devsecops.agent.requestStartNanos";

    /** The routes that are traced: the three triggers and the approval endpoint. */
    private static final Set<String> TRACED_PATHS = Set.of(
            "/alert", "/analyze-ci", "/analyze-security", "/approve");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        request.setAttribute(START_NANOS_ATTRIBUTE, System.nanoTime());
        chain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return TRACED_PATHS.stream().noneMatch(path::startsWith);
    }
}
