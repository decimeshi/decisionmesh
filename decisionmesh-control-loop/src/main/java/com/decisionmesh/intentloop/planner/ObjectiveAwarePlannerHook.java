package com.decisionmesh.intentloop.planner;

import com.decisionmesh.domain.intent.value.IntentObjective;

public class ObjectiveAwarePlannerHook {

    public double adjustWeight(double baseWeight,
                               IntentObjective objective) {

        switch (objective.objectiveType()) {
            case COST:
                return baseWeight * 1.2;
            case LATENCY:
                return baseWeight * 1.1;
            case RISK:
                return baseWeight * 1.3;
            case QUALITY:
                return baseWeight * 1.4;
            default:
                return baseWeight;
        }
    }
}