package com.devsecops.agentservice.diagnosis;

import com.devsecops.agentservice.logs.LogSanitizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * A diagnosis written from a rule table, with no model involved.
 *
 * <p>This is the default engine ({@code AGENT_LLM_PROVIDER=heuristic}) and the
 * one CI uses. That is not a shortcut around the LLM - it is what makes the
 * feature testable and dependable: a GitHub runner has no Ollama, so a pipeline
 * that needed a model to answer would be a pipeline that cannot check its own
 * work. The rule table also gives the evaluation in Phase 10 a fixed baseline to
 * score the model against.
 *
 * <p>Each rule is a signature plus the four things the spec asks a diagnosis to
 * carry: the root cause, the evidence, the fix and a confidence. Evidence is
 * never invented - it is the log lines that actually matched, so a reader can
 * check the claim against the log instead of trusting it.
 *
 * <p>Rules are ordered most-specific first. The first match wins, which is why
 * "No space left on device" is above the generic failure rules: a disk-full
 * build also prints a test failure, and reporting the test failure would be
 * technically true and completely useless.
 */
@Component
@ConditionalOnProperty(name = "agent.llm.provider", havingValue = "heuristic", matchIfMissing = true)
public class HeuristicDiagnosisEngine implements DiagnosisEngine {

    /** Enough to corroborate a cause without turning the response into a log dump. */
    private static final int MAX_EVIDENCE_LINES = 5;

    /** One very long log line must not dominate the response or the audit record. */
    private static final int MAX_EVIDENCE_CHARS = 300;

    /**
     * When nothing matches, the fix is honest about the two ways forward rather
     * than guessing: read the run, or let a model read it.
     */
    private static final String UNKNOWN_FIX =
            "Read the first failing step in the linked run. If the cause is not obvious, "
                    + "either add the signature to HeuristicDiagnosisEngine's rule table or re-run the agent "
                    + "with AGENT_LLM_PROVIDER=ollama so a model can read the log.";

    /** One signature and the diagnosis that follows from it. */
    private record Rule(
            String id,
            Pattern pattern,
            String rootCause,
            String suggestedFix,
            String recommendedAction,
            double confidence) {
    }

    private final LogSanitizer sanitizer;

    public HeuristicDiagnosisEngine(LogSanitizer sanitizer) {
        this.sanitizer = sanitizer;
    }

