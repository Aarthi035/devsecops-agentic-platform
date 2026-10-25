package com.devsecops.agentservice.tools;

/**
 * The step budget for one investigation.
 *
 * <p>The spec's guardrails include a maximum number of steps, and a tool-using
 * loop is exactly the kind of thing that needs one: every step is a network call
 * to a monitoring stack that may be slow, and with a model in the loop, token
 * spend. Making the budget an object rather than a counter passed around means
 * the check and the increment cannot drift apart, and a caller cannot forget to
 * spend.
 *
 * <p>Deliberately mutable and deliberately not thread-safe: one investigation,
 * one thread, one budget. Sharing it would make the limit a race rather than a
 * limit.
 */
public final class ToolBudget {

    private final int limit;
    private int used;

    public ToolBudget(int limit) {
        this.limit = Math.max(0, limit);
    }

    /** Whether another step may be taken. */
    public boolean hasRemaining() {
        return used < limit;
    }

    /**
     * Records one step.
     *
     * @throws IllegalStateException if the budget is already spent - a caller
     *         that spends without checking is a bug in the loop, and silently
     *         allowing it would defeat the limit
     */
    public void spend() {
        if (!hasRemaining()) {
            throw new IllegalStateException("The tool budget of " + limit + " is already spent.");
        }
        used++;
    }

    public int used() {
        return used;
    }

    public int limit() {
        return limit;
    }

    public int remaining() {
        return limit - used;
    }
}
