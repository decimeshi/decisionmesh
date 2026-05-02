package com.decisionmesh.explainability.repository;

import com.decisionmesh.explainability.model.DecisionTrace;
import io.smallrye.mutiny.Uni;

import java.util.List;
import java.util.UUID;

public interface DecisionTraceRepository {

    Uni<Void> save(DecisionTrace trace);

    Uni<List<DecisionTrace>> findByIntent(UUID intentId);

}