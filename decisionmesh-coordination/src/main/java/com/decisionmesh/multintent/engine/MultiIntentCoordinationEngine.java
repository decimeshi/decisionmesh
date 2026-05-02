package com.decisionmesh.multintent.engine;

import com.decisionmesh.multintent.graph.IntentGraph;
import java.util.UUID;

public class MultiIntentCoordinationEngine {

    private final IntentGraph graph;

    public MultiIntentCoordinationEngine(IntentGraph graph) {
        this.graph = graph;
    }

    public void onIntentSatisfied(UUID intentId) {
        graph.getDependents(intentId)
                .forEach(dependent -> triggerDependentIntent(dependent));
    }

    private void triggerDependentIntent(UUID dependentIntentId) {
        // Integration hook with ControlPlaneOrchestrator
    }
}