    /**
     * The CI rule table. Patterns are case-insensitive and searched, not
     * anchored, so a signature is found wherever the tool chain chose to print
     * it.
     */
    private static final List<Rule> CI_RULES = List.of(
            rule("disk-full",
                    "no space left on device|no space left on the device",
                    "The build ran out of disk space on the runner.",
                    "Free space before the build: remove stale Docker images and build caches "
                            + "(docker system prune -a), clear the Maven repository cache, and lower the "
                            + "size of any artifact the job writes to disk.",
                    "none",
                    0.85),
            rule("docker-unavailable",
                    "cannot connect to the docker daemon|is the docker daemon running|docker: command not found",
                    "The job tried to use Docker, but no Docker daemon was reachable from the runner.",
                    "Enable the Docker service for the job (on a self-hosted runner, start Docker Desktop "
                            + "or dockerd first) or drop the Docker step - building an image is not needed to "
                            + "compile and test.",
                    "none",
                    0.85),
            rule("out-of-memory",
                    "out ?of ?memory|oomkilled|java\\.lang\\.OutOfMemoryError|exit code 137|killed process",
                    "The build was killed for using too much memory.",
                    "Raise the memory available to the job, or reduce the build's footprint: cap the JVM "
                            + "heap (-XX:MaxRAMPercentage), build modules in a smaller order, and avoid "
                            + "running the whole test suite and the package step in one JVM.",
                    "none",
                    0.8),
            rule("maven-dependency",
                    "could not resolve dependencies|could not find artifact|non-resolvable parent pom|"
                            + "failed to collect dependencies|failed to read artifact descriptor|unknown host repo",
                    "Maven could not download one of the project's dependencies.",
                    "Check the dependency's coordinates and version in the failing module's pom.xml, confirm "
                            + "the repository that hosts it is declared, and re-run - a transient registry or "
                            + "network failure looks identical to a wrong coordinate and only a re-run tells "
                            + "the two apart.",
                    "retry",
                    0.8),
            rule("compile-error",
                    "compilation failure|cannot find symbol|compilation error|\\[ERROR\\] .*\\.java:\\[\\d+",
                    "The code did not compile.",
                    "Fix the reported compilation error: the failing file and line are in the evidence above. "
                            + "A 'cannot find symbol' usually means a missing import, a typo, or a dependency "
                            + "that was not declared.",
                    "none",
                    0.85),
            rule("test-failure",
                    "there are test failures|tests run:.*failures: [1-9]|tests run:.*errors: [1-9]|"
                            + "failed tests:|assertionerror|test failed",
                    "The build failed because one or more tests failed, not because of a compilation or "
                            + "infrastructure problem.",
                    "Reproduce the failing test locally (mvn test -Dtest=TheFailingTest), read the assertion "
                            + "message in the evidence above, and fix either the production code or the test's "
                            + "expectation - do not disable the test to make the pipeline green.",
                    "none",
                    0.8),
            rule("python-dependency",
                    "modulenotfounderror|importerror: no module named",
                    "A Python step failed because a module was not installed.",
                    "Add the missing package to the requirements file that the job installs, and add the "
                            + "installation step before the step that imports it.",
                    "none",
                    0.8),
            rule("node-dependency",
                    "npm err!|enoent.*package\\.json|yarn error",
                    "A Node step failed, usually because dependencies were missing or package.json did not "
                            + "exist where the step expected it.",
                    "Run the install step (npm ci) before the failing step and make sure the step runs in the "
                            + "directory that holds package.json (working-directory).",
                    "none",
                    0.7),
            rule("registry-auth",
                    "denied: requested access to the resource is denied|unauthorized: authentication required|"
                            + "manifest unknown|unauthorized",
                    "The job could not authenticate to the container registry.",
                    "Confirm the registry credentials are configured (GHCR uses the built-in GITHUB_TOKEN with "
                            + "packages: write), that the token has not expired, and that the image name's "
                            + "namespace matches the account that is logging in.",
                    "none",
                    0.8),
            rule("network-dns",
                    "no such host|temporary failure in name resolution|dial tcp.*connection refused|"
                            + "connection refused|connection timed out|econnrefused",
                    "The job could not reach a host or service it depends on.",
                    "Check the address the step uses: a wrong service name, the wrong port, or a service that "
                            + "is not up yet. If it is a service container, wait for it to become healthy "
                            + "before the step runs.",
                    "retry",
                    0.7),
            rule("gitleaks-secret",
                    "gitleaks|leaks found|secret detected|possible secret",
                    "The secret-scanning step found a secret in the repository or the diff.",
                    "Remove the value from the file, rotate the credential immediately (a committed secret must "
                            + "be assumed compromised), and read the value from a GitHub secret at run time "
                            + "instead.",
                    "none",
                    0.8),
            rule("trivy-vulnerability",
                    "trivy|vulnerabilit|HIGH,CRITICAL|fixed version",
                    "The image or dependency scan failed its severity gate.",
                    "Upgrade the flagged package or base image to a version that carries the fix, rebuild the "
                            + "image, and re-run the scan. If no fix exists yet, record the exception with an "
                            + "owner and an expiry in the scan's ignore file rather than lowering the gate.",
                    "none",
                    0.75),
            rule("kubernetes-rollout",
                    "error from server|failed to apply|no matches for kind|rollout.*not.*complete|"
                            + "containercreating|imagepullbackoff|crashloopbackoff|back-off pulling image",
                    "A Kubernetes step failed: a manifest was rejected, or a workload did not become ready.",
                    "Read the failing object with kubectl describe and kubectl logs, then fix the manifest "
                            + "or the image reference. ImagePullBackOff usually means the image tag does not "
                            + "exist in the registry or the registry credentials are missing.",
                    "none",
                    0.7),
            rule("permission-denied",
                    "permission denied|eacces|operation not permitted",
                    "A step hit a file or command it was not permitted to use.",
                    "Fix the ownership or the mode of the path involved, or the privileges the step needs. On "
                            + "a runner that runs the job as a non-root user, a step that writes outside the "
                            + "workspace is the usual cause.",
                    "none",
                    0.6),
            rule("timeout",
                    "timed out|timeout of|the job.*exceeded the maximum execution time|cancelled"
                            + "(?=.*(?:timeout|exceeded))",
                    "A step or the job itself ran past its time limit.",
                    "Find what hung - often a step waiting on a service that never becomes ready - and either "
                            + "fix the wait or raise timeout-minutes for that job.",
                    "retry",
                    0.6));

