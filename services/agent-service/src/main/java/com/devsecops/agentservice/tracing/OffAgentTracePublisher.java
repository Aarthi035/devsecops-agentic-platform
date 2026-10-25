package com.devsecops.agentservice.tracing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The {@code agent.tracing.mode=off} publisher: it drops every trace.
 *
 * <p>It exists as a real bean rather than as a null check so that "tracing is
 * off" is one configuration value with one meaning, and so the wiring that
 * builds a trace never has to ask whether anyone wants it. The Micrometer
 * metrics are unaffected: {@link AgentTelemetry} records them in every mode,
 * because they stay in the process and the Grafana panel is about the agent
 * rather than about Langfuse.
 *
 * <p>This is the default when {@code agent.tracing.mode} is absent, so a
 * context that never mentions tracing cannot accidentally export anything.
 */
@Component
@ConditionalOnProperty(name = "agent.tracing.mode", havingValue = "off", matchIfMissing = true)
public class OffAgentTracePublisher implements AgentTracePublisher {

    private static final Logger log = LoggerFactory.getLogger(OffAgentTracePublisher.class);

    public OffAgentTracePublisher() {
        log.info("Trace export is off; agent latency and estimated cost are still recorded as metrics.");
    }

    @Override
    public String mode() {
        return "off";
    }

    @Override
    public void publish(AgentTrace trace) {
        // Nothing leaves the process. The audit log and the metrics are the record.
    }
}
