package com.decisionmesh.application.reconciliation;

import io.smallrye.mutiny.Uni;

import java.util.UUID;

public interface ReconciliationService {
    Uni<Void> reconcile(UUID tenantId, UUID intentId);
}