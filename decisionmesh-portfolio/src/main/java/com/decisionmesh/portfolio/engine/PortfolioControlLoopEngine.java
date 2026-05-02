package com.decisionmesh.portfolio.engine;

import com.decisionmesh.portfolio.aggregate.IntentPortfolioAggregate;
import com.decisionmesh.portfolio.allocator.PortfolioBudgetAllocator;
import com.decisionmesh.portfolio.utility.UtilityScoringEngine;
import com.decisionmesh.portfolio.risk.TenantRiskEnvelopeManager;

import java.util.Map;
import java.util.UUID;

public class PortfolioControlLoopEngine {

    private final PortfolioBudgetAllocator allocator = new PortfolioBudgetAllocator();
    private final UtilityScoringEngine utilityEngine = new UtilityScoringEngine();
    private final TenantRiskEnvelopeManager riskManager = new TenantRiskEnvelopeManager();

    public Map<UUID, Double> rebalance(IntentPortfolioAggregate portfolio) {
        return allocator.allocate(
                portfolio.getTotalBudget(),
                portfolio.getIntentPriority()
        );
    }

    public double evaluateUtility(double satisfaction,
                                  double cost,
                                  double risk,
                                  double weight) {

        return utilityEngine.computeUtility(satisfaction, cost, risk, weight);
    }

    public boolean validateRisk(double totalRisk,
                                double threshold) {

        return riskManager.withinRiskThreshold(totalRisk, threshold);
    }
}