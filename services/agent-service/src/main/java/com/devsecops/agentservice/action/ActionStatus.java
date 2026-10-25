package com.devsecops.agentservice.action;

/**
 * Where a proposed action is in its life.
 *
 * <p>An enum rather than a set of booleans, because the interesting part of the
 * approval gate is that these are mutually exclusive and most transitions are
 * forbidden. A proposal that has been rejected cannot then be executed; one
 * that expired cannot be approved afterwards. Naming the states makes "the
 * action is in no state at all" unrepresentable.
 *
 * <p>{@link #FAILED} and {@link #INVALID_TARGET} are separated because they have
 * different next steps: a failure is the cluster refusing a change it was asked
 * to make, and an invalid target is the situation having moved on between the
 * proposal and the approval. The second is not a bug and should not be paged on.
 */
public enum ActionStatus {

    /** Waiting for a human. The only state from which an approval can do anything. */
    PENDING,

    /** Approved and accepted by the cluster. */
    EXECUTED,

    /** Approved, attempted, and refused or unreachable. */
    FAILED,

    /** A human said no. */
    REJECTED,

    /** Nobody answered in time. */
    EXPIRED,

    /** Approved, but the target no longer exists or no longer matches the action. */
    INVALID_TARGET;

    /** Whether this state is final - no further decision will change it. */
    public boolean isFinal() {
        return this != PENDING;
    }
}
