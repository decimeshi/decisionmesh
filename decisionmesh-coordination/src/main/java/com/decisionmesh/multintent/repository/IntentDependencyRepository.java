package com.decisionmesh.multintent.repository;

import com.decisionmesh.multintent.dependency.IntentDependency;
import io.smallrye.mutiny.Uni;

import java.util.List;
import java.util.UUID;

public interface IntentDependencyRepository {

    Uni<Void> save(IntentDependency dependency);

    Uni<List<IntentDependency>> findByParent(UUID parentIntentId);

}