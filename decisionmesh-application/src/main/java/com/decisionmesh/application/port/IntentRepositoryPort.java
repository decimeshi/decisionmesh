package com.decisionmesh.application.port;

import com.decisionmesh.domain.intent.Intent;
import io.smallrye.mutiny.Uni;

import java.util.UUID;

public interface IntentRepositoryPort {
    Uni<Intent> findById(UUID tenantId, UUID intentId);
    Uni<Void> save(Intent intent);
    Uni<Void> flush();
}