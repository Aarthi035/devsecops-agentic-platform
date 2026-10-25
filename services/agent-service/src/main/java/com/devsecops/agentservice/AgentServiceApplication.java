package com.devsecops.agentservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The DevOps Assistant Agent.
 *
 * <p>Phase 8 is the first half of the agent: it is told that a CI run failed,
 * it fetches that run's logs, it produces a structured diagnosis, and it
 * comments that diagnosis on the pull request. Phase 9 adds the tools that look
 * at a running cluster (Prometheus, Loki, kubectl, Trivy) and the approval gate
 * in front of state-changing actions.
 *
 * <p>{@code @ConfigurationPropertiesScan} is what turns
 * {@link com.devsecops.agentservice.config.AgentProperties} into a bean, so the
 * whole configuration surface is one class with defaults rather than a scatter
 * of {@code @Value} annotations.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AgentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentServiceApplication.class, args);
    }
}