    /**
     * The table the incident and security paths use.
     *
     * <p>Held apart from {@link #CI_RULES} because the two subject types collide:
     * a crash loop and an {@code OOMKilled} container appear both in a CI job
     * that applies manifests and in the evidence an incident investigation
     * gathers. One shared ordering cannot answer both - checked in the CI order,
     * a crash loop matches {@code kubernetes-rollout} first and is reported as
     * "a workload did not become ready", with no action to propose, which is the
     * exact behaviour Phase 9 exists to provide. So the CI path keeps its table
     * unchanged, and this path checks the runtime signatures first.
     *
     * <p>These fire on evidence that only exists for a running cluster -
     * Kubernetes events, container states and the metrics an investigation
     * gathers - plus the security rules below. The Phase 9 signatures come
     * first, then the CI rules are still consulted as a fallback by {@code
     * diagnoseEvidence}, so an incident whose only signature is a familiar one
     * ("no space left on device" on a node) still gets an answer.
     */
    private static final List<Rule> RUNTIME_RULES = List.of(
            rule("crash-loop",
                    "crashloopbackoff|back-off restarting failed container|restarting failed container",
                    "A container starts and exits repeatedly, so Kubernetes is applying an exponential back-off "
                            + "instead of keeping it running.",
                    "Read the previous container's log (kubectl logs --previous) - a crash loop is almost always "
                            + "the application exiting on a startup error, a missing configuration value, or a "
                            + "failing connection to a dependency. Fix the cause; restarting the pod only resets "
                            + "the counter.",
                    "restart",
                    0.8),
            rule("oom-killed-container",
                    "oomkilled|container.*was oom killed|reason: oomkilled",
                    "A container was killed by the kernel for exceeding the memory limit set on it.",
                    "Raise the container's memory limit if the application genuinely needs more, or fix the leak "
                            + ": a heap that grows without bound is a bug, and raising the limit only postpones "
                            + "the kill.",
                    "scale",
                    0.8),
            rule("probe-failed",
                    "unhealthy|liveness probe failed|readiness probe failed|probe failed|probe errored",
                    "A container is running but failing its health probe, so Kubernetes has stopped routing "
                            + "traffic to it or is restarting it.",
                    "Check the probe's path and port against what the application actually serves, then look at "
                            + "the container's own log for what it is waiting on - a probe that points at the wrong "
                            + "port or a readiness gate that never opens both look like this.",
                    "restart",
                    0.75),
            rule("container-config-error",
                    "createcontainerconfigerror|couldn't find key|configmap .* not found|secret .* not found",
                    "The container could not be started because a value it needs from a ConfigMap or a Secret is "
                            + "missing or misnamed.",
                    "Compare the keys the Deployment references (env, envFrom, volume) against the keys the "
                            + "ConfigMap and Secret actually contain. A mistyped key name is the usual cause, and "
                            + "the fix is in the manifest, not in the application.",
                    "rollback",
                    0.8),
            rule("service-not-scrapeable",
                    "not scrapeable|up == 0|up=0|no scrapeable target|prometheus has failed to scrape",
                    "The service is not answering Prometheus's scrapes, which means the process is down, "
                            + "listening on a different port than the Service targets, or blocking its own "
                            + "actuator endpoint.",
                    "Check the pod's state and its recent log first. If the process is up, the likely cause is a "
                            + "port mismatch between the Deployment's containerPort, the Service's targetPort and "
                            + "the value Prometheus scrapes.",
                    "restart",
                    0.7),
            rule("elevated-error-rate",
                    "returning 5xx|error rate|server errors|http 5[0-9][0-9] responses",
                    "The service is answering a rising share of requests with server errors while still being "
                            + "up, which points at a failing dependency or a code path rather than a dead pod.",
                    "Read the service's log around the first error and correlate it with the dependency it calls. "
                            + "A 5xx that starts suddenly and affects one endpoint is usually a downstream timeout "
                            + "or a bad deployment; rolling back is a reasonable first move if it began at a "
                            + "deploy.",
                    "rollback",
                    0.65),
            rule("high-latency",
                    "high latency|latency above|p9[59] latency|slow response|response time",
                    "Requests are succeeding but taking far longer than usual, so the service is saturated or "
                            + "waiting on a slow dependency.",
                    "Compare the latency rise with CPU, heap and connection-pool saturation for the same service. "
                            + "If the process is saturated, scaling out helps; if it is waiting, the dependency is "
                            + "the thing to fix.",
                    "scale",
                    0.65),

            // --- Phase 9: security-report signatures -------------------------
            //
            // These fire on the fact lines SecurityAnalysisService generates
            // from a scanner report, not on the finding text itself. That is
            // deliberate: the facts are the agent's own count of what the report
            // contained, so the rule keys off a number the agent verified rather
            // than a word that happens to appear in a package name. The three are
            // ordered worst-first, and the clean-scan rule is last so a report
            // with findings can never be read as empty.
            rule("security-critical-finding",
                    "highest severity present is critical",
                    "The scan found at least one CRITICAL vulnerability. CRITICAL is the scanner's highest "
                            + "severity, meaning a remotely reachable flaw with a published fix, or one that is "
                            + "being exploited.",
                    "Treat this as blocking: upgrade every package the evidence names to its fixed version, "
                            + "rebuild the image, and re-run the scan. A finding with no fixed version named is a "
                            + "mitigation decision rather than an upgrade - record who accepted it, why, and when "
                            + "it is due to be revisited, instead of lowering the gate.",
                    "none",
                    0.8),
            rule("security-findings",
                    "contains [1-9]\\d* finding\\(s\\)",
                    "The scan reported vulnerabilities in the image or its dependencies, none of them CRITICAL.",
                    "Work through the evidence list from the top: each line names a package, the version "
                            + "installed and the version that fixes it. Most of these are dependency or base-image "
                            + "bumps. Re-scan after the upgrade - upgrading one package regularly clears several "
                            + "findings at once.",
                    "none",
                    0.6),
            rule("security-clean-scan",
                    "highest severity present is none",
                    "The scan completed and reported no findings at any severity. There is nothing to remediate "
                            + "in this report.",
                    "Nothing to do. Note that a clean scan is a statement about the scanner's database at the "
                            + "moment it ran and about the targets it was pointed at - it is not a statement that "
                            + "the image is free of vulnerabilities.",
                    "none",
                    0.9));

