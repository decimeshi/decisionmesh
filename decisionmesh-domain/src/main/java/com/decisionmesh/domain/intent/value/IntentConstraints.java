package com.decisionmesh.domain.intent.value;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Execution constraints attached to an Intent.
 *
 * Referenced by:
 *   PromptBuilder         — timeoutSeconds() for SLA urgency signal
 *   IntentCentricPlanner  — allowedAdapters(), region(), maxTokens(), timeoutSeconds()
 *   LlmModelSelector      — region() for adapter filtering
 *   IntentCentricSLAGuard — maxExecutionWindow() for SLA enforcement
 *   DriftEvaluator        — maxDriftThreshold() for drift violation detection
 *   Intent.fromRequest()  — deserialized from REST request body via @JsonCreator
 */
public record IntentConstraints(
        int          maxRetries,          // max execution retry attempts
        int          timeoutSeconds,      // SLA deadline in seconds (0 = no limit)
        int          maxTokens,           // max LLM output tokens (0 = adapter default)
        String       region,              // required data-residency region (null = any)
        List<String> allowedAdapters,     // explicit adapter ID allowlist (empty = all)
        List<String> requiredCompliance,  // compliance frameworks e.g. ["HIPAA", "SOC2"]
        double       maxDriftThreshold,   // max acceptable drift score 0..1 (0 = no limit)
        long         maxExecutionWindow,  // max wall-clock ms for entire intent (0 = no limit)
        long         maxLatency           // max acceptable adapter latency in ms (0 = no limit)
) {

    // ── Compact constructor ───────────────────────────────────────────────────

    public IntentConstraints {
        allowedAdapters    = allowedAdapters    != null ? List.copyOf(allowedAdapters)    : List.of();
        requiredCompliance = requiredCompliance != null ? List.copyOf(requiredCompliance) : List.of();
        if (maxRetries < 0)           throw new IllegalArgumentException("maxRetries must be >= 0");
        if (timeoutSeconds < 0)       throw new IllegalArgumentException("timeoutSeconds must be >= 0");
        if (maxTokens < 0)            throw new IllegalArgumentException("maxTokens must be >= 0");
        if (maxDriftThreshold < 0.0)  throw new IllegalArgumentException("maxDriftThreshold must be >= 0");
        if (maxExecutionWindow < 0)   throw new IllegalArgumentException("maxExecutionWindow must be >= 0");
        if (maxLatency < 0)           throw new IllegalArgumentException("maxLatency must be >= 0");
    }

    // ── Jackson deserialization factory ──────────────────────────────────────

    @JsonCreator
    public static IntentConstraints fromJson(
            @JsonProperty("maxRetries")          int          maxRetries,
            @JsonProperty("timeoutSeconds")      int          timeoutSeconds,
            @JsonProperty("maxTokens")           int          maxTokens,
            @JsonProperty("region")              String       region,
            @JsonProperty("allowedAdapters")     List<String> allowedAdapters,
            @JsonProperty("requiredCompliance")  List<String> requiredCompliance,
            @JsonProperty("maxDriftThreshold")   double       maxDriftThreshold,
            @JsonProperty("maxExecutionWindow")  long         maxExecutionWindow,
            @JsonProperty("maxLatency")          long         maxLatency) {
        return new IntentConstraints(
                maxRetries, timeoutSeconds, maxTokens, region,
                allowedAdapters, requiredCompliance,
                maxDriftThreshold, maxExecutionWindow, maxLatency
        );
    }

    // ── Convenience factories ─────────────────────────────────────────────────

    /** Minimal — just retry and timeout, everything else defaulted. */
    public static IntentConstraints of(int maxRetries, int timeoutSeconds) {
        return new IntentConstraints(maxRetries, timeoutSeconds, 0,
                null, List.of(), List.of(), 0.0, 0L, 0L);
    }

    /** No constraints — useful for tests. */
    public static IntentConstraints none() {
        return new IntentConstraints(3, 30, 0, null, List.of(), List.of(), 0.0, 0L, 0L);
    }

    // ── Semantic helpers ──────────────────────────────────────────────────────

    /**
     * True when this constraint has a drift limit configured.
     * DriftEvaluator uses this to skip drift checks when no threshold is set.
     */
    public boolean hasDriftLimit() {
        return maxDriftThreshold > 0.0;
    }

    /**
     * True when a maximum execution window is configured.
     * IntentCentricSLAGuard uses this for wall-clock enforcement.
     */
    public boolean hasExecutionWindow() {
        return maxExecutionWindow > 0L;
    }

    /**
     * True when a per-adapter latency cap is configured.
     * LlmModelSelector uses this to filter out adapters whose EMA latency
     * already exceeds the constraint.
     */
    public boolean hasLatencyLimit() {
        return maxLatency > 0L;
    }
}