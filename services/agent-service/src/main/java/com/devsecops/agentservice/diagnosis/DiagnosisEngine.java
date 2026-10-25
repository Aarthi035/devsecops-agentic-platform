package com.devsecops.agentservice.diagnosis;

/**
 * The seam that makes the LLM a replaceable part rather than the architecture.
 *
 * <p>Two implementations satisfy it and only one is ever in the context, chosen
 * by {@code agent.llm.provider}: a rule table that needs no model at all, and a
 * call to a local Ollama. That is what lets the whole Phase 8 path - HTTP
 * request, GitHub fetch, diagnosis, PR comment - be tested offline, and it is
 * also the honest answer to "what runs in CI?", where no model is installed.
 *
 * <p>When Phase 9 adds the tool-using loop, the loop goes around this interface,
 * not around a particular model: {@code plan → call tool → observe → reason} is
 * the caller's job, and each engine stays a single-shot text-in, diagnosis-out
 * function.
 */
public interface DiagnosisEngine {

    /**
     * Produces a diagnosis for the failed job described by {@code request}.
     *
     * @throws com.devsecops.agentservice.error.AgentUpstreamException
     *         if the engine depends on something that is unreachable or answered
     *         with something unusable
     */
    Diagnosis diagnose(DiagnosisRequest request);

    /**
     * A short, log-safe label for which engine answered - "heuristic" or
     * "ollama:llama3.2:3b". Recorded on every response and in the audit log so a
     * diagnosis can always be traced back to what produced it.
     */
    String provider();
}