    @Override
    public String provider() {
        return "heuristic";
    }

    @Override
    public Diagnosis diagnose(DiagnosisRequest request) {
        return diagnoseText(request.logs() == null ? "" : request.logs());
    }

    /**
     * The Phase 9 path: an alert's or a security report's evidence.
     *
     * <p>The runtime table is checked first and the CI table second, because an
     * incident's evidence is usually - but not always - about a running cluster.
     * A signature from either table is still a signature; what the order decides
     * is which of the two answers a log that matches both gets, and for an
     * incident that has to be the incident one.
     */
    @Override
    public Diagnosis diagnoseEvidence(EvidenceBundle bundle) {
        String text = bundle.asText();
        Diagnosis runtime = firstMatch(text, RUNTIME_RULES);
        return runtime != null ? runtime : diagnoseText(text);
    }

    /**
     * The CI table applied to a block of text.
     *
     * <p>Not shared with the incident path: the two tables are ordered
     * differently on purpose, so what they have in common is the matching, not
     * the order.
     */
    private Diagnosis diagnoseText(String text) {
        Diagnosis match = firstMatch(text, CI_RULES);
        return match != null ? match : unknown(text);
    }

    /**
     * The first rule in {@code rules} whose signature appears in {@code text}.
     *
     * @return the diagnosis, or null when no signature matched, so the caller
     *         decides what "nothing matched" means on its own path
     */
    private static Diagnosis firstMatch(String text, List<Rule> rules) {
        String logs = text == null ? "" : text;
        for (Rule rule : rules) {
            List<String> evidence = collectEvidence(rule.pattern(), logs);
            if (!evidence.isEmpty()) {
                return new Diagnosis(
                        rule.rootCause(),
                        evidence,
                        rule.suggestedFix(),
                        rule.recommendedAction(),
                        rule.confidence());
            }
        }
        return null;
    }

    /**
     * What to say when nothing matched.
     *
     * <p>Saying so, and quoting the end of the log, is more useful than the
     * nearest guess: a wrong cause with a high confidence is worse than no cause
     * at all.
     */
    private Diagnosis unknown(String text) {
        String logs = text == null ? "" : text;
        return new Diagnosis(
                "The job failed, but its log contains none of the signatures this agent knows. "
                        + "The last lines of the log are quoted as evidence.",
                sanitizer.lastNonBlankLines(logs, MAX_EVIDENCE_LINES),
                UNKNOWN_FIX,
                "none",
                0.2);
    }

    /**
     * Returns the log lines that matched, in order, cleaned and capped.
     *
     * <p>An empty list is the signal that the rule did not fire, which is why
     * this returns lines rather than a boolean: the same pass decides whether the
     * rule applies and produces the evidence for it, so the two can never
     * disagree.
     */
    private static List<String> collectEvidence(Pattern pattern, String logs) {
        List<String> matches = new ArrayList<>();
        for (String line : logs.split("\r?\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || !pattern.matcher(trimmed).find()) {
                continue;
            }
            matches.add(trimmed.length() > MAX_EVIDENCE_CHARS
                    ? trimmed.substring(0, MAX_EVIDENCE_CHARS) + "..."
                    : trimmed);
            if (matches.size() >= MAX_EVIDENCE_LINES) {
                break;
            }
        }
        return List.copyOf(matches);
    }

    /** Compiles a rule's signature once, at class load, rather than per call. */
    private static Rule rule(String id, String regex, String rootCause, String suggestedFix,
                             String recommendedAction, double confidence) {
        return new Rule(id, Pattern.compile(regex, Pattern.CASE_INSENSITIVE), rootCause, suggestedFix,
                recommendedAction, confidence);
    }
}
