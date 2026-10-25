package com.devsecops.agentservice.tools;

/**
 * The agent's read-only view of the running system: {@code queryPrometheus},
 * {@code queryLoki}, {@code kubectlReadOnly} and {@code readSecurityReport}.
 *
 * <p>One interface with two implementations, chosen by {@code
 * agent.tools.mode}: {@link HttpDiagnosticTools} calls the real services, and
 * {@link MockDiagnosticTools} answers from fixtures and touches nothing. That
 * switch is what makes Phase 9 demonstrable on a laptop and what lets every test
 * run without a cluster - the alternative, mocking each call site, would mean
 * the tests exercise the mocking rather than the agent.
 *
 * <p><b>Nothing here writes.</b> The live implementation only ever issues GET
 * requests, and the Kubernetes calls go through a ServiceAccount whose Role
 * grants {@code get}/{@code list}/{@code watch} and nothing else. The
 * state-changing actions of Phase 9 are in a separate class with a separate
 * credential, reachable only through the approval gate; that separation is the
 * point of the "read-only by default" guardrail, and it is enforced by both the
 * code and the cluster rather than by convention.
 *
 * <p>Every method returns a {@link ToolResult} rather than throwing, so one
 * unavailable service degrades an investigation instead of failing it.
 */
public interface DiagnosticTools {

    /** {@code live} or {@code mock}; recorded on every response and audit entry. */
    String mode();

    /**
     * Runs a PromQL instant query.
     *
     * @param promql the expression, e.g. {@code up{job="user-service"}}
     */
    ToolResult queryPrometheus(String promql);

    /**
     * Runs a LogQL range query and returns the matching lines.
     *
     * @param logql the selector/expression, e.g. {@code {namespace="devsecops", pod=~"user-.*"}}
     * @param limit the maximum number of lines to return
     */
    ToolResult queryLoki(String logql, int limit);

    /**
     * Reads one object - {@code kubectl get}, as an API call.
     *
     * @param kind      a Kubernetes kind: {@code Pod}, {@code Deployment}, {@code Service}, ...
     * @param namespace the namespace; ignored for cluster-scoped kinds such as {@code Node}
     * @param name      the object's name
     */
    ToolResult kubectlGet(String kind, String namespace, String name);

    /**
     * Reads one object together with the events about it - what {@code kubectl
     * describe} is useful for, which is almost always the events at the bottom.
     */
    ToolResult kubectlDescribe(String kind, String namespace, String name);

    /** Reads a container's log - {@code kubectl logs}. */
    ToolResult kubectlLogs(String namespace, String pod, String container);

    /** Lists the events in a namespace, newest first. */
    ToolResult kubectlEvents(String namespace);

    /**
     * Reads a scanner report from disk.
     *
     * <p>The implementation confines {@code path} to {@code
     * agent.security.report-directory}: a request cannot talk the agent into
     * reading {@code /etc/shadow} by asking for it. That boundary is a property
     * of this method, not of the caller, so no future caller can forget it.
     */
    ToolResult readSecurityReport(String path);
}
