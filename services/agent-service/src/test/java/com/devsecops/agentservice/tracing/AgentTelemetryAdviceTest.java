package com.devsecops.agentservice.tracing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.devsecops.agentservice.action.ActionProposal;
import com.devsecops.agentservice.action.ActionStatus;
import com.devsecops.agentservice.action.ActionType;
import com.devsecops.agentservice.action.ApprovalResult;
import com.devsecops.agentservice.analysis.AnalyzeCiResponse;
import com.devsecops.agentservice.config.TracingProperties;
import com.devsecops.agentservice.diagnosis.Diagnosis;
import com.devsecops.agentservice.incident.IncidentAnalysisResponse;
import com.devsecops.agentservice.security.SecurityAnalysisResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;

/**
 * What one finished request turns into: a trace, and the metrics behind the
 * Grafana agent panel.
 *
 * <p>One test per response shape, because the advice's real work is knowing how
 * each of the four responses reports "what did the agent conclude" - an incident
 * carries its tool-call count, a CI analysis carries a job count instead, the
 * security path always spends exactly one read, and an approval spends none
 * because it is a decision rather than an investigation.
 *
 * <p>The last test is the one that matters most: a trace is a record of work
 * already done, so a publisher that throws must not cost the caller an answer.
 */
class AgentTelemetryAdviceTest {

    private static final String METRIC_REQUESTS = "agent.requests";
    private static final String METRIC_TOOL_CALLS = "agent.tool_calls";
    private static final String METRIC_COST = "agent.estimated_cost_usd";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AgentTelemetry telemetry = new AgentTelemetry(registry, properties());
    private final CapturingPublisher publisher = new CapturingPublisher();
    private final AgentTelemetryAdvice advice = new AgentTelemetryAdvice(provider(telemetry), provider(publisher));

    @Test
    void tracesAnIncidentWithItsToolCallsAndProposedAction() {
        write("/alert", incident("heuristic", false, false, proposal()), 200);

        AgentTrace trace = onlyTrace();
        assertThat(trace.name()).isEqualTo("alert");
        assertThat(trace.subject()).isEqualTo("deployment devsecops/user-service");
        assertThat(trace.provider()).isEqualTo("heuristic");
        assertThat(trace.recommendedAction()).isEqualTo("restart");
        assertThat(trace.confidence()).isEqualTo(0.8);
        assertThat(trace.toolCalls()).isEqualTo(5);
        assertThat(trace.toolsMode()).isEqualTo("mock");
        assertThat(trace.outcome()).isEqualTo("accepted");
        assertThat(trace.notes()).anySatisfy(note ->
                assertThat(note).contains("proposed restart_workload for Deployment devsecops/user-service"));

        // The heuristic engine calls no model, so the estimate is tool calls only.
        assertThat(trace.estimatedCostUsd()).isEqualTo(0.0001);
        assertThat(registry.get(METRIC_REQUESTS).tags("endpoint", "/alert", "outcome", "accepted")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get(METRIC_TOOL_CALLS).tags("endpoint", "/alert", "mode", "mock")
                .counter().count()).isEqualTo(5.0);
        assertThat(registry.get(METRIC_COST).tag("endpoint", "/alert").counter().count())
                .isCloseTo(0.0001, within(1e-9));
    }

    @Test
    void notesADeduplicationAndAnExhaustedBudget() {
        write("/alert", incident("heuristic", true, true, null), 200);

        assertThat(onlyTrace().notes())
                .anySatisfy(note -> assertThat(note).contains("deduplicated"))
                .anySatisfy(note -> assertThat(note).contains("budget of 12 calls"));
    }

    @Test
    void chargesAModelCallWhenTheEngineWasNotTheHeuristicOne() {
        write("/alert", incident("ollama", false, false, null), 200);

        // 1 model call at 0.0002 plus 5 tool calls at 0.00002.
        assertThat(onlyTrace().estimatedCostUsd()).isEqualTo(0.0003);
    }

    @Test
    void treatsTheAnalyzedJobCountAsTheStepCountForCi() {
        write("/analyze-ci", ciAnalysis(), 200);

        AgentTrace trace = onlyTrace();
        assertThat(trace.name()).isEqualTo("analyze-ci");
        assertThat(trace.subject()).isEqualTo("example/platform");
        assertThat(trace.toolCalls()).isEqualTo(1);
        assertThat(trace.toolsMode()).isEqualTo("github");
        assertThat(trace.notes()).anySatisfy(note ->
                assertThat(note).contains("pull request comment: not posted"));
    }

    @Test
    void countsTheSingleReportReadForASecurityAnalysis() {
        write("/analyze-security", securityAnalysis(), 200);

        AgentTrace trace = onlyTrace();
        assertThat(trace.name()).isEqualTo("analyze-security");
        assertThat(trace.subject()).isEqualTo("/reports/trivy.json");
        assertThat(trace.toolCalls()).isEqualTo(1);
        assertThat(trace.notes()).anySatisfy(note ->
                assertThat(note).contains("highest severity CRITICAL across 3 finding(s)"));
    }

    @Test
    void recordsAnApprovalAsADecisionRatherThanAnInvestigation() {
        write("/approve", approval(), 200);

        AgentTrace trace = onlyTrace();
        assertThat(trace.name()).isEqualTo("approve");
        assertThat(trace.recommendedAction()).isEqualTo("restart_workload");
        assertThat(trace.confidence()).isEqualTo(-1);
        assertThat(trace.toolCalls()).isZero();
        assertThat(trace.outcome()).isEqualTo("accepted");
        assertThat(trace.notes()).anySatisfy(note ->
                assertThat(note).contains("decision APPROVED, executed true"));
        // Nothing is recorded for a route that is not traced.
        assertThat(registry.find(METRIC_TOOL_CALLS).counter()).isNull();
    }

