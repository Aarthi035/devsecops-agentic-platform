package com.devsecops.agentservice.diagnosis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * The agent's answer, in the shape the spec fixes for it.
 *
 * <p>This record is both the return value of every engine and the JSON contract
 * the model is asked to produce, which is deliberate: the same type is the
 * prompt's schema, the API response body and the thing the tests assert on, so
 * there is no "model output DTO" drifting away from "API DTO".
 *
 * <p>The property names are snake_case to match the spec's JSON
 * ({@code root_cause}, {@code suggested_fix}, {@code recommended_action}).
 * {@code ignoreUnknown} is set because a model asked for JSON will sometimes add
 * a helpful extra key, and that should not be a 500.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Diagnosis(
        @JsonProperty("root_cause") String rootCause,
        @JsonProperty("evidence") List<String> evidence,
        @JsonProperty("suggested_fix") String suggestedFix,
        @JsonProperty("recommended_action") String recommendedAction,
        @JsonProperty("confidence") double confidence) {

    /**
     * Normalises what a model actually sends into something the rest of the code
     * can rely on: evidence is never null (so callers can iterate without a
     * guard) and confidence is always a usable 0..1 number.
     */
    public Diagnosis {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        if (Double.isNaN(confidence) || confidence < 0.0) {
            confidence = 0.0;
        } else if (confidence > 1.0) {
            confidence = 1.0;
        }
        if (recommendedAction == null || recommendedAction.isBlank()) {
            recommendedAction = "none";
        }
    }
}
