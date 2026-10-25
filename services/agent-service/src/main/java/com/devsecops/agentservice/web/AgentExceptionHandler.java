package com.devsecops.agentservice.web;

import com.devsecops.agentservice.error.AgentUpstreamException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns the agent's exceptions into responses a caller can act on.
 *
 * <p>Two mappings, and the distinction between them is the point:
 *
 * <ul>
 *   <li>{@link AgentUpstreamException} → <b>502</b>. GitHub or the model failed,
 *       the agent did not. A caller (an alert, a pipeline) can retry or page on
 *       this, and it is never the agent's own bug.</li>
 *   <li>Validation and unreadable bodies → <b>400</b>, with the offending fields
 *       named so a caller can fix the request without reading the source.</li>
 * </ul>
 *
 * <p>Everything else is left to Spring, which is the correct default: an
 * unexpected exception is a bug and should surface as a 500 with a stack trace in
 * the log, not be flattened into a message that hides it.
 */
@RestControllerAdvice
public class AgentExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AgentExceptionHandler.class);

    @ExceptionHandler(AgentUpstreamException.class)
    public ResponseEntity<Map<String, Object>> handleUpstream(AgentUpstreamException exception) {
        // Logged at warn, not error: a red GitHub or a stopped Ollama is an
        // expected operational state, not a defect in the agent.
        log.warn("Upstream failure while analyzing CI: {}", exception.getMessage());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "upstream_error");
        body.put("message", describe(exception));
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException exception) {
        List<String> details = exception.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .toList();
        log.info("Rejected an /analyze-ci request: {}", details);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "invalid_request");
        body.put("details", details);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException exception) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "invalid_request");
        body.put("message", "The request body could not be read as JSON.");
        return ResponseEntity.badRequest().body(body);
    }

    /** Never returns null, so the response body is always well-formed. */
    private static String describe(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }
}
