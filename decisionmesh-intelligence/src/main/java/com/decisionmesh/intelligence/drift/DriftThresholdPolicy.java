package com.decisionmesh.intelligence.drift;

public record DriftThresholdPolicy(double threshold) {

    public boolean requiresReplan(double driftScore) {
        return driftScore >= threshold;
    }
}