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
     * The rule table. Patterns are case-insensitive and searched, not anchored,
     * so a signature is found wherever the tool chain chose to print it.
     */
    private static final List<Rule> RULES = List.of(
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

    @Override
    public String provider() {
        return "heuristic";
    }

    @Override
    public Diagnosis diagnose(DiagnosisRequest request) {
        String logs = request.logs() == null ? "" : request.logs();

        for (Rule rule : RULES) {
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

        // No signature matched. Saying so, and quoting the end of the log, is more
        // useful than the nearest guess: a wrong cause with a high confidence is
        // worse than no cause at all.
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
