package com.devsecops.agentservice.action;

/**
 * The body of {@code POST /approve/{actionId}}.
 *
 * <p>{@code approve} is a {@link Boolean} rather than a primitive so "the caller
 * did not say" is distinguishable from "the caller said no". That distinction
 * matters: absent is a malformed request and is refused, while {@code false} is
 * a decision to reject the proposal and is recorded as one. Collapsing the two
 * would make an unreadable request look like a deliberate rejection in the audit
 * trail.
 *
 * <p>{@code token} may be sent here or as an {@code Authorization: Bearer} header.
 * The header is preferred and is what the controller looks at first - a body is
 * more likely to be captured by a proxy's request log than a header - and the
 * body field exists so a curl in a demo script is readable.
 */
public record ApprovalRequest(
        /** Whether to execute the action. Required. */
        Boolean approve,

        /** The approval token; alternative to the {@code Authorization} header. */
        String token,

        /** Who is deciding, for the audit trail. Not an authentication. */
        String approver) {
}
