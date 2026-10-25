package com.devsecops.agentservice.action;

import com.devsecops.agentservice.error.AgentRequestException;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The state-changing operations the agent is allowed to <em>propose</em>.
 *
 * <p>Three, and the list is closed on purpose. An enum rather than a free-form
 * verb because every value here has a hand-written implementation, a known set
 * of applicable kinds and a known parameter shape - and a name that arrived in a
 * request body would have none of those.
 *
 * <p>{@link #supportsKind} and {@link #validateParameters} are what make a
 * proposal checkable before it is stored and again before it is executed. A
 * request to roll back a Service is not a risky operation that failed, it is a
 * request that makes no sense, and it should be refused as the latter.
 */
public enum ActionType {

    /** A rolling restart: the same rollout the pod template would produce, without changing it. */
    RESTART_WORKLOAD("restart_workload", 0, 0, List.of("Deployment", "StatefulSet", "DaemonSet")),

    /** Changes {@code spec.replicas}. One parameter: {@code replicas}. */
    SCALE_WORKLOAD("scale_workload", 0, 20, List.of("Deployment", "StatefulSet")),

    /** Restores a previous pod template. Optional parameter: {@code revision}. */
    ROLLBACK_DEPLOYMENT("rollback_deployment", 0, 100, List.of("Deployment"));

    private final String wireName;
    private final int minReplicas;
    private final int maxReplicas;
    private final List<String> kinds;

    ActionType(String wireName, int minReplicas, int maxReplicas, List<String> kinds) {
        this.wireName = wireName;
        this.minReplicas = minReplicas;
        this.maxReplicas = maxReplicas;
        this.kinds = kinds;
    }

    /**
     * The name used in JSON, e.g. {@code scale_workload}.
     *
     * <p>{@code @JsonValue} makes this the enum's serialised form, so a response
     * carries the same word a caller would send - {@code restart_workload}, not
     * {@code RESTART_WORKLOAD}. The wire name is the contract; the enum constant
     * is an implementation detail of this class.
     */
    @JsonValue
    public String wireName() {
        return wireName;
    }

    /** The kinds this action applies to. */
    public List<String> supportedKinds() {
        return kinds;
    }

    /** Whether this action makes sense against a workload of the given kind. */
    public boolean supportsKind(String kind) {
        if (kind == null) {
            return false;
        }
        String normalised = kind.strip().toLowerCase(Locale.ROOT);
        return kinds.stream().anyMatch(candidate -> candidate.toLowerCase(Locale.ROOT)
                .equals(normalised.endsWith("s") && normalised.length() > 1
                        ? normalised.substring(0, normalised.length() - 1)
                        : normalised));
    }

    /** Parses a wire name, or the enum name, case-insensitively. */
    public static Optional<ActionType> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String candidate = raw.strip();
        for (ActionType type : values()) {
            if (type.wireName.equalsIgnoreCase(candidate) || type.name().equalsIgnoreCase(candidate)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }

    /**
     * Checks the parameters for this action.
     *
     * <p>Refuses rather than clamps. A scale request for 500 replicas that is
     * silently reduced to 20 has quietly done something other than what was
     * asked, and the operator who approved "20" would never know 500 was
     * requested.
     */
    public void validateParameters(Map<String, Object> parameters) {
        Map<String, Object> values = parameters == null ? Map.of() : parameters;
        switch (this) {
            case RESTART_WORKLOAD -> {
                if (!values.isEmpty()) {
                    throw new AgentRequestException("restart_workload takes no parameters, but got: "
                            + values.keySet() + ".");
                }
            }
            case SCALE_WORKLOAD -> {
                int replicas = requireInt(values, "replicas");
                if (replicas < minReplicas || replicas > maxReplicas) {
                    throw new AgentRequestException("replicas must be between " + minReplicas + " and "
                            + maxReplicas + ", but was " + replicas + ".");
                }
            }
            case ROLLBACK_DEPLOYMENT -> {
                if (values.containsKey("revision")) {
                    int revision = requireInt(values, "revision");
                    if (revision < 1) {
                        throw new AgentRequestException("revision must be a positive number, but was "
                                + revision + ".");
                    }
                }
            }
        }
    }

    private static int requireInt(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value == null) {
            throw new AgentRequestException("The parameter '" + key + "' is required for this action.");
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = String.valueOf(value).strip();
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new AgentRequestException("The parameter '" + key + "' must be a whole number, but was '"
                    + text + "'.");
        }
    }

    /** Whether a parameter is meaningful for this action; used to reject unknown keys. */
    public List<String> parameterNames() {
        return switch (this) {
            case RESTART_WORKLOAD -> List.of();
            case SCALE_WORKLOAD -> List.of("replicas");
            case ROLLBACK_DEPLOYMENT -> List.of("revision");
        };
    }
}
