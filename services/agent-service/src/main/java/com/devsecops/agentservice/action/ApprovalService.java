package com.devsecops.agentservice.action;

import com.devsecops.agentservice.audit.AuditLog;
import com.devsecops.agentservice.config.AgentProperties;
import com.devsecops.agentservice.error.AgentRequestException;
import com.devsecops.agentservice.error.AgentUnauthorizedException;
import com.devsecops.agentservice.tools.DiagnosticTools;
import com.devsecops.agentservice.tools.ToolResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The approval gate: the only place an action can be authorised, and the only
 * caller of {@link ActionExecutor}.
 *
 * <p>Everything the Phase 9 spec asks for around state-changing actions is in
 * this class, and the checks are in this order on purpose:
 *
 * <ol>
 *   <li><b>Does the action exist?</b> An id that names nothing is a client
 *       error, not a silent success.</li>
 *   <li><b>Is this deployment allowed to approve at all?</b> With no approval
 *       token configured, every approval is refused. The default deployment can
 *       diagnose anything and authorise nothing.</li>
 *   <li><b>Did the caller present the token?</b> Compared in constant time.</li>
 *   <li><b>Has it already been decided?</b> A double-click gets the original
 *       answer back as {@code DUPLICATE} rather than executing twice.</li>
 *   <li><b>Is it still within its time to live?</b> A proposal about a
 *       situation that has since changed is marked {@code EXPIRED}.</li>
 *   <li><b>Does the target still exist?</b> Re-read through the read-only tools
 *       immediately before executing, so "the pod was already gone" is reported
 *       as {@code INVALID_TARGET} instead of as a failed change.</li>
 *   <li><b>Only then</b> is the executor called.</li>
 * </ol>
 *
 * <p>{@code decide} is {@code synchronized}. Approvals are rare - one per
 * incident - and a proposal must be executed exactly once, which is the one
 * property a coarse lock gives for free and a lock-free version has to earn. The
 * cost is that two simultaneous approvals of two different actions serialise;
 * the benefit is that two simultaneous approvals of <em>one</em> action cannot
 * both win.
 *
 * <p>Nothing here re-derives the action's meaning from the request. The request
 * says yes or no; what happens was fixed when the proposal was created and
 * validated.
 */
