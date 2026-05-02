package com.decisionmesh.llm.selector;

import com.decisionmesh.domain.intent.Intent;
import com.decisionmesh.domain.intent.value.IntentConstraints;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Adaptive LLM model selector — epsilon-greedy bandit with hard circuit breaker.
 *
 * Selection pipeline:
 *   1. Filter    — region, compliance, circuit breaker (FIX 3.5)
 *   2. Rank      — composite score descending
 *   3. Explore   — epsilon-greedy: with probability ε pick non-primary adapter
 *   4. Return    — ordered [primary, fallback1, fallback2, ...]
 *
 * FIX 3.5 — Hard circuit breaker for degraded adapters.
 *   is_degraded adapters are HARD-FILTERED when execution_count > circuitBreakerThreshold.
 *   Below the threshold, we don't have enough data to trust the degradation signal —
 *   a single bad call shouldn't permanently exile a new adapter.
 *   If ALL adapters are degraded, the filter is released as a last resort (prefer
 *   degraded over "no response").
 *
 * FIX 3.6 — Empty registry is logged as ERROR (not warn) and emits a metric.
 *   The engine handles the empty case — the selector just makes the signal louder.
 */
@ApplicationScoped
public class LlmModelSelector {

    @ConfigProperty(name = "llm.selector.epsilon",                    defaultValue = "0.10")
    double epsilon;

    @ConfigProperty(name = "llm.selector.warmup-calls",               defaultValue = "20")
    int warmupCalls;

    // FIX 3.5: minimum execution count before we trust the degradation signal
    @ConfigProperty(name = "llm.selector.circuit-breaker-threshold",  defaultValue = "5")
    int circuitBreakerThreshold;

    // FIX 3.6: when true, throw on empty registry instead of silent fallback
    @ConfigProperty(name = "llm.selector.strict-mode",                defaultValue = "false")
    boolean strictMode;

    @Inject
    MeterRegistry meterRegistry;

    private final Random random = new Random();

    /**
     * Select and rank adapters for this intent.
     *
     * @param intent      Provides constraints for filtering
     * @param candidates  All active adapters with live stats from AdapterRegistry
     * @return            Ranked list: index 0 = primary, rest = fallbacks in order
     * @throws IllegalStateException if no eligible adapter found in strict mode
     */
    public List<SelectedAdapter> select(Intent intent, List<AdapterStats> candidates) {
        if (candidates.isEmpty()) {
            handleEmptyRegistry(intent);
            // In non-strict mode, caller handles empty result — return empty list
            return List.of();
        }

        IntentConstraints constraints = intent.getConstraints();

        // ── Step 1: Filter ────────────────────────────────────────────────────
        List<AdapterStats> eligible = candidates.stream()
                .filter(a -> passesRegionFilter(a, constraints))
                .filter(a -> passesCircuitBreaker(a))        // FIX 3.5: hard-break degraded
                .collect(Collectors.toList());

        if (eligible.isEmpty()) {
            // FIX 3.5: all adapters are degraded — release circuit breaker as last resort
            Log.warnf("All adapters degraded or circuit-broken for intent=%s. " +
                    "Using degraded adapters as last resort.", intent.getId());
            meterRegistry.counter("llm.selector.all_degraded",
                    "tenant", intent.getTenantId().toString()).increment();
            eligible = candidates; // allow degraded adapters rather than returning nothing
        }

        // ── Step 2: Rank ──────────────────────────────────────────────────────
        List<AdapterStats> ranked = eligible.stream()
                .sorted(Comparator.comparingDouble(AdapterStats::compositeScore).reversed())
                .collect(Collectors.toList());

        // ── Step 3: Epsilon-greedy exploration ────────────────────────────────
        double  effectiveEpsilon = computeEffectiveEpsilon(ranked);
        boolean explore          = ranked.size() > 1 && random.nextDouble() < effectiveEpsilon;

        List<AdapterStats> ordered = explore
                ? reorder(ranked, 1 + random.nextInt(ranked.size() - 1))
                : ranked;

        if (explore) {
            Log.infof("Exploration: chose rank-%d adapter as primary. intent=%s, ε=%.2f",
                    ranked.indexOf(ordered.get(0)) + 1, intent.getId(), effectiveEpsilon);
            meterRegistry.counter("llm.selector.exploration",
                    "tenant", intent.getTenantId().toString()).increment();
        }

        // ── Step 4: Build result ──────────────────────────────────────────────
        return IntStream.range(0, ordered.size())
                .mapToObj(i -> SelectedAdapter.of(
                        ordered.get(i), i + 1, explore && i == 0,
                        buildReason(ordered.get(i), i, explore)))
                .collect(Collectors.toList());
    }

