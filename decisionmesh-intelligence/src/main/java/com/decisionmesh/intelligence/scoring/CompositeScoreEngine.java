package com.decisionmesh.intelligence.scoring;

public class CompositeScoreEngine {

    private final ScoreWeights weights;

    public CompositeScoreEngine(ScoreWeights weights) {
        this.weights = weights;
    }

    public AdapterScoreSnapshot compute(String adapterId,
                                        double normalizedLatency,
                                        double normalizedCost,
                                        double successRate,
                                        double riskScore,
                                        double driftScore,
                                        double slaPenalty) {

        double score = weights.weightedScore(
                normalizedLatency,
                normalizedCost,
                successRate,
                riskScore,
                driftScore,
                slaPenalty
        );

        return new AdapterScoreSnapshot(adapterId, score, weights.getVersion());
    }
}