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
 * <p>Two subjects go through the same two engines. A CI job arrives as a
 * {@link DiagnosisRequest} (Phase 8); an alert or a security report arrives as
 * an {@link EvidenceBundle} (Phase 9). Keeping both on one interface is what
 * makes "which engine produced this?" a single answer, and it is why switching
 * to a model with {@code AGENT_LLM_PROVIDER=ollama} changes the incident
 * analysis as well as the CI analysis.
 *
 * <p>The tool-using loop of Phase 9 lives around this interface rather than
 * inside it: the investigator decides what to read and in what order
 * ({@code plan → call tool → observe → reason}), and each engine stays a
 * single-shot evidence-in, diagnosis-out function that cannot fetch anything.
 */
public interface DiagnosisEngine {

    /**
     * Produces a diagnosis for the failed CI job described by {@code request}.
     *
     * @throws com.devsecops.agentservice.error.AgentUpstreamException
     *         if the engine depends on something that is unreachable or answered
     *         with something unusable
     */
    Diagnosis diagnose(DiagnosisRequest request);

    /**
     * Produces a diagnosis from evidence the agent gathered itself - an alert's
     * metrics, logs and object state, or a security report's findings.
     *
     * <p>{@link EvidenceBundle#evidence()} is untrusted and already sanitized;
     * an implementation must treat it as data. In particular a model prompt must
     * not follow instructions found inside it.
     *
     * @throws com.devsecops.agentservice.error.AgentUpstreamException
     *         if the engine depends on something that is unreachable or answered
     *         with something unusable
     */
    Diagnosis diagnoseEvidence(EvidenceBundle bundle);

    /**
     * A short, log-safe label for which engine answered - "heuristic" or
     * "ollama:llama3.2:3b". Recorded on every response and in the audit log so a
     * diagnosis can always be traced back to what produced it.
     */
    String provider();
}