    // ── Filters ───────────────────────────────────────────────────────────────

    private boolean passesRegionFilter(AdapterStats a, IntentConstraints constraints) {
        if (constraints == null) return true;
        String required = constraints.region();
        if (required == null || required.isBlank()) return true;
        return required.equalsIgnoreCase(a.getRegion())
                || a.getRegion() == null || a.getRegion().isBlank();
    }

    /**
     * FIX 3.5 — Hard circuit breaker.
     *
     * Degraded adapters are EXCLUDED when:
     *   is_degraded = true  AND  execution_count > circuitBreakerThreshold
     *
     * The execution_count guard prevents premature exclusion of new adapters that
     * happened to fail their first few calls (EMA is noisy at low sample counts).
     * After circuitBreakerThreshold executions the signal is trusted and the
     * adapter is hard-filtered until its success rate recovers above 75% (set
     * in AdapterPerformanceProfileRepository.RECOVERY_THRESHOLD).
     */
    private boolean passesCircuitBreaker(AdapterStats a) {
        if (!a.isDegraded()) return true;
        boolean hasEnoughData = a.getExecutionCount() > circuitBreakerThreshold;
        if (hasEnoughData) {
            Log.debugf("Circuit breaker: excluding degraded adapter=%s (execCount=%d, successRate=%.2f)",
                    a.getAdapterId(), a.getExecutionCount(), a.getEmaSuccessRate());
        }
        return !hasEnoughData; // allow degraded adapters below threshold (not enough data)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * FIX 3.6 — Empty registry is an infrastructure signal, not a normal event.
     * Log as ERROR and emit a metric. In strict mode, throw to fail fast.
     */
    private void handleEmptyRegistry(Intent intent) {
        Log.errorf(
                "AdapterRegistry returned zero candidates for intent=%s, tenant=%s, type=%s. " +
                "Check: (1) adapters table has rows, (2) is_active=true, (3) adapter_type='LLM', " +
                "(4) allowed_intent_types allows this type, (5) RLS tenant context is set.",
                intent.getId(), intent.getTenantId(), intent.getIntentType());

        meterRegistry.counter("llm.registry.empty",
                "tenant", intent.getTenantId().toString(),
                "intent_type", intent.getIntentType()).increment();

        if (strictMode) {
            throw new IllegalStateException(
                    "No LLM adapters available for tenant=" + intent.getTenantId()
                            + ", type=" + intent.getIntentType()
                            + ". Check adapter registry and RLS configuration.");
        }
    }

    private double computeEffectiveEpsilon(List<AdapterStats> ranked) {
        long totalCalls = ranked.stream().mapToLong(AdapterStats::getExecutionCount).sum();
        // Double epsilon during warmup to encourage exploration of new adapters
        return totalCalls < warmupCalls ? Math.min(epsilon * 2.0, 0.30) : epsilon;
    }

    private List<AdapterStats> reorder(List<AdapterStats> ranked, int explorationIdx) {
        AdapterStats pick    = ranked.get(explorationIdx);
        AdapterStats primary = ranked.get(0);
        return IntStream.range(0, ranked.size()).mapToObj(i -> {
            if (i == 0)              return pick;
            if (i == 1)              return primary;
            if (i == explorationIdx) return ranked.get(1);
            return ranked.get(i);
        }).collect(Collectors.toList());
    }

    private String buildReason(AdapterStats s, int rank, boolean wasExploration) {
        if (s.isColdStart())
            return String.format("Cold start — neutral prior (score=%.3f)", s.compositeScore());
        if (s.isDegraded())
            return String.format("Degraded last-resort (successRate=%.2f, score=%.3f)",
                    s.getEmaSuccessRate(), s.compositeScore());
        if (wasExploration && rank == 0)
            return String.format("Exploration (ε=%.2f, score=%.3f)", epsilon, s.compositeScore());
        return String.format("Rank %d — score=%.3f (success=%.2f, latency=%.0fms, cost=$%.4f)",
                rank + 1, s.compositeScore(),
                s.getEmaSuccessRate(), s.getEmaLatencyMs(), s.getEmaCostPerCall());
    }
}
