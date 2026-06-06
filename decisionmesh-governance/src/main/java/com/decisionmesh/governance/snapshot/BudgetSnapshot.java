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

        // maxRequests matches SubscriptionEntity.Plan credits exactly
        // FREE: 100 one-time, BUILDER: 15k/mo, PRO: 60k/mo
        this.maxRequests = switch (plan) {
            case FREE        ->         100;
            case BUILDER     ->      15_000;
            case PRO         ->      60_000;
            case ENTERPRISE  -> Integer.MAX_VALUE;
        };
    }

    public int getMaxRequests() { return maxRequests; }
    public SubscriptionEntity.Plan getPlan() { return plan; }
}