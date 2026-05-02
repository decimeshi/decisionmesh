package com.decisionmesh.domain.value;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Budget value object for an Intent.
 * Tracks ceiling and cumulative spend using double primitives throughout.
 *
 * Referenced by:
 *   LlmExecutionEngine         — getCeilingUsd(), getSpentUsd(), remaining(), isExceeded()
 *   ExecutionRecordRepository  — getCeilingUsd()
 *   BudgetGuard                — validateBudget()
 *   PromptBuilder              — remaining() for SLA/budget tightness signal
 */
public final class Budget {

    private final double amount;    // budget ceiling in USD  (0.0 = unconstrained)
    private final double spent;     // cumulative spend so far in USD
    private final String currency;

    // ── Constructor ──────────────────────────────────────────────────────────

    @JsonCreator
    public Budget(@JsonProperty("amount")   double amount,
                  @JsonProperty("spent")    double spent,
                  @JsonProperty("currency") String currency) {
        this.amount   = amount;
        this.spent    = spent;
        this.currency = currency != null ? currency : "USD";
    }

    // ── Factories ────────────────────────────────────────────────────────────

    public static Budget of(double amount) {
        return new Budget(amount, 0.0, "USD");
    }

    public static Budget of(double amount, String currency) {
        return new Budget(amount, 0.0, currency);
    }

    public static Budget unconstrained() {
        return new Budget(0.0, 0.0, "USD");
    }

    // ── Core accessors ────────────────────────────────────────────────────────

    /** Budget ceiling in USD. 0.0 means unconstrained. */
    public double amount()        { return amount; }

    /** Cumulative spend so far in USD. */
    public double spent()         { return spent; }

    /** Budget ceiling — alias used by LlmExecutionEngine and ExecutionRecordRepository. */
    public double getCeilingUsd() { return amount; }

    /** Cumulative spend — alias used by LlmExecutionEngine budget check. */
    public double getSpentUsd()   { return spent; }

    /** Currency code (default "USD"). */
    public String getCurrency()   { return currency; }

    // ── Semantic helpers ─────────────────────────────────────────────────────

    /**
     * Remaining budget: ceiling - spent.
     * Returns Double.MAX_VALUE when unconstrained (ceiling == 0).
     */
    public double remaining() {
        if (amount == 0.0) return Double.MAX_VALUE;
        return amount - spent;
    }

    /**
     * True when spend has reached or exceeded the ceiling.
     * Always false when unconstrained.
     */
    public boolean isExceeded() {
        if (amount == 0.0) return false;
        return spent >= amount;
    }

    /**
     * True when a budget ceiling is configured (amount > 0).
     */
    public boolean isConstrained() {
        return amount > 0.0;
    }

    /**
     * Returns a new Budget with additional spend recorded.
     * Used by Intent.consumeBudget().
     */
    public Budget consume(double additionalSpend) {
        return new Budget(amount, spent + additionalSpend, currency);
    }

    @Override
    public String toString() {
        return String.format("Budget{amount=%.6f, spent=%.6f, remaining=%.6f, currency=%s}",
                amount, spent, remaining(), currency);
    }
}