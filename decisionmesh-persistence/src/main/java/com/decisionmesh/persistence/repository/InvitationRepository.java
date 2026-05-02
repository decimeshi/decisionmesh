package com.decisionmesh.persistence.repository;

import com.decisionmesh.persistence.entity.InvitationEntity;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class InvitationRepository implements PanacheRepositoryBase<InvitationEntity, UUID> {

    public Uni<List<InvitationEntity>> findByTenant(UUID tenantId) {
        return find("tenantId", tenantId).list();
    }

    public Uni<InvitationEntity> findByToken(String token) {
        return find("token", token).firstResult();
    }
}
