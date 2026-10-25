package com.devsecops.agentservice.action;

import com.devsecops.agentservice.error.AgentRequestException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A state-changing action the agent suggests and a human has to authorise.
 *
 * <p>Immutable, and that is the safety property rather than a style choice: the
 * thing that was approved is the thing that gets executed, and nothing can
 * rewrite a proposal between the two. Everything a reviewer needs to decide is on
 * the record - the exact target, the parameters, the {@code reason}, and the
 * {@code evidence} the conclusion rests on - so an approval is an informed one
 * rather than a click on a suggestion.
 *
 * <p>{@link #create} is the only way to build one, and it validates. A
 * namespace or a name that is not a legal Kubernetes name is refused here
 * rather than interpolated into an API path later, which is what stops a
 * proposal from being a way to aim the executor at something other than a
 * workload.
 */
public record ActionProposal(
        /** A short opaque id, used to approve this exact proposal. */
        String id,
        /** What would be done. */
        ActionType type,
        /** The Kubernetes kind of the target, e.g. {@code Deployment}. */
        String kind,
        /** The target's namespace. */
        String namespace,
        /** The target's name. */
        String name,
        /** The action's parameters - {@code replicas}, {@code revision}, or empty. */
        Map<String, Object> parameters,
        /** Why the agent is suggesting this, in one sentence. */
        String reason,
        /** The evidence the suggestion is based on; never empty. */
        List<String> evidence,
        /** The alert or incident this came from, for the audit trail. */
        String subject,
        /** When the proposal was made. */
        Instant createdAt,
        /** When it stops being executable. */
        Instant expiresAt) {

    /** A Kubernetes object name: lower-case alphanumerics and dashes, starting and ending alphanumeric. */
    private static final Pattern K8S_NAME = Pattern.compile("[a-z0-9]([-a-z0-9]*[a-z0-9])?");

    /** Kubernetes' own limit on most object names, and on namespaces when set explicitly. */
    private static final int MAX_NAME_LENGTH = 253;

    public ActionProposal {
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    /**
     * Builds and validates a proposal.
     *
     * @throws AgentRequestException when the type is missing, the target is not
     *         something the action applies to, a name is not a legal Kubernetes
     *         name, or the parameters do not fit the action
     */
    public static ActionProposal create(ActionType type,
                                        String kind,
                                        String namespace,
                                        String name,
                                        Map<String, Object> parameters,
                                        String reason,
                                        List<String> evidence,
                                        String subject,
                                        Instant now,
                                        Duration ttl) {
        if (type == null) {
            throw new AgentRequestException("An action type is required.");
        }
        validateName("namespace", namespace);
        validateName("name", name);
        if (!type.supportsKind(kind)) {
            throw new AgentRequestException(type.wireName() + " does not apply to a " + kind
                    + "; it applies to: " + String.join(", ", type.supportedKinds()) + ".");
        }
        type.validateParameters(parameters);
        Map<String, Object> canonical = canonicalParameters(type, parameters);
        if (reason == null || reason.isBlank()) {
            throw new AgentRequestException("A proposed action must say why it is being proposed.");
        }
        if (evidence == null || evidence.isEmpty()) {
            throw new AgentRequestException("A proposed action must cite the evidence behind it.");
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new AgentRequestException("A proposed action must have a positive time to live.");
        }

        return new ActionProposal(
                UUID.randomUUID().toString(),
                type,
                kind.strip(),
                namespace.strip(),
                name.strip(),
                canonical,
                reason.strip(),
                evidence,
                subject == null ? "" : subject,
                now,
                now.plus(ttl));
    }

    /** Whether this proposal is still waiting for a decision and still executable. */
    public boolean isPendingAt(Instant moment) {
        return !moment.isAfter(expiresAt);
    }

    /** A one-line rendering used in audit entries and responses. */
    public String describe() {
        StringBuilder text = new StringBuilder(type.wireName()).append(' ').append(kind.toLowerCase())
                .append(' ').append(namespace).append('/').append(name);
        if (!parameters.isEmpty()) {
            text.append(' ').append(parameters);
        }
        return text.toString();
    }

    /**
     * Rewrites the parameters into the shape the executor reads.
     *
     * <p>{@link ActionType#validateParameters} accepts {@code "3"} as well as
     * {@code 3}, because a JSON body may carry either and both mean the same
     * thing. The executor, however, reads {@code replicas} and {@code revision}
     * as numbers, and the one place that is allowed to know what a caller sent
     * is here - so the coercion happens once, at creation. What is stored, what
     * the audit trail records and what the executor reads are then the same
     * {@code Integer}, and a string parameter cannot reach a cast it does not
     * fit. Keys the action does not declare are dropped rather than carried
     * along, so the stored proposal contains exactly what it will do.
     */
    private static Map<String, Object> canonicalParameters(ActionType type, Map<String, Object> parameters) {
        Map<String, Object> values = parameters == null ? Map.of() : parameters;
        Map<String, Object> canonical = new LinkedHashMap<>();
        for (String name : type.parameterNames()) {
            if (values.containsKey(name)) {
                canonical.put(name, asInt(values.get(name)));
            }
        }
        return canonical;
    }

    private static Object asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value).strip());
    }

    private static void validateName(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new AgentRequestException("The " + field + " of an action is required.");
        }
        String candidate = value.strip();
        if (candidate.length() > MAX_NAME_LENGTH || !K8S_NAME.matcher(candidate).matches()) {
            throw new AgentRequestException("The " + field + " '" + candidate
                    + "' is not a valid Kubernetes name.");
        }
    }
}
