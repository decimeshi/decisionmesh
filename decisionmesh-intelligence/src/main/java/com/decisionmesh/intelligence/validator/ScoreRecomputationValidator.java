package com.decisionmesh.intelligence.validator;

import com.decisionmesh.intelligence.scoring.CompositeScoreEngine;
import com.decisionmesh.intelligence.scoring.AdapterScoreSnapshot;

public class ScoreRecomputationValidator {

    public void validate(AdapterScoreSnapshot original,
                         CompositeScoreEngine engine,
                         String adapterId,
                         double latency,
                         double cost,
                         double success,
                         double risk,
                         double drift,
                         double slaPenalty) {

        AdapterScoreSnapshot recomputed = engine.compute(
                adapterId, latency, cost, success, risk, drift, slaPenalty);

        if (Math.abs(original.compositeScore() - recomputed.compositeScore()) > 0.0001) {
            throw new IllegalStateException("Composite score mismatch during replay");
        }
    }
}