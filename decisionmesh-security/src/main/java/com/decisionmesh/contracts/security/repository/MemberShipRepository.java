package com.decisionmesh.contracts.security.repository;


import com.decisionmesh.contracts.security.entity.MemberShipEntity;
import io.quarkus.hibernate.reactive.panache.PanacheRepository;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.UUID;

@ApplicationScoped
public class MemberShipRepository implements PanacheRepository<MemberShipEntity> {

    public Uni<MemberShipEntity> findByTenantUserProject(UUID tenantId, UUID userId, UUID projectId) {
        return find("tenantId = ?1 and userId = ?2 and projectId = ?3",
                tenantId, userId, projectId)
                .firstResult();
    }

    public Multi<MemberShipEntity> findByTenant(UUID tenantId) {
        return find("tenantId", tenantId).firstResult().toMulti();
    }

    public Multi<MemberShipEntity> findByUser(UUID userId) {
        return find("userId", userId).firstResult().toMulti();
    }

    public Uni<Boolean> exists(UUID tenantId, UUID userId, UUID projectId) {
        return count("tenantId = ?1 and userId = ?2 and projectId = ?3",
                tenantId, userId, projectId)
                .map(count -> count > 0);
    }

    public Uni<MemberShipEntity> findMember(UUID userId, UUID projectId) {
        return find("tenantId = ?1 and userId = ?2 and projectId = ?3",
                userId, projectId)
                .firstResult();
    }

    public Uni<MemberShipEntity> findByUserAndProject(UUID userId, UUID projectId) {
        return find("userId = ?1 and projectId = ?2", userId, projectId)
                .firstResult();
    }

    public Uni<MemberShipEntity> findMember(UUID tenantId, UUID userId, UUID projectId) {
        return find("tenantId = ?1 and userId = ?2 and projectId = ?3",
                tenantId, userId, projectId)
                .firstResult();
    }

}


