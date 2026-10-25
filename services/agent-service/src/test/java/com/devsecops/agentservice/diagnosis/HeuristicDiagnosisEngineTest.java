package com.devsecops.agentservice.diagnosis;

import com.devsecops.agentservice.logs.LogSanitizer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HeuristicDiagnosisEngineTest {

    private final HeuristicDiagnosisEngine engine = new HeuristicDiagnosisEngine(new LogSanitizer());

    @Test
    void identifiesCompilationFailuresAndPreservesMatchingEvidence() {
        Diagnosis diagnosis = engine.diagnose(new DiagnosisRequest(
                "example/platform",
                "CI",
                "build",
                "failure",
                "[ERROR] Compilation failure\n[ERROR] App.java:[42,13] cannot find symbol"));

        assertThat(diagnosis.rootCause()).isEqualTo("The code did not compile.");
        assertThat(diagnosis.evidence()).containsExactly(
                "[ERROR] Compilation failure",
                "[ERROR] App.java:[42,13] cannot find symbol");
        assertThat(diagnosis.recommendedAction()).isEqualTo("none");
        assertThat(diagnosis.confidence()).isGreaterThan(0.8);
    }

    @Test
    void choosesDiskFailureBeforeGenericTestFailure() {
        Diagnosis diagnosis = engine.diagnose(new DiagnosisRequest(
                "example/platform",
                "CI",
                "build",
                "failure",
                "No space left on device\nThere are test failures"));

        assertThat(diagnosis.rootCause()).isEqualTo("The build ran out of disk space on the runner.");
        assertThat(diagnosis.evidence()).containsExactly("No space left on device");
    }

    @Test
    void returnsTailEvidenceWhenNoKnownSignatureMatches() {
        Diagnosis diagnosis = engine.diagnose(new DiagnosisRequest(
                "example/platform",
                "CI",
                "build",
                "failure",
                "first line\n\nlast useful line"));

        assertThat(diagnosis.rootCause()).contains("none of the signatures");
        assertThat(diagnosis.evidence()).containsExactly("first line", "last useful line");
        assertThat(diagnosis.confidence()).isEqualTo(0.2);
    }
}