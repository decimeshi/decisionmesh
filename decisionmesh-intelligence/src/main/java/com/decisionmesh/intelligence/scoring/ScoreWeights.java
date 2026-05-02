package com.decisionmesh.intelligence.scoring;

public class ScoreWeights {

    private final double latencyWeight;
    private final double costWeight;
    private final double successWeight;
    private final double riskPenaltyWeight;
    private final double driftPenaltyWeight;
    private final double slaPenaltyWeight;
    private final int version;

    public ScoreWeights(double latencyWeight,
                        double costWeight,
                        double successWeight,
                        double riskPenaltyWeight,
                        double driftPenaltyWeight,
                        double slaPenaltyWeight,
                        int version) {
        this.latencyWeight = latencyWeight;
        this.costWeight = costWeight;
        this.successWeight = successWeight;
        this.riskPenaltyWeight = riskPenaltyWeight;
        this.driftPenaltyWeight = driftPenaltyWeight;
        this.slaPenaltyWeight = slaPenaltyWeight;
        this.version = version;
    }

    public int getVersion() { return version; }

    public double weightedScore(double latency,
                                double cost,
                                double successRate,
                                double risk,
                                double drift,
                                double slaPenalty) {

        return (latencyWeight * latency)
             + (costWeight * cost)
             + (successWeight * successRate)
             - (riskPenaltyWeight * risk)
             - (driftPenaltyWeight * drift)
             - (slaPenaltyWeight * slaPenalty);
    }
}