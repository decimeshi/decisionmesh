package com.decisionmesh.portfolio.aggregate;

import java.util.*;

public class IntentPortfolioAggregate {

    private final String tenantId;
    private final double totalBudget;
    private final double riskThreshold;
    private final Map<UUID, Double> intentPriority = new HashMap<>();

    public IntentPortfolioAggregate(String tenantId,
                                    double totalBudget,
                                    double riskThreshold) {
        this.tenantId = tenantId;
        this.totalBudget = totalBudget;
        this.riskThreshold = riskThreshold;
    }

    public void registerIntent(UUID intentId, double priorityWeight) {
        intentPriority.put(intentId, priorityWeight);
    }

    public Map<UUID, Double> getIntentPriority() {
        return Collections.unmodifiableMap(intentPriority);
    }

    public double getTotalBudget() { return totalBudget; }
    public double getRiskThreshold() { return riskThreshold; }
    public String getTenantId() { return tenantId; }
}