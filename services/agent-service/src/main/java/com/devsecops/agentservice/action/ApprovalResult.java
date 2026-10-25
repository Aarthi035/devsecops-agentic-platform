package com.devsecops.agentservice.action;

import java.time.Instant;

/**
 * The answer to a decision on a proposal.
 *
 * <p>Carries both the {@code decision} - what the caller asked for and what the
 * gate did with it - and the resulting {@code status} of the action. They are
 * separate because they can differ in a way that matters: a request to approve
 * an expired proposal has the decision {@code EXPIRED} and leaves the action
 * {@code EXPIRED}. Collapsing them would make "your approval was refused" and
 * "the action is done" look the same in a response body.
 *
 * <p>{@code proposal} is always present so a caller can see exactly what was
 * decided, including on a refusal - which is how an operator discovers that a
 * duplicate click was about a different action than they thought.
 */
public record ApprovalResult(
        /** The id that was decided on. */
        String actionId,
        /** {@code APPROVED}, {@code REJECTED}, {@code EXPIRED}, {@code DUPLICATE} or {@code INVALID_TARGET}. */
        String decision,
        /** The action's status after the decision. */
        ActionStatus status,
        /** Whether the action actually ran. */
        boolean executed,
        /** One line explaining the outcome, safe to show. */
        String message,
        /** Who approved, when the caller said. */
        String decidedBy,
        /** When the decision was taken; null only if no decision was recorded. */
        Instant decidedAt,
        /** The execution result, present only when an execution was attempted. */
        ActionOutcome outcome,
        /** The proposal that was decided on. */
        ActionProposal proposal) {
}
