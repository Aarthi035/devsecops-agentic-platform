package com.devsecops.agentservice.incident;

import com.devsecops.agentservice.diagnosis.EvidenceBundle;
import java.util.List;

/**
 * What one investigation produced: the evidence to reason from, the readings
 * that produced it, and an honest account of what was not collected.
 *
 * <p>{@code budgetExhausted} and {@code notices} exist because a bounded
 * investigation sometimes stops early, and a diagnosis that quietly omits the
 * logs it never fetched looks the same as one that read them and found nothing
 * there. The spec's limits are only a safety property if a reader can tell that
 * they were reached.
 */
public record Investigation(
        EvidenceBundle bundle,
        List<Reading> readings,
        int toolCalls,
        int toolBudget,
        boolean budgetExhausted,
        List<String> notices) {

    public Investigation {
        readings = readings == null ? List.of() : List.copyOf(readings);
        notices = notices == null ? List.of() : List.copyOf(notices);
    }
}
