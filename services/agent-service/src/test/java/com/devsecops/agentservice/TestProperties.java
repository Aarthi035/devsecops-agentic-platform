package com.devsecops.agentservice;

import com.devsecops.agentservice.config.AgentProperties;

/**
 * Builds {@link AgentProperties} for tests.
 *
 * <p>{@code AgentProperties} is one record with seven components and a nested
 * record for each group, which is the right shape for the binder - one class,
 * one YAML tree - and the wrong shape for a test that only cares about two
 * values. Constructing it by hand in ten places would mean every new
 * configuration group edits ten tests, so the defaults live here once and a
 * test overrides only the part it is about.
 *
 * <p>The defaults are deliberately the <em>test</em> values, not the deployment
 * values: tools in {@code mock} mode and a blank approval token, so a test that
 * forgets to say otherwise cannot reach a real cluster or approve an action.
 */
public final class TestProperties {

    private TestProperties() {
        // Constants and factories only.
    }

    /** The tools the tests want: no network, deterministic fixtures. */
    public static AgentProperties.Tools mockTools() {
        return mockTools("pod-restarts");
    }

    /**
     * Mock tools with a named fixture scenario.
     *
     * @param scenario the fixture {@code MockDiagnosticTools} should answer with
     */
    public static AgentProperties.Tools mockTools(String scenario) {
        return new AgentProperties.Tools(
                "mock",
                "http://prometheus.invalid:9090", "",
                "http://loki.invalid:3100",
                "https://kubernetes.invalid",
                "", "", "",
                "",
                "",
                5, 0, 20, 200, scenario);
    }

    /** Live tools pointing at URLs that cannot resolve, so an accidental call fails loudly. */
    public static AgentProperties.Tools liveTools() {
        return new AgentProperties.Tools(
                "live",
                "http://prometheus.invalid:9090", "",
                "http://loki.invalid:3100",
                "https://kubernetes.invalid",
                "", "", "",
                "",
                "",
                2, 0, 20, 200, "pod-restarts");
    }

    public static AgentProperties.Incident incident() {
        return new AgentProperties.Incident(300, 20, 300, "devsecops");
    }

    public static AgentProperties.Security security() {
        return new AgentProperties.Security("/reports/trivy.json", "/reports", 50, 2_097_152);
    }

    /** No approval token: proposals can be made but not approved. */
    public static AgentProperties.Approval approval() {
        return new AgentProperties.Approval("", 900, 100);
    }

    public static AgentProperties.Approval approval(String token) {
        return new AgentProperties.Approval(token, 900, 100);
    }

    /** A complete object with the mock-mode defaults and the given LLM/GitHub/limits. */
    public static AgentProperties with(AgentProperties.Llm llm,
                                       AgentProperties.Github github,
                                       AgentProperties.Limits limits) {
        return new AgentProperties(
                llm, github, limits,
                mockTools(), incident(), security(), approval());
    }

    /** A complete object with the heuristic engine, no GitHub token and the standard limits. */
    public static AgentProperties defaults() {
        return with(
                new AgentProperties.Llm("heuristic", "http://localhost:11434", "llama3.2:3b", 60, 700, 0.1),
                new AgentProperties.Github("https://api.github.com", "", false),
                new AgentProperties.Limits(12_000, 3, 30, 12));
    }

    /**
     * A complete object whose mock tools answer with a named fixture scenario.
     *
     * @param scenario a fixture in {@code MockDiagnosticTools}, e.g. {@code oom-killed}
     */
    public static AgentProperties mockScenario(String scenario) {
        return withTools(mockTools(scenario));
    }

    /**
     * The same defaults, with an approval token configured - for a test that
     * authorises an action. The default has no token, so approvals are refused.
     *
     * @param token the token {@code POST /approve} must present
     */
    public static AgentProperties withApproval(String token) {
        AgentProperties base = defaults();
        return new AgentProperties(
                base.llm(), base.github(), base.limits(), base.tools(),
                base.incident(), base.security(), new AgentProperties.Approval(token, 900, 100));
    }

    /** The same defaults, with the given tools - for a test that needs a fixture or a budget. */
    public static AgentProperties withTools(AgentProperties.Tools tools) {
        return new AgentProperties(
                new AgentProperties.Llm("heuristic", "http://localhost:11434", "llama3.2:3b", 60, 700, 0.1),
                new AgentProperties.Github("https://api.github.com", "", false),
                new AgentProperties.Limits(12_000, 3, 30, 12),
                tools, incident(), security(), approval());
    }
}
