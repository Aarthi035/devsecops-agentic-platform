package com.devsecops.agentservice.incident;

import com.devsecops.agentservice.action.ActionProposal;
import com.devsecops.agentservice.diagnosis.Diagnosis;
import java.util.List;

/**
 * The answer to {@code POST /alert}.
 *
 * <p>This response is written for whoever is reading an Alertmanager
 * notification at 3am, so it leads with the three things that question needs
 * answered: what the agent concluded ({@code diagnosis}), what it looked at to
 * conclude it ({@code facts} and {@code readings}), and what it wants to do
 * about it ({@code proposedAction}).
 *
 * <p>{@code deduplicated} is the field that explains a short response.
 * Alertmanager re-sends a notification it has not had acknowledged, and every
 * re-send is the same incident; investigating it again would double the tool
 * calls, the audit entries and - with a model - the cost, and would produce a
 * second proposed action for a decision the first response already asked for.
 * A deduplicated answer returns the diagnosis reached the first time, unchanged,
 * with {@code deduplicated: true} so the caller knows why it looks familiar.
 *
 * <p>{@code approvalsEnabled} and {@code executorMode} are reported because a
 * proposed action is a suggestion whose executability depends on the
 * deployment: with no approval token configured, or with the tools in mock
 * mode, approving it would change nothing. Saying so here is cheaper than the
 * reviewer discovering it after clicking approve.
 *
 * <p>{@code facts} are the agent's own observations and are trustworthy;
 * {@code readings} says which tool calls succeeded. Between them they are what
 * makes a diagnosis checkable - "up{service=payment-service} = 0" is a claim a
 * reader can go and verify, and a missing reading is visible as a missing
 * reading rather than as a suspiciously confident conclusion.
 */
public record IncidentAnalysisResponse(
        /** Whether the payload was usable. False means the diagnosis explains why not. */
        boolean accepted,
        /** True when this notification was already analyzed inside the deduplication window. */
        boolean deduplicated,
        /** Alertmanager's group key, or a derived equivalent. */
        String groupKey,
        /** {@code firing} or {@code resolved}. */
        String status,
        /** The primary subject, e.g. {@code deployment devsecops/user-service}. */
        String subject,
        /** How many alerts the payload carried, after the configured maximum was applied. */
        int alertCount,
        /** How many of them the investigator actually reached before the budget ran out. */
        int investigatedAlerts,
        /** The normalized alerts, in the order they were processed. */
        List<NormalizedAlert> alerts,
        /** The agent's own observations. Trusted. */
        List<String> facts,
        /** One entry per tool call, including the ones that failed. */
        List<Reading> readings,
        /** How many tool calls the investigation spent. */
        int toolCalls,
        /** The budget those calls came from. */
        int toolBudget,
        /** True when the investigation stopped because the budget was exhausted. */
        boolean budgetExhausted,
        /** {@code live} or {@code mock}. */
        String toolsMode,
        /** {@code live} or {@code mock}: whether an approved action would do anything. */
        String executorMode,
        /** Whether the deployment can authorise an action at all. */
        boolean approvalsEnabled,
        /** Which engine produced the diagnosis. */
        String provider,
        /** The structured diagnosis. */
        Diagnosis diagnosis,
        /** The action awaiting a human decision, or null when none is proposed. */
        ActionProposal proposedAction,
        /** Everything else the agent wants the caller to know. */
        List<String> notices) {

    public IncidentAnalysisResponse {
        alerts = alerts == null ? List.of() : List.copyOf(alerts);
        facts = facts == null ? List.of() : List.copyOf(facts);
        readings = readings == null ? List.of() : List.copyOf(readings);
        notices = notices == null ? List.of() : List.copyOf(notices);
    }
}
