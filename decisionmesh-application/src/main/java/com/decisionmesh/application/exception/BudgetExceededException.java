package com.decisionmesh.application.exception;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Thrown when intent budget is exhausted before or during execution.
 * Maps to ExecutionRecord status BUDGET_EXCEEDED.
 */
public class BudgetExceededException extends RuntimeException {

    private final UUID       intentId;
    private final BigDecimal budgetCeiling;
    private final BigDecimal currentSpend;

    public BudgetExceededException(UUID intentId,
                                    BigDecimal budgetCeiling,
                                    BigDecimal currentSpend) {
        super(String.format(
                "Budget exceeded: intentId=%s, ceiling=$%.6f, currentSpend=$%.6f",
                intentId, budgetCeiling, currentSpend));
        this.intentId      = intentId;
        this.budgetCeiling = budgetCeiling;
        this.currentSpend  = currentSpend;
    }

    public UUID       getIntentId()      { return intentId; }
    public BigDecimal getBudgetCeiling() { return budgetCeiling; }
    public BigDecimal getCurrentSpend()  { return currentSpend; }
}
