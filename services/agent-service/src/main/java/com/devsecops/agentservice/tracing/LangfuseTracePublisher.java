package com.devsecops.agentservice.tracing;

import com.devsecops.agentservice.config.TracingProperties;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The {@code agent.tracing.mode=langfuse} publisher.
 *
 * <p>Posts one {@code trace-create} event to a Langfuse instance's ingestion
 * API. Two decisions are worth stating:
 *
 * <ul>
 *   <li><b>It is asynchronous and best-effort.</b> The send is
 *       {@code sendAsync} and nothing waits on it: tracing happens after the
 *       answer has been computed, and a trace store that is slow, down or
 *       misconfigured must not slow down, fail or block a diagnosis. A failure
 *       is logged once per occurrence and otherwise dropped.</li>
 *   <li><b>It falls back to the log sink.</b> "Mode is langfuse but no keys are
 *       configured" is a deployment mistake, not a reason to lose the trace, so
 *       the publisher says so once at startup and then writes the same line the
 *       {@code log} mode writes.</li>
 * </ul>
 *
 * <p>The payload carries the trace's shape - which endpoint, which engine, what
 * was recommended, how many tool calls, how long, what it is estimated to have
 * cost - and no request or response body. A trace sink is a third party, and
 * the agent's own rule is that nothing untrusted and nothing secret leaves the
 * process for one.
 */
@Component
@ConditionalOnProperty(name = "agent.tracing.mode", havingValue = "langfuse")
public class LangfuseTracePublisher implements AgentTracePublisher {

    private static final Logger log = LoggerFactory.getLogger(LangfuseTracePublisher.class);

    private static final String INGESTION_PATH = "/api/public/ingestion";

    private final TracingProperties properties;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final HttpClient client;
    private final URI endpoint;
    private final String authorization;

    public LangfuseTracePublisher(TracingProperties properties, ObjectMapper mapper, Clock clock) {
        this.properties = properties;
        this.mapper = mapper;
        this.clock = clock;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, properties.timeoutSeconds())))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.endpoint = URI.create(stripTrailingSlash(properties.host()) + INGESTION_PATH);
        this.authorization = properties.hasCredentials() ? basic(properties) : null;
        if (authorization == null) {
            log.warn("agent.tracing.mode is 'langfuse' but no public key / secret key is configured; traces will "
                    + "be written to the log sink instead of being exported.");
        } else {
            log.info("Traces are exported to {} as release '{}' in environment '{}'.", endpoint,
                    properties.release(), properties.environment());
        }
    }

    @Override
    public String mode() {
        return "langfuse";
    }

    @Override
    public void publish(AgentTrace trace) {
        if (authorization == null) {
            TraceLog.write(mapper, trace, mode() + "-unconfigured");
            return;
        }
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(Math.max(1, properties.timeoutSeconds())))
                    .header("Content-Type", "application/json")
                    .header("Authorization", authorization)
                    .POST(HttpRequest.BodyPublishers.ofString(payload(trace), StandardCharsets.UTF_8))
                    .build();
        } catch (RuntimeException e) {
            log.warn("Could not build the Langfuse request for trace {}: {}", trace.id(), e.getMessage());
            return;
        }

        client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete((response, error) -> {
            if (error != null) {
                log.warn("Could not export trace {} to Langfuse: {}", trace.id(), error.getMessage());
            } else if (response.statusCode() / 100 != 2) {
                log.warn("Langfuse rejected trace {} with HTTP {}: {}", trace.id(), response.statusCode(),
                        truncate(response.body()));
            }
        });
    }

    /**
     * The ingestion batch for one trace.
     *
     * <p>Hand-built from maps rather than from a DTO class, because it is the
     * wire shape of somebody else's API: a record here would look like part of
     * this application's model and would have to be swept away the day Langfuse
     * changes a field name.
     */
    private String payload(AgentTrace trace) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", trace.id());
        body.put("name", trace.name());
        body.put("timestamp", now());
        body.put("release", properties.release());
        body.put("environment", properties.environment());
        body.put("tags", List.of(trace.outcome(), trace.toolsMode().isBlank() ? "no-tools" : trace.toolsMode()));
        body.put("metadata", metadata(trace));

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", UUID.randomUUID().toString());
        event.put("type", "trace-create");
        event.put("timestamp", now());
        event.put("body", body);

        try {
            return mapper.writeValueAsString(Map.of("batch", List.of(event)));
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalStateException("trace " + trace.id() + " could not be serialised", e);
        }
    }

    /** The part of the trace worth indexing and filtering on, and nothing more. */
    private Map<String, Object> metadata(AgentTrace trace) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("endpoint", trace.endpoint());
        metadata.put("subject", trace.subject());
        metadata.put("provider", trace.provider());
        metadata.put("recommendedAction", trace.recommendedAction());
        metadata.put("confidence", trace.confidence());
        metadata.put("toolCalls", trace.toolCalls());
        metadata.put("toolsMode", trace.toolsMode());
        metadata.put("durationMillis", trace.durationMillis());
        metadata.put("estimatedCostUsd", trace.estimatedCostUsd());
        metadata.put("notes", trace.notes());
        return metadata;
    }

    private String now() {
        return Instant.now(clock).toString();
    }

    private static String basic(TracingProperties properties) {
        String pair = properties.publicKey() + ":" + properties.secretKey();
        return "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
    }

    private static String stripTrailingSlash(String host) {
        String value = host == null ? "" : host.strip();
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }
}
