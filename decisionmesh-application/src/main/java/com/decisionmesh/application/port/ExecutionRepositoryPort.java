package com.decisionmesh.application.port;

import com.decisionmesh.domain.execution.ExecutionRecord;
import io.smallrye.mutiny.Uni;

import java.util.List;
import java.util.UUID;

/**
 * Port: persists and retrieves execution records.
 * Implemented in the infrastructure layer.
 */
public interface ExecutionRepositoryPort {

    Uni<Void> append(ExecutionRecord record);
    Uni<List<ExecutionRecord>> findByIntentId(UUID intentId);
}