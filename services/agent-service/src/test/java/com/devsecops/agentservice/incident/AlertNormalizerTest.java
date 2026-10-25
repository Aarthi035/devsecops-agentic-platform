package com.devsecops.agentservice.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devsecops.agentservice.TestProperties;
import com.devsecops.agentservice.error.AgentRequestException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The boundary where an unvalidated Alertmanager payload stops.
 *
 * <p>Every downstream decision - what to read, what to propose an action
 * against - is made from the {@code NormalizedAlert}s this class produces, so
 * what is pinned here is which label becomes the target and whether the agent
 * had to interpret it. A label called {@code service} is a convention, not a
 * fact, and {@code inferred} is how that stays visible instead of silently
 * becoming a restart of the wrong workload.
 */
class AlertNormalizerTest {

    private final AlertNormalizer normalizer = new AlertNormalizer(TestProperties.defaults());

    private static AlertmanagerWebhook.Alert alert(Map<String, String> labels) {
        return new AlertmanagerWebhook.Alert(null, labels, Map.of(), "2026-10-25T09:00:00Z", null, null, null);
    }

    private static AlertmanagerWebhook webhook(String status, List<AlertmanagerWebhook.Alert> alerts) {
        return new AlertmanagerWebhook("4", "group-1", status, "agent-service", Map.of(), Map.of(), Map.of(),
                "http://alertmanager.invalid", alerts);
    }

    @Test
    void believesAnExplicitlyNamedWorkloadKind() {
        NormalizedAlert normalized = normalizer.normalize(webhook("firing", List.of(
                alert(Map.of("alertname", "UserServiceDown", "namespace", "devsecops",
                        "deployment", "user-service", "severity", "critical"))))).get(0);

        assertThat(normalized.workloadKind()).isEqualTo("Deployment");
        assertThat(normalized.workloadName()).isEqualTo("user-service");
        assertThat(normalized.inferred()).isFalse();
        assertThat(normalized.subject()).isEqualTo("deployment devsecops/user-service");
        assertThat(normalized.severity()).isEqualTo("critical");
    }

    @Test
    void readsAConventionalServiceLabelAsADeploymentAndSaysSo() {
        NormalizedAlert normalized = normalizer.normalize(webhook("firing", List.of(
                alert(Map.of("alertname", "HighErrorRate", "namespace", "devsecops",
                        "service", "payment-service"))))).get(0);

        assertThat(normalized.workloadKind()).isEqualTo("Deployment");
        assertThat(normalized.workloadName()).isEqualTo("payment-service");
        assertThat(normalized.inferred()).isTrue();
    }

    @Test
    void skipsTheMonitoringStacksOwnScrapeJobs() {
        NormalizedAlert normalized = normalizer.normalize(webhook("firing", List.of(
                alert(Map.of("alertname", "PrometheusDown", "job", "prometheus"))))).get(0);

        assertThat(normalized.workloadKind()).isEqualTo("unknown");
        assertThat(normalized.workloadName()).isEqualTo("PrometheusDown");
    }

    @Test
    void leavesTheTargetUnknownWhenTheLabelsDescribeNothing() {
        NormalizedAlert normalized = normalizer.normalize(webhook("firing", List.of(
                alert(Map.of("alertname", "SomethingOdd"))))).get(0);

        assertThat(normalized.workloadKind()).isEqualTo("unknown");
        assertThat(normalized.namespace()).isEqualTo("devsecops");
        assertThat(normalized.targetKey()).isEqualTo("unknown/devsecops/SomethingOdd");
    }

    @Test
    void fallsBackToAPodOnlyWhenNothingBetterIsNamed() {
        NormalizedAlert normalized = normalizer.normalize(webhook("firing", List.of(
                alert(Map.of("alertname", "PodRestarts", "pod", "user-service-7d9f-abc"))))).get(0);

        assertThat(normalized.workloadKind()).isEqualTo("Pod");
        assertThat(normalized.workloadName()).isEqualTo("user-service-7d9f-abc");
    }

