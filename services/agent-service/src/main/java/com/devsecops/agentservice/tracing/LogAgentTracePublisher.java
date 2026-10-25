package com.devsecops.agentservice.tracing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The {@code agent.tracing.mode=log} publisher: one structured line per request.
 *
 * <p>This is the Phase 10 laptop sink. It needs nothing installed, and because
 * Promtail is already shipping every pod's stdout into Loki, the traces it
 * writes are queryable in Grafana next to the service logs - which is enough to
 * answer the questions a trace is for ("what did the agent do for this alert,
 * how long did it take, how many steps did it spend") without a Langfuse
 * instance to run.
 */
@Component
@ConditionalOnProperty(name = "agent.tracing.mode", havingValue = "log")
public class LogAgentTracePublisher implements AgentTracePublisher {

    private static final Logger log = LoggerFactory.getLogger(LogAgentTracePublisher.class);

    private final ObjectMapper mapper;

    public LogAgentTracePublisher(ObjectMapper mapper) {
        this.mapper = mapper;
        log.info("Traces are written to the '{}' logger; query them in Loki.", "com.devsecops.agentservice.trace");
    }

    @Override
    public String mode() {
        return "log";
    }

    @Override
    public void publish(AgentTrace trace) {
        TraceLog.write(mapper, trace, mode());
    }
}
