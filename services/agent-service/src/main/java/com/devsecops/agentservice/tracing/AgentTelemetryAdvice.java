package com.devsecops.agentservice.tracing;

import com.devsecops.agentservice.action.ApprovalResult;
import com.devsecops.agentservice.analysis.AnalyzeCiResponse;
import com.devsecops.agentservice.diagnosis.Diagnosis;
import com.devsecops.agentservice.incident.IncidentAnalysisResponse;
import com.devsecops.agentservice.security.SecurityAnalysisResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Records the agent's metrics and publishes one trace per request.
 *
 * <p>This is where Phase 10's observability attaches, and it attaches
 * <b>outside</b> the agent rather than inside it. The alternative - calling a
 * tracer from each of the four services - would put a tracing dependency into
 * every constructor, every unit test and every future service, to record facts
 * the response already carries. The advice sees the finished body, so it can
 * read the diagnosis, the confidence, the tool-call count and the tool mode off
 * the same object the caller receives, and the agent's own code stays unaware
 * that anything is being watched.
 *
 * <p>That also means no service changed and no existing test changed when
 * tracing was added, which is the property worth having: instrumentation that
 * requires editing the thing it measures is instrumentation that will be
 * removed the first time it is inconvenient.
 *
 * <p>Its dependencies are {@link ObjectProvider}s, so a sliced test
 * ({@code @WebMvcTest}) that has no telemetry and no publisher gets an advice
 * that does nothing rather than a context that will not start.
 *
 * <p><b>It can never fail a request.</b> Everything is inside one try/catch:
 * a trace is a record of work already done, and losing one must cost nothing.
 */
@ControllerAdvice
public class AgentTelemetryAdvice implements ResponseBodyAdvice<Object> {

    private static final Logger log = LoggerFactory.getLogger(AgentTelemetryAdvice.class);

    /** The routes whose responses are understood. Anything else passes straight through. */
    private static final Set<String> TRACED_PATHS = Set.of(
            "/alert", "/analyze-ci", "/analyze-security", "/approve");

    /** The engine that needs no model, so a request it answers costs no model call. */
    private static final String HEURISTIC = "heuristic";

    private static final int MAX_NOTES = 5;
    private static final int MAX_NOTE_CHARS = 200;

    private final ObjectProvider<AgentTelemetry> telemetry;
    private final ObjectProvider<AgentTracePublisher> publisher;

    public AgentTelemetryAdvice(ObjectProvider<AgentTelemetry> telemetry,
                                ObjectProvider<AgentTracePublisher> publisher) {
        this.telemetry = telemetry;
        this.publisher = publisher;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body,
                                  MethodParameter returnType,
                                  MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request,
                                  ServerHttpResponse response) {
        String endpoint = request.getURI().getPath();
        long durationMillis = durationMillis(request);
        int status = status(response);

        try {
            record(endpoint, body, status, durationMillis);
        } catch (RuntimeException e) {
            // A trace must never be the reason a diagnosis fails to reach its caller.
            log.warn("Could not record telemetry for {}: {}", endpoint, e.toString());
        }
        return body;
    }

    /** Turns one response into metrics and a trace, skipping anything unrecognised. */
    private void record(String endpoint, Object body, int status, long durationMillis) {
        if (TRACED_PATHS.stream().noneMatch(endpoint::startsWith)) {
            return;
        }
        AgentTelemetry meters = telemetry.getIfAvailable();
        AgentTracePublisher sink = publisher.getIfAvailable();
        if (meters == null && sink == null) {
            // A sliced test context: nothing to record into and nowhere to publish.
            return;
        }

        View view = view(endpoint, body, status);
        // The cost is derived, not observed: the request reports what it spent
        // in model and tool calls, and the configured rates turn that into
        // dollars. With no telemetry bean there are no rates, so there is no
        // estimate - which is why this is zero rather than a guess.
        double estimatedCostUsd = meters == null
                ? 0
                : meters.estimateCostUsd(view.modelCalls(), view.toolCalls());
        if (meters != null) {
            meters.recordRequest(endpoint, view.outcome(), durationMillis);
            if (view.confidence() >= 0) {
                meters.recordDiagnosis(endpoint, view.provider(), view.action(), view.confidence());
            }
            meters.recordToolCalls(endpoint, view.toolCalls(), view.toolsMode());
            meters.recordEstimatedCost(endpoint, estimatedCostUsd);
        }
        if (sink != null) {
            sink.publish(new AgentTrace(UUID.randomUUID().toString(), view.name(), view.subject(), endpoint,
                    view.outcome(), view.provider(), view.action(), view.confidence(), view.toolCalls(),
                    view.toolsMode(), durationMillis, estimatedCostUsd, view.notes()));
        }
    }

