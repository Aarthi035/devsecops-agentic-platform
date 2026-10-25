package com.devsecops.agentservice.error;

/**
 * A caller sent something the agent cannot act on: an alert with no alerts in
 * it, an approval for an action that has expired, a target that does not match
 * the action.
 *
 * <p>Separate from {@link AgentUpstreamException} because the fix is different.
 * A 502 says "try again, something upstream was down"; a 400 says "this request
 * is wrong and retrying it will not help". Mapping the two onto the same status
 * would make an operator page for a malformed alert and wait for a broken
 * monitoring stack to recover.
 *
 * <p>The message ends up in the response body, so it must be safe to show: it
 * describes what was wrong with the request, never what a credential contains.
 */
public class AgentRequestException extends RuntimeException {

    public AgentRequestException(String message) {
        super(message);
    }
}
