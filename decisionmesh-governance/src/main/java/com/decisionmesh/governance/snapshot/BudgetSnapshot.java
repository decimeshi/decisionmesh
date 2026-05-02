package com.decisionmesh.governance.snapshot;

import com.decisionmesh.billing.model.SubscriptionEntity;

public class BudgetSnapshot {

    private final double remainingBudget;
    private final double spentSoFar;
    private final String currency;
    private final SubscriptionEntity.Plan plan;
    private final int maxRequests;

    public BudgetSnapshot(double remainingBudget,
                          double spentSoFar,
                          String currency,
                          SubscriptionEntity.Plan plan) {
        this.remainingBudget = remainingBudget;
        this.spentSoFar      = spentSoFar;
        this.currency        = currency;
        this.plan            = plan;

        this.maxRequests = switch (plan) {
            case FREE        ->       100;
            case HOBBY       ->       500;
            case BUILDER     ->     2_000;
            case PRO         ->    10_000;
            case ENTERPRISE  -> Integer.MAX_VALUE;
        };
    }

    public int getMaxRequests() { return maxRequests; }
    public SubscriptionEntity.Plan getPlan() { return plan; }
}