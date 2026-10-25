package com.devsecops.agentservice.action;

/**
 * What happened when an approved action was executed.
 *
 * <p>Carries its own success flag rather than relying on an exception, because
 * "the action was approved and the cluster refused it" is a result an operator
 * needs to see, not an error to catch. The Kubernetes API refusing a patch is
 * normal - the object may be gone, the field immutable, the credential
 * read-only - and each of those has a different next step, so the message says
 * which.
 *
 * <p>{@code detail} is for the audit trail: the API's own response text, which
 * may name objects but never a credential, since the executor's token is only
 * ever sent as a header.
 */
public record ActionOutcome(
        /** What was attempted, e.g. {@code restart_workload deployment devsecops/user-service}. */
        String action,
        /** Whether the cluster accepted it. */
        boolean executed,
        /** One line for the response body. */
        String message,
        /** The API's answer, for the audit trail. Empty on a client-side refusal. */
        String detail) {

    public ActionOutcome {
        message = message == null ? "" : message;
        detail = detail == null ? "" : detail;
    }

    public static ActionOutcome success(String action, String message, String detail) {
        return new ActionOutcome(action, true, message, detail);
    }

    public static ActionOutcome failure(String action, String message, String detail) {
        return new ActionOutcome(action, false, message, detail);
    }
}
