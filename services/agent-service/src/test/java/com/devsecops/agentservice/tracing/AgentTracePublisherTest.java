package com.devsecops.agentservice.tracing;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.devsecops.agentservice.config.TracingProperties;
import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The three trace sinks, and the one rule they all share: a publisher never
 * fails the request that produced the trace.
 *
 * <p>The Langfuse case is driven against a real HTTP server on the loopback
 * interface rather than a mock, because the parts worth checking are exactly the
 * ones a mock erases - that the ingestion path is {@code /api/public/ingestion},
 * that the credential arrives as HTTP Basic, and that the body is a
 * {@code trace-create} batch. Asserting "sendAsync was called" would prove none
 * of that.
 */
class AgentTracePublisherTest {

    private static final String TRACE_LOGGER = "com.devsecops.agentservice.trace";

    private Logger traceLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        traceLogger = (Logger) LoggerFactory.getLogger(TRACE_LOGGER);
        appender = new ListAppender<>();
        appender.start();
        traceLogger.addAppender(appender);
        traceLogger.setLevel(Level.INFO);
    }

    @AfterEach
    void detachAppender() {
        traceLogger.detachAppender(appender);
    }

    @Test
    void offPublishesNothingAtAll() {
        OffAgentTracePublisher publisher = new OffAgentTracePublisher();

        publisher.publish(trace("trace-off"));

        assertThat(publisher.mode()).isEqualTo("off");
        assertThat(appender.list).isEmpty();
    }

    @Test
    void logWritesOneEnvelopePerTrace() {
        LogAgentTracePublisher publisher = new LogAgentTracePublisher(new ObjectMapper());

        publisher.publish(trace("trace-log"));

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage())
                .contains("\"sink\":\"log\"", "\"id\":\"trace-log\"", "\"name\":\"alert\"", "\"toolCalls\":6");
    }

    @Test
    void langfuseWithoutKeysFallsBackToTheLogSink() {
        LangfuseTracePublisher publisher = new LangfuseTracePublisher(
                properties("langfuse", "", "", "http://langfuse.invalid:3000"),
                new ObjectMapper(),
                Clock.systemUTC());

        publisher.publish(trace("trace-unconfigured"));

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage())
                .contains("\"sink\":\"langfuse-unconfigured\"", "\"id\":\"trace-unconfigured\"");
    }

    @Test
    void langfuseWithKeysPostsATraceCreateBatch() throws IOException, InterruptedException {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        CountDownLatch received = new CountDownLatch(1);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/public/ingestion", exchange -> {
            authorization.set(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(207, -1);
            exchange.close();
            received.countDown();
        });
        server.start();

        try {
            LangfuseTracePublisher publisher = new LangfuseTracePublisher(
                    properties("langfuse", "pk", "sk", "http://127.0.0.1:" + server.getAddress().getPort()),
                    new ObjectMapper(),
                    Clock.systemUTC());

            publisher.publish(trace("trace-langfuse"));

            assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(body.get()).contains("\"batch\"", "\"type\":\"trace-create\"", "\"id\":\"trace-langfuse\"");
            // "pk:sk" in HTTP Basic, which is the shape Langfuse expects.
            assertThat(authorization.get()).isEqualTo("Basic " + java.util.Base64.getEncoder()
                    .encodeToString("pk:sk".getBytes(StandardCharsets.UTF_8)));
            assertThat(appender.list).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    private static TracingProperties properties(String mode, String publicKey, String secretKey, String host) {
        return new TracingProperties(mode, host, publicKey, secretKey, 5, "release", "test", 0.0002, 0.00002);
    }

    private static AgentTrace trace(String id) {
        return new AgentTrace(id, "alert", "deployment devsecops/user-service", "/alert", "accepted",
                "heuristic", "restart", 0.8, 6, "mock", 42, 0.00012, List.of("a note"));
    }
}
