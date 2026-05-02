package com.decisionmesh.intentloop.engine;

import com.decisionmesh.domain.intent.Intent;
import com.decisionmesh.domain.intent.value.IntentObjective;
import com.decisionmesh.intentloop.satisfaction.*;
import com.decisionmesh.intentloop.stability.StabilityWindow;
import com.decisionmesh.intentloop.drift.DriftEvolutionTracker;

public class IntentControlLoopEngine {

    private final SatisfactionEvaluator evaluator = new SatisfactionEvaluator();
    private final DriftEvolutionTracker driftTracker = new DriftEvolutionTracker();

    public boolean evaluate(Intent intent,
                            IntentObjective objective,
                            double observedMetric,
                            double driftScore,
                            StabilityWindow window) {

        SatisfactionScore score = evaluator.evaluate(objective, observedMetric);

        window.add(score.getValue());

        boolean stable = window.isStable(0.8);
        boolean driftIncreasing = driftTracker.isDriftIncreasing(driftScore);

        return stable && !driftIncreasing;
    }
}