    @Test
    void narrowsTheLogSelectorToThePodWhenTheAlertNamesOne() {
        List<AlertmanagerWebhook.Alert> alerts = List.of(
                alert(Map.of("alertname", "PodRestarts", "namespace", "devsecops", "pod", "user-service-7d9f")),
                alert(Map.of("alertname", "HighErrorRate", "namespace", "devsecops", "service", "payment")));

        List<NormalizedAlert> normalized = normalizer.normalize(webhook("firing", alerts));

        assertThat(normalized.get(0).logSelector())
                .isEqualTo("{namespace=\"devsecops\", pod=\"user-service-7d9f\"}");
        assertThat(normalized.get(1).logSelector()).isEqualTo("{namespace=\"devsecops\"}");
    }

    @Test
    void derivesAStableFingerprintWhenTheSenderOmitsOne() {
        AlertmanagerWebhook.Alert sent = alert(Map.of("alertname", "PodRestarts", "namespace", "devsecops",
                "deployment", "user-service"));

        String first = normalizer.normalize(webhook("firing", List.of(sent))).get(0).fingerprint();
        String again = normalizer.normalize(webhook("firing", List.of(sent))).get(0).fingerprint();

        assertThat(first).matches("[0-9a-f]{16}").isEqualTo(again);
    }

    @Test
    void keepsTheFingerprintAlertmanagerSent() {
        AlertmanagerWebhook.Alert sent = new AlertmanagerWebhook.Alert(null,
                Map.of("alertname", "PodRestarts"), Map.of(), null, null, null, "abcdef0123456789");

        assertThat(normalizer.normalize(webhook("firing", List.of(sent))).get(0).fingerprint())
                .isEqualTo("abcdef0123456789");
    }

    @Test
    void takesTheGroupStatusWhenAnIndividualAlertDoesNotStateOne() {
        NormalizedAlert normalized = normalizer.normalize(webhook("resolved", List.of(
                alert(Map.of("alertname", "PodRestarts", "service", "user-service"))))).get(0);

        assertThat(normalized.status()).isEqualTo("resolved");
    }

    @Test
    void refusesAPayloadThatCannotDescribeAnIncident() {
        assertThatThrownBy(() -> normalizer.normalize(null))
                .isInstanceOf(AgentRequestException.class)
                .hasMessage("The alert payload is empty.");
        assertThatThrownBy(() -> normalizer.normalize(webhook("pending", List.of(
                alert(Map.of("alertname", "X"))))))
                .isInstanceOf(AgentRequestException.class)
                .hasMessage("The alert status must be 'firing' or 'resolved', not 'pending'.");
        assertThatThrownBy(() -> normalizer.normalize(webhook("firing", List.of())))
                .isInstanceOf(AgentRequestException.class)
                .hasMessage("The alert payload contains no alerts.");
        assertThatThrownBy(() -> normalizer.normalize(new AlertmanagerWebhook("4", "g", "firing", "r",
                Map.of(), Map.of(), Map.of(), null, java.util.Arrays.asList(null, null))))
                .isInstanceOf(AgentRequestException.class)
                .hasMessage("The alert payload contains no usable alert.");
    }

    @Test
    void truncatesAnOverLongGroupToTheConfiguredMaximumAndReportsWhatItDropped() {
        List<AlertmanagerWebhook.Alert> many = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            Map<String, String> labels = new LinkedHashMap<>();
            labels.put("alertname", "PodRestarts");
            labels.put("pod", "user-service-" + i);
            many.add(alert(labels));
        }

        AlertmanagerWebhook webhook = webhook("firing", many);

        assertThat(normalizer.normalize(webhook)).hasSize(20);
        assertThat(normalizer.droppedCount(webhook)).isEqualTo(5);
    }
}
