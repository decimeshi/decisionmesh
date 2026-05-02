package com.decisionmesh.bootstrap.learning.profile;

public class AdapterPerformanceProfile {

    private String adapterId;
    private double emaLatency;
    private double emaCost;
    private double successRate;
    private double riskScore;
    private int executionCount;
    private boolean coldStart;

    public void update(double latency, double cost, boolean success) {
        this.executionCount++;
        this.emaLatency = (emaLatency * 0.8) + (latency * 0.2);
        this.emaCost = (emaCost * 0.8) + (cost * 0.2);
        if (success) successRate = (successRate * 0.9) + 0.1;
        if (executionCount > 10) coldStart = false;
    }
}