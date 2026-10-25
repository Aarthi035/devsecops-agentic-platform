package com.devsecops.agentservice.tools;

/**
 * The outcome of one read-only tool call.
 *
 * <p>Deliberately not an exception. A diagnostic tool that fails is a normal
 * event - Prometheus is not deployed yet, Loki has no data for that pod, the pod
 * was deleted a minute ago - and an investigation should continue with whatever
 * the other tools returned rather than abort. Every tool therefore returns a
 * value that says whether it worked, and the caller decides what a missing
 * reading means. That is also what makes "the agent gathered 4 of 5 readings"
 * something a response can report honestly.
 *
 * <p>{@code body} is raw tool output - log lines, JSON, metric values - and is
 * untrusted. It is sanitized by the caller before it becomes evidence. Keeping
 * the tool itself free of that responsibility means one sanitizer pass over
 * everything that reaches an engine, rather than one per tool.
 */
public record ToolResult(
        /** Which tool produced this: {@code queryPrometheus}, {@code kubectlReadOnly}, ... */
        String tool,
        /** Whether the call produced a usable reading. */
        boolean ok,
        /** One line the agent can quote as an observed fact; never null. */
        String summary,
        /** The raw reading, or "" when the call failed. Untrusted. */
        String body,
        /** Why the call failed, phrased for an operator; null on success. */
        String error) {

    public ToolResult {
        summary = summary == null ? "" : summary;
        body = body == null ? "" : body;
    }

    public static ToolResult ok(String tool, String summary, String body) {
        return new ToolResult(tool, true, summary, body, null);
    }

    /**
     * A failed reading.
     *
     * <p>{@code summary} is set to the same text as {@code error} rather than
     * left blank, because a caller that quotes summaries should still record
     * "Prometheus was unreachable" next to the readings that did work.
     */
    public static ToolResult failed(String tool, String summary, String error) {
        return new ToolResult(tool, false, summary, "", error);
    }
}
