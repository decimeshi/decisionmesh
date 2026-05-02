package com.decisionmesh.application.port;

import com.decisionmesh.domain.execution.ExecutionRecord;
import io.smallrye.mutiny.Uni;

import java.util.UUID;

public interface LearningEngine {
    Uni<Void> update(ExecutionRecord record);

    Uni<Void> updateProfiles(UUID id);
}