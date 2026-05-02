package com.decisionmesh.application.idempotency;

import io.smallrye.mutiny.Uni;

import java.util.UUID;

public interface IdempotencyService {
    Uni<Boolean> checkAndRegister(UUID tenantId, String idempotencyKey);
}