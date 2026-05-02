package com.decisionmesh.llm.selector;

import java.time.Instant;
import java.util.UUID;

/**
 * Live EMA performance statistics for a single adapter.
 *
 * FIX 3.4: Cold-start prior changed from 0.8 → 0.5 (neutral).
 *   0.8 was too optimistic — new adapters incorrectly dominated selection
 *   over well-proven adapters just because their assumed success rate was high.
 *   0.5 = "unknown quality" which puts cold adapters in the middle of the ranking,
 *   letting the selector decide whether to explore them via epsilon-greedy.
 *
 * EMA formula: new = α × observation + (1 − α) × old_ema   (α = 0.1)
 * Composite:   40% success + 25% latency (inv) + 20% cost (inv) + 15% risk (inv)
 */
public class AdapterStats {

    private static final double EMA_ALPHA = 0.1;

    // FIX 3.4: neutral prior (was 0.8 — too optimistic for unknown adapters)
    private static final double COLD_START_SUCCESS_PRIOR  = 0.5;
    private static final double COLD_START_LATENCY_PRIOR  = 3000.0; // assume slightly slow until proven otherwise
    private static final double COLD_START_COST_PRIOR     = 0.02;   // assume moderate cost
    private static final double COLD_START_RISK_PRIOR     = 0.15;   // assume moderate risk

    private final UUID   adapterId;
    private final String provider;
    private final String model;
    private final String region;

    private double  emaSuccessRate;
    private double  emaLatencyMs;
    private double  emaCostPerCall;
    private double  emaRiskScore;
    private long    executionCount;
    private boolean isDegraded;
    private Instant lastUpdated;

    // ── Constructor ───────────────────────────────────────────────────────────

    public AdapterStats(UUID adapterId, String provider, String model, String region) {
        this.adapterId      = adapterId;
        this.provider       = provider;
        this.model          = model;
        this.region         = region;
        this.emaSuccessRate = COLD_START_SUCCESS_PRIOR;
        this.emaLatencyMs   = COLD_START_LATENCY_PRIOR;
        this.emaCostPerCall = COLD_START_COST_PRIOR;
        this.emaRiskScore   = COLD_START_RISK_PRIOR;
        this.executionCount = 0;
        this.isDegraded     = false;
        this.lastUpdated    = Instant.now();
    }

    // ── EMA updates ──────────────────────────────────────────────────────────

    public synchronized void recordSuccess(long latencyMs, double costUsd, double riskScore) {
        emaSuccessRate = ema(emaSuccessRate, 1.0);
        emaLatencyMs   = ema(emaLatencyMs,   latencyMs);
        emaCostPerCall = ema(emaCostPerCall,  costUsd);
        emaRiskScore   = ema(emaRiskScore,    riskScore);
        executionCount++;
        isDegraded     = false;
        lastUpdated    = Instant.now();
    }

    public synchronized void recordFailure(long latencyMs, double costUsd) {
        emaSuccessRate = ema(emaSuccessRate, 0.0);
        emaLatencyMs   = ema(emaLatencyMs,   latencyMs);
        emaCostPerCall = ema(emaCostPerCall,  costUsd);
        executionCount++;
        lastUpdated    = Instant.now();
        if (emaSuccessRate < 0.60) isDegraded = true;
    }

    // ── Composite scoring ────────────────────────────────────────────────────

    /**
     * Weighted composite score (0..1, higher = better).
     *
     * Ceilings used for normalisation:
     *   latency → 10,000 ms (10s considered "terrible")
     *   cost    → $0.10 per call (above this is expensive)
     *   risk    → 1.0 (full range)
     */
    public double compositeScore() {
        double successScore = emaSuccessRate;
        double latencyScore = 1.0 - Math.min(emaLatencyMs  / 10_000.0, 1.0);
        double costScore    = 1.0 - Math.min(emaCostPerCall / 0.10,     1.0);
        double riskScore    = 1.0 - Math.min(emaRiskScore,              1.0);

        return 0.40 * successScore
             + 0.25 * latencyScore
             + 0.20 * costScore
             + 0.15 * riskScore;
    }

    public boolean isColdStart() { return executionCount == 0; }

    // ── EMA helper ────────────────────────────────────────────────────────────

    private double ema(double current, double newValue) {
        return EMA_ALPHA * newValue + (1.0 - EMA_ALPHA) * current;
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public UUID    getAdapterId()      { return adapterId; }
    public String  getProvider()       { return provider; }
    public String  getModel()          { return model; }
    public String  getRegion()         { return region; }
    public double  getEmaSuccessRate() { return emaSuccessRate; }
    public double  getEmaLatencyMs()   { return emaLatencyMs; }
    public double  getEmaCostPerCall() { return emaCostPerCall; }
    public double  getEmaRiskScore()   { return emaRiskScore; }
    public long    getExecutionCount() { return executionCount; }
    public boolean isDegraded()        { return isDegraded; }
    public Instant getLastUpdated()    { return lastUpdated; }

    // Public setters for AdapterRegistry deserialization from DB
    public void setEmaSuccessRate(double v) { this.emaSuccessRate = v; }
    public void setEmaLatencyMs(double v)   { this.emaLatencyMs   = v; }
    public void setEmaCostPerCall(double v) { this.emaCostPerCall = v; }
    public void setEmaRiskScore(double v)   { this.emaRiskScore   = v; }
    public void setExecutionCount(long v)   { this.executionCount = v; }
    public void setDegraded(boolean v)      { this.isDegraded     = v; }
}
