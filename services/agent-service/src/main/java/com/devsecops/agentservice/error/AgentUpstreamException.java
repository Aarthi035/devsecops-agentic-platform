package com.devsecops.agentservice.error;

/**
 * Something the agent depends on - the GitHub API or the model endpoint - did
 * not answer, or answered with something the agent cannot use.
 *
 * <p>Separate from a plain {@code RuntimeException} so the web layer can map
 * exactly this family to 502 Bad Gateway and leave genuine bugs to become 500s.
 * The distinction is worth keeping: a 502 says "the agent is fine, its upstream
 * is not", which is the difference between restarting the agent and fixing the
 * cause.
 *
 * <p>The message is written for a human reading a response body, so it must
 * never be built from a secret - see how the callers phrase it.
 */
public class AgentUpstreamException extends RuntimeException {

    public AgentUpstreamException(String message) {
        super(message);
    }

    public AgentUpstreamException(String message, Throwable cause) {
        super(message, cause);
    }
}
