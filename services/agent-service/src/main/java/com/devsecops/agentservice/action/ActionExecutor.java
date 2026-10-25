package com.devsecops.agentservice.action;

/**
 * Performs an approved state-changing action.
 *
 * <p>Separated from {@link com.devsecops.agentservice.tools.DiagnosticTools} on
 * purpose, and the separation is the guardrail rather than a tidy-up:
 *
 * <ul>
 *   <li>The read-only tools can only build GETs, so there is no method anywhere
 *       in the diagnostic path that could change something.</li>
 *   <li>This interface has exactly one write method and exactly one caller -
 *       {@link ApprovalService}, after a human approval - so "who can change the
 *       cluster" is answerable by reading one call site.</li>
 *   <li>The live implementation uses a <em>different</em> credential from the
 *       diagnostic tools, and that credential is blank unless a deployment
 *       deliberately mounts one. An agent deployed without it can diagnose any
 *       incident and cannot act on any of them.</li>
 * </ul>
 *
 * <p>Neither implementation ever throws. An execution that failed is a
 * {@link ActionOutcome} with {@code executed=false} and a reason, because the
 * caller has to record the failure in the audit trail and report it in the
 * response rather than turn it into a 500 that loses the fact that the action
 * was approved at all.
 */
public interface ActionExecutor {

    /** {@code live} or {@code mock}; recorded on every response and audit entry. */
    String mode();

    /**
     * Executes a proposal.
     *
     * <p>The caller has already validated the proposal, checked its expiry and
     * confirmed the target still exists. An implementation should therefore
     * trust the proposal's shape and refuse only what the cluster refuses - but
     * it must not assume the approval happened, because a future caller might
     * forget.
     */
    ActionOutcome execute(ActionProposal proposal);
}