    /**
     * The trace's fields, read off whichever response shape this endpoint returns.
     *
     * <p>One switch on the endpoint rather than reflection or a shared interface:
     * the four responses are genuinely different documents, and the only place
     * that has to know how each one reports "what did the agent conclude" is
     * here. A fifth route would add a case, and the compiler would not let it be
     * forgotten.
     */
    private View view(String endpoint, Object body, int status) {
        if (body instanceof IncidentAnalysisResponse incident) {
            return incidentView(incident, status);
        }
        if (body instanceof AnalyzeCiResponse analysis) {
            return ciView(analysis, status);
        }
        if (body instanceof SecurityAnalysisResponse security) {
            return securityView(security, status);
        }
        if (body instanceof ApprovalResult approval) {
            return approvalView(approval, status);
        }
        // An error body from AgentExceptionHandler, or a shape this advice has
        // not been taught: record the outcome and nothing else.
        return new View(endpoint, "", "", "", 0.0, 0, 0, "", outcome(status, true), List.of());
    }

    private View incidentView(IncidentAnalysisResponse incident, int status) {
        Diagnosis diagnosis = incident.diagnosis();
        String provider = orBlank(incident.provider());
        String action = diagnosis == null ? "" : orBlank(diagnosis.recommendedAction());
        double confidence = diagnosis == null ? -1 : diagnosis.confidence();
        int toolCalls = incident.toolCalls();

        List<String> notes = new ArrayList<>();
        if (incident.deduplicated()) {
            notes.add("deduplicated against an earlier notification");
        }
        if (incident.budgetExhausted()) {
            notes.add("the tool budget of " + incident.toolBudget() + " calls was exhausted");
        }
        if (incident.proposedAction() != null) {
            notes.add("proposed " + incident.proposedAction().type().wireName()
                    + " for " + incident.proposedAction().kind() + " " + incident.proposedAction().namespace()
                    + "/" + incident.proposedAction().name());
        }
        addNotices(notes, incident.notices());

        return new View("alert", orBlank(incident.subject()), provider, action, confidence, toolCalls,
                modelCalls(provider, diagnosis == null ? 0 : 1),
                orBlank(incident.toolsMode()), outcome(status, incident.accepted()), notes);
    }

    private View ciView(AnalyzeCiResponse analysis, int status) {
        Diagnosis diagnosis = analysis.primaryDiagnosis();
        String provider = orBlank(analysis.provider());
        String action = diagnosis == null ? "" : orBlank(diagnosis.recommendedAction());
        double confidence = diagnosis == null ? -1 : diagnosis.confidence();
        // One log fetch and one diagnosis per analyzed job is the whole of what
        // /analyze-ci spends, so the job count is its step count.
        int toolCalls = analysis.analyzedJobCount();

        List<String> notes = new ArrayList<>();
        if (analysis.comment() != null) {
            notes.add("pull request comment: " + (analysis.comment().posted() ? "posted" : "not posted")
                    + " (" + orBlank(analysis.comment().reason()) + ")");
        }
        if (analysis.truncatedAnyLog()) {
            notes.add("at least one job log was truncated before it was analyzed");
        }

        return new View("analyze-ci", orBlank(analysis.repository()), provider, action, confidence, toolCalls,
                modelCalls(provider, toolCalls), "github", outcome(status, true), notes);
    }

