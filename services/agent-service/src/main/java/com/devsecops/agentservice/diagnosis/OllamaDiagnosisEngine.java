package com.devsecops.agentservice.diagnosis;

import com.devsecops.agentservice.config.AgentProperties;
import com.devsecops.agentservice.error.AgentUpstreamException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Diagnoses a failure by asking a local model - Ollama, by default
 * {@code llama3.2:3b}.
 *
 * <p>There is no Spring AI or LangChain4j here on purpose. The agent has exactly
 * one call to make, and it is one JSON POST; a library would add a dependency
 * tree, its own version of the Spring Boot BOM to reconcile, and a layer of
 * abstraction over two objects. The JDK's {@code HttpClient} plus Jackson says
 * the same thing in a class you can read in one sitting - and keeps this module
 * on the same Spring Boot version as the other three services.
 *
 * <p>Three things the spec asks for are enforced here rather than trusted to the
 * model:
 * <ul>
 *   <li><b>Bounded output.</b> {@code num_predict} is set from
 *       {@code agent.llm.max-output-tokens}, not left to the model's own
 *       default, so a runaway generation cannot cost unbounded time.</li>
 *   <li><b>Structured output.</b> {@code format: json} makes Ollama constrain
 *       generation to valid JSON, and the result is parsed into
 *       {@link Diagnosis} - so a malformed answer fails loudly as a 502 instead
 *       of reaching the PR comment as prose.</li>
 *   <li><b>Sanitized input.</b> The log arrives already redacted and truncated,
 *       so nothing here needs to know about secrets.</li>
 * </ul>
 *
 * <p>Only the sanitized log and the job's identifying strings are ever sent.
 * The GitHub token is not part of this class's world - it cannot leak to the
 * model because it is never placed in the request.
 */
@Component
@ConditionalOnProperty(name = "agent.llm.provider", havingValue = "ollama")
public class OllamaDiagnosisEngine implements DiagnosisEngine {

    private static final Logger log = LoggerFactory.getLogger(OllamaDiagnosisEngine.class);

    /**
     * The role the model plays, and the exact JSON contract it must answer with.
     * The keys are the spec's, and the last line is the anti-hallucination rule:
     * a diagnosis that invents a log line is worse than a low-confidence one,
     * because it looks checkable and is not.
     */
    private static final String SYSTEM_PROMPT = """
            You are a CI failure diagnostician for a Spring Boot microservices platform.
            You are given the sanitized log of a failed GitHub Actions job.
            Answer with a single JSON object and nothing else, with exactly these keys:
              "root_cause": string - one sentence naming the underlying cause.
              "evidence": array of strings - up to 5 lines copied verbatim from the log.
              "suggested_fix": string - concrete steps a developer should take.
              "recommended_action": string - one of "none", "retry", "restart", "scale", "rollback".
              "confidence": number between 0 and 1.
            Copy the evidence from the log exactly; never invent a line that is not there.
            If the log does not support a conclusion, say so and use a low confidence.
            """;

    /** Connect timeout is a fraction of the whole-call budget: a dead endpoint should fail fast. */
    private static final int MAX_CONNECT_TIMEOUT_SECONDS = 10;

    private final AgentProperties.Llm llm;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OllamaDiagnosisEngine(AgentProperties properties, ObjectMapper objectMapper) {
        this.llm = properties.llm();
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(
                        Math.max(1, Math.min(MAX_CONNECT_TIMEOUT_SECONDS, llm.timeoutSeconds()))))
                .build();
    }

    @Override
    public String provider() {
        return "ollama:" + llm.model();
    }

    @Override
    public Diagnosis diagnose(DiagnosisRequest request) {
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(llm.baseUrl() + "/api/chat"))
                .timeout(Duration.ofSeconds(llm.timeoutSeconds()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(request), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            // The message names the endpoint so an operator can test it with curl,
            // but never the body of the request - the log is in there.
            throw new AgentUpstreamException(
                    "Could not reach the model endpoint at " + llm.baseUrl() + " (is Ollama running?): "
                            + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentUpstreamException("The model call was interrupted.", e);
        }

        if (response.statusCode() != 200) {
            throw new AgentUpstreamException(
                    "The model endpoint returned HTTP " + response.statusCode()
                            + ". Check that the model '" + llm.model() + "' is pulled (ollama pull "
                            + llm.model() + ").");
        }
        return parseDiagnosis(response.body());
    }

    /**
     * Builds the request body.
     *
     * <p>Written as a map rather than a hand-built string so the log is
     * JSON-escaped by Jackson. A build log is full of quotes, backslashes and
     * control characters; concatenating it into a string literal would produce
     * invalid JSON the first time a test printed one.
     */
    private String buildRequestBody(DiagnosisRequest request) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("temperature", llm.temperature());
        // The "max token limit" guardrail from the spec, in the one place the
        // model's generation length is actually decided.
        options.put("num_predict", llm.maxOutputTokens());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", llm.model());
        body.put("stream", false);
        body.put("format", "json");
        body.put("options", options);
        body.put("messages", List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", buildUserMessage(request))));

        try {
            return objectMapper.writeValueAsString(body);
        } catch (IOException e) {
            // Serialising maps of strings and numbers cannot realistically fail;
            // if it does, it is a bug, so it becomes a 500 rather than a 502.
            throw new IllegalStateException("Could not serialise the model request.", e);
        }
    }

    /** The user turn: where the job is, and the sanitized log in a fenced block. */
    private String buildUserMessage(DiagnosisRequest request) {
        return """
                Repository: %s
                Workflow: %s
                Job: %s
                Conclusion: %s

                Log of the failed job:
                ```
                %s
                ```
                """.formatted(
                nullToUnknown(request.repository()),
                nullToUnknown(request.workflowName()),
                nullToUnknown(request.jobName()),
                nullToUnknown(request.conclusion()),
                request.logs() == null ? "" : request.logs());
    }

    /**
     * Pulls the diagnosis out of Ollama's chat envelope.
     *
     * <p>Ollama returns {@code {"message":{"content":"<the JSON>"}}, ...}, so the
     * content is a JSON string nested in JSON. Even with {@code format: json} a
     * model will sometimes wrap its object in a Markdown fence, so the text is
     * trimmed to the outermost braces before parsing. Anything that survives that
     * and still will not parse is reported as an upstream problem: the agent
     * cannot invent a diagnosis the model did not give.
     */
    private Diagnosis parseDiagnosis(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode content = root.path("message").path("content");
            if (content.isMissingNode() || content.asText().isBlank()) {
                throw new AgentUpstreamException(
                        "The model returned no content. Check that model '" + llm.model()
                                + "' supports chat completions.");
            }
            String json = extractJsonObject(content.asText());
            log.debug("Model returned a diagnosis of {} characters.", json.length());
            return objectMapper.readValue(json, Diagnosis.class);
        } catch (IOException e) {
            throw new AgentUpstreamException(
                    "The model did not return the expected JSON diagnosis: " + e.getMessage(), e);
        }
    }

    /**
     * Returns the first balanced-looking JSON object in the text.
     *
     * <p>Deliberately crude - from the first {@code {} to the last {@code }} - and
     * not a parser. It exists only to strip a Markdown fence or a sentence of
     * preamble the model added around an otherwise valid object, which is a
     * formatting slip rather than a lie. If there is no brace at all the original
     * text is returned so the failure message can quote it.
     */
    private static String extractJsonObject(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    private static String nullToUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
