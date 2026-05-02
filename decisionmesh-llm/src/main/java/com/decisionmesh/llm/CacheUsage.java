package com.decisionmesh.llm;


import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Captures prompt cache token usage returned by providers that support caching.
 *
 * Anthropic response usage block example:
 * {
 *   "input_tokens": 12,
 *   "cache_creation_input_tokens": 487,   // first call — writing to cache
 *   "cache_read_input_tokens": 0           // subsequent calls — reading from cache
 * }
 *
 * Cost calculation for Anthropic claude-haiku-4-5-20251001:
 *   Standard input:       $1.00 per 1M tokens
 *   Cache write:          $1.25 per 1M tokens  (25% more than standard)
 *   Cache read:           $0.10 per 1M tokens  (90% cheaper than standard)
 */
public record CacheUsage(
        int inputTokens,              // regular (non-cached) input tokens
        int cacheCreationTokens,      // tokens written to cache (first call)
        int cacheReadTokens,          // tokens read from cache (subsequent calls)
        int outputTokens
) {

    public static final CacheUsage EMPTY = new CacheUsage(0, 0, 0, 0);

    /** True when this execution actually read from the cache — real savings occurred. */
    public boolean isCacheHit() {
        return cacheReadTokens > 0;
    }

    /** True when cache was just written for the first time. */
    public boolean isCacheWrite() {
        return cacheCreationTokens > 0;
    }

    /** Total input tokens charged (excluding cache reads which are cheaper). */
    public int totalInputTokens() {
        return inputTokens + cacheCreationTokens + cacheReadTokens;
    }

    /**
     * Cost savings vs. charging all tokens at standard rate.
     * Cache reads cost 10% of standard — so savings = 90% of read token cost.
     *
     * @param standardInputCostPerMillion e.g. 1.00 for Haiku 4.5
     */
    public BigDecimal savingsUsd(double standardInputCostPerMillion) {
        if (cacheReadTokens == 0) return BigDecimal.ZERO;
        // Standard cost for those tokens
        double standardCost = (cacheReadTokens / 1_000_000.0) * standardInputCostPerMillion;
        // Cache read cost (10% of standard)
        double cacheCost    = standardCost * 0.10;
        double savings      = standardCost - cacheCost;
        return BigDecimal.valueOf(savings).setScale(8, RoundingMode.HALF_UP);
    }

    /**
     * Actual cost of this execution using cache-aware pricing.
     * Anthropic Haiku 4.5:
     *   input:         $1.00 / 1M
     *   cache write:   $1.25 / 1M
     *   cache read:    $0.10 / 1M
     *   output:        $5.00 / 1M
     */
    public BigDecimal anthropicHaikuCostUsd() {
        double inputCost  = (inputTokens          / 1_000_000.0) * 1.00;
        double writeCost  = (cacheCreationTokens  / 1_000_000.0) * 1.25;
        double readCost   = (cacheReadTokens       / 1_000_000.0) * 0.10;
        double outputCost = (outputTokens          / 1_000_000.0) * 5.00;
        return BigDecimal.valueOf(inputCost + writeCost + readCost + outputCost)
                .setScale(8, RoundingMode.HALF_UP);
    }

    @Override
    public String toString() {
        return String.format("CacheUsage{input=%d, cacheWrite=%d, cacheRead=%d, output=%d, hit=%s}",
                inputTokens, cacheCreationTokens, cacheReadTokens, outputTokens, isCacheHit());
    }
}
