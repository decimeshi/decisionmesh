package com.decisionmesh.portfolio.risk;

public class TenantRiskEnvelopeManager {

    public boolean withinRiskThreshold(double totalRisk,
                                       double threshold) {
        return totalRisk <= threshold;
    }
}