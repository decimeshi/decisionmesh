package com.decisionmesh.intelligence.replanner;

import com.decisionmesh.intelligence.drift.DriftThresholdPolicy;
import com.decisionmesh.domain.intent.Intent;
import io.smallrye.mutiny.Uni;

public class AdaptiveReplanner {

    private final DriftThresholdPolicy driftPolicy;

    public AdaptiveReplanner(DriftThresholdPolicy driftPolicy) {
        this.driftPolicy = driftPolicy;
    }

    public Uni<Boolean> evaluate(Intent intent) {
        double drift = intent.getDriftScore().value();
        if (driftPolicy.requiresReplan(drift)) {
            return Uni.createFrom().item(true);
        }
        return Uni.createFrom().item(false);
    }
}