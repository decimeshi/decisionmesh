package com.decisionmesh.intentloop.worker;

import com.decisionmesh.intentloop.engine.IntentControlLoopEngine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class ContinuousEvaluationWorker {

    @Inject IntentControlLoopEngine engine;

    public void runPeriodicEvaluation() {
        // Load active intents
        // Re-evaluate satisfaction
        // Trigger replan if unstable
        // Update drift metrics
    }
}