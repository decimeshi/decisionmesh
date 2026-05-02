package com.decisionmesh.explainability.engine;

import com.decisionmesh.explainability.model.DecisionTrace;
import com.decisionmesh.explainability.graph.DecisionTraceGraph;

import java.util.UUID;

public class ExplainabilityEngine {

    private final DecisionTraceGraph graph = new DecisionTraceGraph();

    public void recordDecision(DecisionTrace trace) {
        // Persist via repository layer (integration hook)
    }

    public void linkDecisions(UUID parentDecisionId,
                              UUID childDecisionId) {
        graph.link(parentDecisionId, childDecisionId);
    }

    public DecisionTraceGraph getGraph() {
        return graph;
    }
}