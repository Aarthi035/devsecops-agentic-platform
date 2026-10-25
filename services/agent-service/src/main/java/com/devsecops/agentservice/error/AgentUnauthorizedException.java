package com.devsecops.agentservice.error;

/**
 * Someone asked the agent to do something it is not authorised to do.
 *
 * <p>Only one thing reaches this today: {@code POST /approve}/{@code actionId}
 * with a missing or wrong approval token, or with no approval token configured
 * at all. It is separated from {@link AgentRequestException} because a 400 says
 * "your request was malformed" and a 401 says "this attempt was refused", and
 * the second is the one an operator wants in an alert.
 *
 * <p>The message says what was refused and why, never what was expected - a
 * refusal that quotes the configured token would leak it to the very caller it
 * was refused.
 */
public class AgentUnauthorizedException extends RuntimeException {

    public AgentUnauthorizedException(String message) {
        super(message);
    }
}
