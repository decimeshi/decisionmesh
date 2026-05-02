package com.decisionmesh.intelligence.regret;

public class RegretCalculator {

    public double compute(double optimalReward, double actualReward) {
        return optimalReward - actualReward;
    }
}