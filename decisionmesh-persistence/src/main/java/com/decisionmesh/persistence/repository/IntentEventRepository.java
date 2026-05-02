package com.decisionmesh.persistence.repository;

import com.decisionmesh.persistence.entity.IntentEventEntity;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.UUID;

/**
 * Reactive Panache repository for IntentEventEntity.
 *
 * Fixed: PanacheRepository (blocking) → PanacheRepositoryBase<IntentEventEntity, UUID> (reactive)
 */
@ApplicationScoped
public class IntentEventRepository implements PanacheRepositoryBase<IntentEventEntity, UUID> {

    public Uni<List<IntentEventEntity>> findByIntentId(UUID intentId) {
        return find("intentId = ?1 order by occurredAt asc", intentId).list();
    }

    public Uni<List<IntentEventEntity>> findByTenantAndIntent(UUID tenantId, UUID intentId) {
        return find("tenantId = ?1 and intentId = ?2 order by occurredAt asc",
                tenantId, intentId).list();
    }
}