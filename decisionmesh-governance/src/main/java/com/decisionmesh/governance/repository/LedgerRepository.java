package com.decisionmesh.governance.repository;


import com.decisionmesh.governance.entity.LedgerEntryEntity;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class LedgerRepository implements PanacheRepositoryBase<LedgerEntryEntity, UUID> {

    public Uni<List<LedgerEntryEntity>> findByIntentId(UUID intentId) {
        return find("intentId", intentId).list();
    }
}
