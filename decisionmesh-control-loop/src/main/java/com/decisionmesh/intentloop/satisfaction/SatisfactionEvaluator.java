package com.decisionmesh.intentloop.satisfaction;

import com.decisionmesh.domain.intent.value.IntentObjective;

public class SatisfactionEvaluator {

    public SatisfactionScore evaluate(IntentObjective objective,
                                      double observedMetric) {

        double delta = Math.abs(objective.targetThreshold() - observedMetric);

        double normalized = 1.0 - (delta / (objective.targetThreshold() + objective.tolerance()));
        if (normalized < 0) normalized = 0;

        return SatisfactionScore.of(normalized);
    }
}