@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    /**
     * One proposal and its current state.
     *
     * <p>Immutable, and replaced in the map when the state changes, so a reader
     * checking a status and a writer changing it cannot interleave on the same
     * object. See the class comment for why the mutating path is serialised
     * anyway.
     */
    private record Entry(ActionProposal proposal,
                         ActionStatus status,
                         Instant decidedAt,
                         String decidedBy,
                         ActionOutcome outcome,
                         String note) {

        static Entry pending(ActionProposal proposal) {
            return new Entry(proposal, ActionStatus.PENDING, null, null, null, "awaiting approval");
        }
    }

    private final AgentProperties.Approval approval;
    private final ActionExecutor executor;
    private final DiagnosticTools tools;
    private final AuditLog auditLog;
    private final Clock clock;
    private final Map<String, Entry> proposals = new ConcurrentHashMap<>();

    public ApprovalService(AgentProperties properties,
                           ActionExecutor executor,
                           DiagnosticTools tools,
                           AuditLog auditLog,
                           Clock clock) {
        this.approval = properties.approval();
        this.executor = executor;
        this.tools = tools;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    /** {@code live} or {@code mock}: whether an approved action does anything. */
    public String executorMode() {
        return executor.mode();
    }

    /** Whether state changes are possible at all on this deployment. */
    public boolean approvalsEnabled() {
        return approval.hasToken();
    }

    /**
     * Registers a proposal and returns it for the response.
     *
     * <p>Validation is {@link ActionProposal#create}'s: an action that does not
     * apply to the target, an illegal Kubernetes name or a parameter outside its
     * range throws {@link AgentRequestException} rather than becoming a pending
     * proposal that will fail later.
     *
     * <p>An identical proposal that is still awaiting a decision is returned
     * instead of a second one being created. Alertmanager retries and alert
     * groups repeat, and three pending "restart user-service" proposals from one
     * incident would mean the reviewer has to reject two of them - or approve one
     * and wonder whether the other two will fire too.
     */
    public ActionProposal propose(ActionType type,
                                  String kind,
                                  String namespace,
                                  String name,
                                  Map<String, Object> parameters,
                                  String reason,
                                  List<String> evidence,
                                  String subject) {
        Optional<ActionProposal> existing = findPending(type, kind, namespace, name, parameters);
        if (existing.isPresent()) {
            log.info("An identical proposal is already pending for {}; reusing it.", existing.get().describe());
            return existing.get();
        }

        Instant now = clock.instant();
        Duration ttl = Duration.ofSeconds(Math.max(1, approval.defaultTtlSeconds()));
        ActionProposal proposal = ActionProposal.create(
                type, kind, namespace, name, parameters, reason, evidence, subject, now, ttl);

        proposals.put(proposal.id(), Entry.pending(proposal));
        evictOldest();

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("actionId", proposal.id());
        fields.put("action", proposal.describe());
        fields.put("subject", proposal.subject());
        fields.put("reason", proposal.reason());
        fields.put("expiresAt", proposal.expiresAt().toString());
        fields.put("executorMode", executor.mode());
        auditLog.record(AuditLog.ACTION_PROPOSED, fields);

        log.info("Proposed '{}' (id {}, expires {}).", proposal.describe(), proposal.id(), proposal.expiresAt());
        return proposal;
    }

    /** A proposal by id, whether or not it has been decided. */
    public Optional<ActionProposal> find(String actionId) {
        Entry entry = actionId == null ? null : proposals.get(actionId);
        return entry == null ? Optional.empty() : Optional.of(entry.proposal());
    }

    /** Every proposal still waiting for a decision, oldest first. */
    public List<ActionProposal> pending() {
        return proposals.values().stream()
                .filter(entry -> entry.status() == ActionStatus.PENDING)
                .map(Entry::proposal)
                .sorted(Comparator.comparing(ActionProposal::createdAt))
                .toList();
    }

    /**
     * Approves or rejects a proposal.
     *
     * @param approve       true to execute the action, false to refuse it
     * @param presentedToken the token from the request; compared, never logged
     * @param approver      who is asking, for the audit trail; may be blank
     * @throws AgentRequestException   when no action has that id
     * @throws AgentUnauthorizedException when approvals are disabled or the token is wrong
     */
    public synchronized ApprovalResult decide(String actionId,
                                              boolean approve,
                                              String presentedToken,
                                              String approver) {
        Entry entry = actionId == null ? null : proposals.get(actionId);
        if (entry == null) {
            throw new AgentRequestException("There is no proposed action with id '" + actionId + "'.");
        }
        ActionProposal proposal = entry.proposal();

        if (!approval.hasToken()) {
            auditRefusal(actionId, proposal, approver, "no approval token is configured");
            throw new AgentUnauthorizedException(
                    "Approvals are disabled on this deployment: no approval token is configured. Set the "
                            + "approval token to enable them.");
        }
        if (!approval.accepts(presentedToken)) {
            auditRefusal(actionId, proposal, approver, "the approval token was missing or wrong");
            throw new AgentUnauthorizedException(
                    "The approval token is missing or wrong; the action was not executed.");
        }

        Instant now = clock.instant();
        String who = approver == null || approver.isBlank() ? "unknown" : approver.strip();

        // Already decided: an idempotent answer, not a second execution.
        if (entry.status().isFinal()) {
            return new ApprovalResult(actionId, "DUPLICATE", entry.status(),
                    entry.status() == ActionStatus.EXECUTED,
                    "This action was already decided and its status is " + entry.status()
                            + "; nothing further was executed.",
                    entry.decidedBy(), entry.decidedAt(), entry.outcome(), proposal);
        }

        // Past its deadline: the situation it was written about has moved on.
        if (!proposal.isPendingAt(now)) {
            Entry expired = new Entry(proposal, ActionStatus.EXPIRED, now, who, null, "expired");
            proposals.put(actionId, expired);
            auditDecision(actionId, proposal, who, "EXPIRED", ActionStatus.EXPIRED, null);
            log.info("Refused '{}': the proposal expired at {}.", proposal.describe(), proposal.expiresAt());
            return new ApprovalResult(actionId, "EXPIRED", ActionStatus.EXPIRED, false,
                    "The proposal expired at " + proposal.expiresAt()
                            + "; nothing was executed. Diagnose again if the incident is still open.",
                    who, now, null, proposal);
        }

        if (!approve) {
            Entry rejected = new Entry(proposal, ActionStatus.REJECTED, now, who, null, "rejected");
            proposals.put(actionId, rejected);
            auditDecision(actionId, proposal, who, "REJECTED", ActionStatus.REJECTED, null);
            log.info("Rejected '{}' on the instruction of {}.", proposal.describe(), who);
            return new ApprovalResult(actionId, "REJECTED", ActionStatus.REJECTED, false,
                    "The action was rejected by " + who + "; nothing was executed.", who, now, null, proposal);
        }

        // Re-read the target through the read-only tools before changing it.
        // The proposal was written from an earlier snapshot, and between then and
        // now the pod may have been replaced, deleted, or moved namespaces.
        ToolResult target = tools.kubectlGet(proposal.kind(), proposal.namespace(), proposal.name());
        if (!target.ok()) {
            Entry invalid = new Entry(proposal, ActionStatus.INVALID_TARGET, now, who, null,
                    target.summary());
            proposals.put(actionId, invalid);
            auditDecision(actionId, proposal, who, "INVALID_TARGET", ActionStatus.INVALID_TARGET,
                    target.summary());
            log.warn("Refused '{}': the target could not be confirmed - {}", proposal.describe(),
                    target.summary());
            return new ApprovalResult(actionId, "INVALID_TARGET", ActionStatus.INVALID_TARGET, false,
                    "The target could not be confirmed at execution time, so nothing was executed: "
                            + target.summary(),
                    who, now, null, proposal);
        }

        ActionOutcome outcome = executor.execute(proposal);
        ActionStatus status = outcome.executed() ? ActionStatus.EXECUTED : ActionStatus.FAILED;
        proposals.put(actionId, new Entry(proposal, status, now, who, outcome, outcome.message()));

        Map<String, Object> executedFields = new LinkedHashMap<>();
        executedFields.put("actionId", actionId);
        executedFields.put("action", proposal.describe());
        executedFields.put("approvedBy", who);
        executedFields.put("executorMode", executor.mode());
        executedFields.put("executed", outcome.executed());
        executedFields.put("result", outcome.message());
        auditLog.record(AuditLog.ACTION_EXECUTED, executedFields);

        auditDecision(actionId, proposal, who, "APPROVED", status, outcome.message());
        log.info("'{}' was approved by {} and {} ({}).", proposal.describe(), who,
                outcome.executed() ? "executed" : "failed", outcome.message());

        return new ApprovalResult(actionId, "APPROVED", status, outcome.executed(),
                outcome.message(), who, now, outcome, proposal);
    }

    // --- internals ---------------------------------------------------------

    private Optional<ActionProposal> findPending(ActionType type,
                                                 String kind,
                                                 String namespace,
                                                 String name,
                                                 Map<String, Object> parameters) {
        return proposals.values().stream()
                .filter(entry -> entry.status() == ActionStatus.PENDING)
                .map(Entry::proposal)
                .filter(existing -> existing.type() == type
                        && existing.kind().equalsIgnoreCase(kind == null ? "" : kind.strip())
                        && existing.namespace().equalsIgnoreCase(namespace == null ? "" : namespace.strip())
                        && existing.name().equalsIgnoreCase(name == null ? "" : name.strip())
                        && existing.parameters().equals(parameters == null ? Map.of() : parameters))
                .findFirst();
    }

    /** Keeps the store bounded: the oldest proposals go first, whatever their status. */
    private void evictOldest() {
        int max = Math.max(1, approval.maxRetained());
        int excess = proposals.size() - max;
        if (excess <= 0) {
            return;
        }
        proposals.values().stream()
                .sorted(Comparator.comparing(entry -> entry.proposal().createdAt()))
                .limit(excess)
                .forEach(entry -> proposals.remove(entry.proposal().id()));
    }

    private void auditDecision(String actionId,
                               ActionProposal proposal,
                               String who,
                               String decision,
                               ActionStatus status,
                               String note) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("actionId", actionId);
        fields.put("action", proposal.describe());
        fields.put("subject", proposal.subject());
        fields.put("approver", who);
        fields.put("decision", decision);
        fields.put("status", status.name());
        fields.put("note", note);
        auditLog.record(AuditLog.ACTION_DECIDED, fields);
    }

    private void auditRefusal(String actionId, ActionProposal proposal, String who, String why) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("actionId", actionId);
        fields.put("action", proposal.describe());
        fields.put("approver", who == null ? "unknown" : who);
        fields.put("decision", "UNAUTHORIZED");
        fields.put("note", why);
        auditLog.record(AuditLog.ACTION_DECIDED, fields);
    }
}
