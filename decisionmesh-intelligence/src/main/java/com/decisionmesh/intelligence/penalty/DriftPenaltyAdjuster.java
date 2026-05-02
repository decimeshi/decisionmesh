package com.decisionmesh.intelligence.penalty;

public class DriftPenaltyAdjuster {

    public double adjustPenalty(double currentPenalty, double driftScore) {
        return currentPenalty + (driftScore * 0.5);
    }
}