    private View securityView(SecurityAnalysisResponse security, int status) {
        Diagnosis diagnosis = security.diagnosis();
        String provider = orBlank(security.provider());
        String action = diagnosis == null ? "" : orBlank(diagnosis.recommendedAction());
        double confidence = diagnosis == null ? -1 : diagnosis.confidence();

        List<String> notes = new ArrayList<>();
        notes.add("highest severity " + orBlank(security.highestSeverity())
                + " across " + security.findingCount() + " finding(s)");
        addNotices(notes, security.notices());

        // The security path makes exactly one tool call: it reads the report.
        return new View("analyze-security", orBlank(security.reportPath()), provider, action, confidence, 1,
                modelCalls(provider, diagnosis == null ? 0 : 1),
                orBlank(security.toolsMode()), outcome(status, security.accepted()), notes);
    }

    private View approvalView(ApprovalResult approval, int status) {
        String action = approval.proposal() == null ? "" : approval.proposal().type().wireName();
        List<String> notes = new ArrayList<>();
        notes.add("decision " + orBlank(approval.decision()) + ", executed " + approval.executed());
        addNotices(notes, List.of(orBlank(approval.message())));

        // An approval runs no diagnosis steps of its own; the action it authorises
        // is deliberately not counted as a tool call, because it is not a read.
        return new View("approve", orBlank(approval.actionId()), "", action, -1, 0, 0, "",
                outcome(status, approval.executed() || !"APPROVED".equals(approval.decision())), notes);
    }

    /**
     * How many times a model was asked to write in this request.
     *
     * <p>Zero for the heuristic engine: it answers from a table in this
     * repository, and counting it as a model call would inflate the cost
     * estimate for every deployment that runs without a model - which is the
     * default one.
     */
    private static int modelCalls(String provider, int callsForOneDiagnosis) {
        return HEURISTIC.equalsIgnoreCase(provider) ? 0 : callsForOneDiagnosis;
    }

    /** {@code accepted} / {@code rejected} / {@code error}, from the status and the body's own verdict. */
    private static String outcome(int status, boolean accepted) {
        if (status / 100 == 2) {
            return accepted ? "accepted" : "rejected";
        }
        return status / 100 == 4 ? "rejected" : "error";
    }

    /**
     * The HTTP status the response will carry.
     *
     * <p>Read from the servlet response rather than from the {@link
     * ServerHttpResponse}, which has no status accessor of its own. The advice
     * runs after the handler, so by this point the status is settled.
     */
    private static int status(ServerHttpResponse response) {
        if (response instanceof org.springframework.http.server.ServletServerHttpResponse servlet) {
            return servlet.getServletResponse().getStatus();
        }
        return 200;
    }

    /** How long the request took, or {@code -1} when the filter did not see it. */
    private static long durationMillis(ServerHttpRequest request) {
        if (!(request instanceof org.springframework.http.server.ServletServerHttpRequest servlet)) {
            return -1;
        }
        HttpServletRequest raw = servlet.getServletRequest();
        Object started = raw.getAttribute(AgentRequestTimerFilter.START_NANOS_ATTRIBUTE);
        if (!(started instanceof Long startNanos)) {
            return -1;
        }
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static void addNotices(List<String> notes, List<String> notices) {
        if (notices == null) {
            return;
        }
        for (String notice : notices) {
            if (notes.size() >= MAX_NOTES) {
                notes.add("(further notices omitted)");
                return;
            }
            notes.add(truncate(notice));
        }
    }

    private static String truncate(String text) {
        String value = orBlank(text);
        return value.length() <= MAX_NOTE_CHARS ? value : value.substring(0, MAX_NOTE_CHARS) + "...";
    }

    private static String orBlank(String text) {
        return text == null ? "" : text;
    }

    /**
     * The facts a trace is built from, already normalised.
     *
     * <p>{@code modelCalls} is carried rather than the cost itself because the
     * rates live in configuration, and the caller that holds the telemetry bean
     * is the one that can turn a call count into an estimate.
     */
    private record View(
            String name,
            String subject,
            String provider,
            String action,
            double confidence,
            int toolCalls,
            int modelCalls,
            String toolsMode,
            String outcome,
            List<String> notes) {
    }
}
