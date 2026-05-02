package com.decisionmesh.application.port;

import java.util.UUID;

/**
 * Application-layer view of adapter performance statistics.
 *
 * Used by IntentCentricPlanner to score and rank adapters.
 * Populated by AdapterLearningPortImpl from adapter_performance_profiles table.
 *
 * Original fields (avgCost, avgLatency, failureRate) are preserved.
 * Additional fields added to support:
 *   - AdapterRegistry filtering (adapterId, provider, model, region)
 *   - LlmModelSelector circuit breaker (degraded, executionCount)
 *   - Cold-start detection (coldStart, executionCount)
 *   - Composite scoring (compositeScore, riskScore)
 */
public record AdapterStats(
        // ── Identity ─────────────────────────────────────────────────────────
        UUID   adapterId,
        String provider,       // "OPENAI", "ANTHROPIC", "GEMINI", "DEEPSEEK", "AZURE_OPENAI"
        String model,          // "gpt-4o", "claude-3-5-sonnet-20241022", etc.
        String region,         // for region-based filtering in AdapterRegistry

        // ── Performance (EMA values from adapter_performance_profiles) ────────
        double avgCost,        // ema_cost
        long   avgLatency,     // ema_latency_ms
        double failureRate,    // 1.0 - ema_success_rate
        double riskScore,      // ema_risk_score
        double compositeScore, // pre-computed composite_score from DB

        // ── State ─────────────────────────────────────────────────────────────
        long    executionCount, // total executions — 0 = cold start
        boolean coldStart,      // cold_start flag from adapter_performance_profiles
        boolean degraded        // is_degraded flag from adapter_performance_profiles
) {
    // ── Convenience accessors (standard Java boolean is-prefix) ──────────────

    /**
     * True when the adapter is marked degraded in adapter_performance_profiles.
     * isDegraded() follows Java boolean naming convention.
     * The record accessor degraded() also works — both return the same value.
     */
    public boolean isDegraded() {
        return degraded;
    }

    /**
     * True when the adapter has no execution history yet.
     * Planner treats cold-start adapters as exploration candidates.
     */
    public boolean isColdStart() {
        return coldStart || executionCount == 0;
    }

    /**
     * Success rate derived from failure rate.
     */
    public double successRate() {
        return 1.0 - failureRate;
    }

    // ── Factory for cold-start / unknown adapters ─────────────────────────────

    /**
     * Creates an AdapterStats with neutral cold-start priors.
     * Used when no profile exists yet for an adapter.
     */
    public static AdapterStats coldStart(UUID adapterId, String provider,
                                         String model, String region) {
        return new AdapterStats(
                adapterId, provider, model, region,
                0.01,   // avgCost — low prior
                2000L,  // avgLatency — 2s prior
                0.2,    // failureRate — 20% failure prior (neutral, not optimistic)
                0.1,    // riskScore
                0.5,    // compositeScore — neutral
                0L,     // executionCount
                true,   // coldStart
                false   // degraded
        );
    }
}