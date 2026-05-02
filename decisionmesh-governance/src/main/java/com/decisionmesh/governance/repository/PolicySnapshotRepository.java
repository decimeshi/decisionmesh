package com.decisionmesh.governance.repository;


import com.decisionmesh.governance.entity.PolicySnapshotEntity;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.UUID;

@ApplicationScoped
public class PolicySnapshotRepository implements PanacheRepositoryBase<PolicySnapshotEntity, UUID> {

    public Uni<PolicySnapshotEntity> findByIntentAndVersion(UUID intentId, long version) {
        return find("intentId = ?1 and version = ?2", intentId, version).firstResult();
    }
}