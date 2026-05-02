package com.decisionmesh.intentloop.drift;

public class DriftEvolutionTracker {

    private double previousDrift = 0.0;

    public boolean isDriftIncreasing(double currentDrift) {
        boolean increasing = currentDrift > previousDrift;
        previousDrift = currentDrift;
        return increasing;
    }
}