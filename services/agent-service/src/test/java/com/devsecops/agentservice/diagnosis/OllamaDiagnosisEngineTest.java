package com.devsecops.agentservice.diagnosis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devsecops.agentservice.TestProperties;
import com.devsecops.agentservice.config.AgentProperties;
import com.devsecops.agentservice.error.AgentUpstreamException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The model-backed engine, tested against a stand-in for Ollama.
 *
 * <p>The cases here are the ones that decide whether a model can be trusted with
 * the answer: a well-formed reply is parsed, a reply wrapped in a Markdown fence
 * is still parsed, and everything else - a 404, a missing message, prose instead
 * of JSON - becomes a 502 rather than a silently empty diagnosis. That last part
 * is the point of the whole class: an agent that invents a diagnosis when the
 * model misbehaves is worse than one that fails.
 *
 * <p>The envelopes are built with Jackson rather than as literals, because the
 * model's answer is itself a JSON string inside JSON - writing that by hand is
 * how the test would become the thing that is broken.
 */
class OllamaDiagnosisEngineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private OllamaDiagnosisEngine engine;
    private Consumer<HttpExchange> handler;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> handler.accept(exchange));
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        AgentProperties properties = TestProperties.with(
                new AgentProperties.Llm("ollama", baseUrl, "llama3.2:3b", 5, 700, 0.1),
                new AgentProperties.Github("https://api.github.com", "", false),
                new AgentProperties.Limits(12_000, 3, 5, 12));
        engine = new OllamaDiagnosisEngine(properties, new ObjectMapper());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void parsesAWellFormedDiagnosis() {
        handler = exchange -> respond(exchange, 200, envelope("""
                {"root_cause":"The build ran out of disk space.","evidence":["No space left on device"],
                 "suggested_fix":"Free space and re-run.","recommended_action":"none","confidence":0.9}
                """));

        Diagnosis diagnosis = engine.diagnose(request());

        assertThat(diagnosis.rootCause()).isEqualTo("The build ran out of disk space.");
        assertThat(diagnosis.evidence()).containsExactly("No space left on device");
        assertThat(diagnosis.confidence()).isEqualTo(0.9);
        assertThat(engine.provider()).isEqualTo("ollama:llama3.2:3b");
    }

    @Test
    void clampsAnOutOfRangeConfidenceInsteadOfFailing() {
        handler = exchange -> respond(exchange, 200, envelope("""
                {"root_cause":"Test failure.","evidence":[],"suggested_fix":"Fix the test.",
                 "recommended_action":"none","confidence":4.2}
                """));

        assertThat(engine.diagnose(request()).confidence()).isEqualTo(1.0);
    }

    @Test
    void toleratesAMarkdownFenceAroundTheObject() {
        handler = exchange -> respond(exchange, 200, envelope("Here is the diagnosis:\n```json\n"
                + "{\"root_cause\":\"Test failure.\",\"evidence\":[],\"suggested_fix\":\"Fix the test.\","
                + "\"recommended_action\":\"none\",\"confidence\":0.7}\n```"));

        assertThat(engine.diagnose(request()).rootCause()).isEqualTo("Test failure.");
    }

    @Test
    void rejectsProseInsteadOfJson() {
        handler = exchange -> respond(exchange, 200, envelope("I could not determine the cause from this log."));

        assertThatThrownBy(() -> engine.diagnose(request()))
                .isInstanceOf(AgentUpstreamException.class)
                .hasMessageContaining("did not return the expected JSON diagnosis");
    }

    @Test
    void rejectsAnEmptyAnswer() {
        handler = exchange -> respond(exchange, 200, envelope(""));

        assertThatThrownBy(() -> engine.diagnose(request()))
                .isInstanceOf(AgentUpstreamException.class)
                .hasMessageContaining("returned no content");
    }

    @Test
    void reportsAMissingModelAsAnUpstreamProblem() {
        handler = exchange -> respond(exchange, 404, "{\"error\":\"model 'llama3.2:3b' not found\"}");

        assertThatThrownBy(() -> engine.diagnose(request()))
                .isInstanceOf(AgentUpstreamException.class)
                .hasMessageContaining("HTTP 404")
                .hasMessageContaining("ollama pull");
    }

    @Test
    void reportsAnUnreachableEndpointAsAnUpstreamProblem() {
        // Point the engine at a port nothing is listening on: a stopped Ollama
        // must surface as "cannot reach the model", not as a 500.
        AgentProperties unreachable = TestProperties.with(
                new AgentProperties.Llm("ollama", "http://127.0.0.1:1", "llama3.2:3b", 2, 700, 0.1),
                new AgentProperties.Github("https://api.github.com", "", false),
                new AgentProperties.Limits(12_000, 3, 2, 12));

        assertThatThrownBy(() -> new OllamaDiagnosisEngine(unreachable, new ObjectMapper()).diagnose(request()))
                .isInstanceOf(AgentUpstreamException.class)
                .hasMessageContaining("is Ollama running?");
    }

    private static DiagnosisRequest request() {
        return new DiagnosisRequest("example/platform", "CI", "build", "failure", "log line");
    }

    /** Ollama's chat envelope: {@code {"message":{"content":"<the JSON>"}}}. */
    private static String envelope(String content) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "llama3.2:3b");
        body.put("done", true);
        body.put("message", Map.of("role", "assistant", "content", content));
        try {
            return MAPPER.writeValueAsString(body);
        } catch (JacksonException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } finally {
            exchange.close();
        }
    }
}