    @Test
    void recordsAMetricForAnErrorBodyWithoutGuessingAtItsShape() {
        write("/alert", Map.of("error", "the payload could not be read"), 400);

        AgentTrace trace = onlyTrace();
        assertThat(trace.name()).isEqualTo("/alert");
        assertThat(trace.outcome()).isEqualTo("rejected");
        assertThat(trace.confidence()).isEqualTo(0.0);
    }

    @Test
    void ignoresRoutesThatAreNotTraced() {
        write("/actuator/health", Map.of("status", "UP"), 200);

        assertThat(publisher.traces).isEmpty();
        assertThat(registry.find(METRIC_REQUESTS).counter()).isNull();
    }

    @Test
    void aPublisherThatThrowsDoesNotCostTheCallerTheResponse() {
        AgentTelemetryAdvice brittle = new AgentTelemetryAdvice(provider(telemetry), provider(new ThrowingPublisher()));
        Object body = incident("heuristic", false, false, null);

        assertThatCode(() -> write(brittle, "/alert", body, 200)).doesNotThrowAnyException();
    }

    private void write(String path, Object body, int status) {
        write(advice, path, body, status);
    }

    private static void write(AgentTelemetryAdvice advice, String path, Object body, int status) {
        Object returned = advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null,
                request(path), response(status));
        assertThat(returned).isSameAs(body);
    }

    private AgentTrace onlyTrace() {
        assertThat(publisher.traces).hasSize(1);
        return publisher.traces.get(0);
    }

    // --- the four response shapes, built by hand ---------------------------------

    private static IncidentAnalysisResponse incident(String provider, boolean deduplicated,
                                                     boolean budgetExhausted, ActionProposal proposed) {
        return new IncidentAnalysisResponse(
                true, deduplicated, "group-key", "firing", "deployment devsecops/user-service",
                1, 1, List.of(), List.of("up{service=\"user-service\"} = 0"), List.of(),
                5, 12, budgetExhausted, "mock", "mock", false, provider,
                diagnosis("restart", 0.8), proposed, List.of("one notice"));
    }

    private static AnalyzeCiResponse ciAnalysis() {
        Diagnosis diagnosis = diagnosis("restart", 0.8);
        return new AnalyzeCiResponse(
                "example/platform", 1234L, "CI", "https://github.com/example/platform/actions/runs/1234",
                "feature/x", "9c65d35abcdef", "failure",
                List.of(new AnalyzeCiResponse.JobOutcome(9L, "build", "https://example/job/9", "failure",
                        120, false, diagnosis)),
                diagnosis, 1, 1, "heuristic",
                new AnalyzeCiResponse.CommentOutcome(false, 7, null, "No GitHub token is configured"));
    }

    private static SecurityAnalysisResponse securityAnalysis() {
        return new SecurityAnalysisResponse(
                true, "/reports/trivy.json", "user-service:1.0.0", 3,
                Map.of("CRITICAL", 1, "HIGH", 2), "CRITICAL", 3, false, "mock", "heuristic",
                diagnosis("rollback", 0.8), List.of(), List.of());
    }

    private static ApprovalResult approval() {
        ActionProposal proposal = proposal();
        return new ApprovalResult(proposal.id(), "APPROVED", ActionStatus.EXECUTED, true,
                "the Deployment was restarted", "operator", Instant.now(), null, proposal);
    }

    private static ActionProposal proposal() {
        return ActionProposal.create(ActionType.RESTART_WORKLOAD, "Deployment", "devsecops", "user-service",
                Map.of(), "the container is crash-looping", List.of("restarts=12"),
                "deployment devsecops/user-service", Instant.now(), Duration.ofMinutes(15));
    }

    private static Diagnosis diagnosis(String action, double confidence) {
        return new Diagnosis("the container is crash-looping", List.of("restarts=12"),
                "restart the Deployment", action, confidence);
    }

    // --- servlet plumbing --------------------------------------------------------

    private static ServerHttpRequest request(String path) {
        ServletServerHttpRequest request = mock(ServletServerHttpRequest.class);
        when(request.getURI()).thenReturn(URI.create(path));
        HttpServletRequest raw = mock(HttpServletRequest.class);
        when(raw.getAttribute(AgentRequestTimerFilter.START_NANOS_ATTRIBUTE)).thenReturn(System.nanoTime());
        when(request.getServletRequest()).thenReturn(raw);
        return request;
    }

    private static ServerHttpResponse response(int status) {
        ServletServerHttpResponse response = mock(ServletServerHttpResponse.class);
        HttpServletResponse raw = mock(HttpServletResponse.class);
        when(raw.getStatus()).thenReturn(status);
        when(response.getServletResponse()).thenReturn(raw);
        return response;
    }

    private static TracingProperties properties() {
        return new TracingProperties("log", "http://langfuse.invalid:3000", "", "",
                5, "release", "test", 0.0002, 0.00002);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T instance) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(instance);
        return provider;
    }

    private static final class CapturingPublisher implements AgentTracePublisher {
        private final List<AgentTrace> traces = new ArrayList<>();

        @Override
        public String mode() {
            return "capture";
        }

        @Override
        public void publish(AgentTrace trace) {
            traces.add(trace);
        }
    }

    private static final class ThrowingPublisher implements AgentTracePublisher {
        @Override
        public String mode() {
            return "throwing";
        }

        @Override
        public void publish(AgentTrace trace) {
            throw new IllegalStateException("the trace store is unreachable");
        }
    }
}
