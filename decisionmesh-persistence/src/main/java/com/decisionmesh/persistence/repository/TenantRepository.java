package com.decisionmesh.persistence.repository;

import com.decisionmesh.contracts.security.entity.TenantEntity;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.UUID;

/**
 * Reactive Panache repository for TenantEntity.
 *
 * Extends PanacheRepositoryBase<TenantEntity, UUID> which provides
 * all standard reactive CRUD methods (findById, persist, delete, etc.)
 * returning Uni<T> — no blocking calls.
 *
 * Fixed: was extending blocking PanacheRepository from quarkus-hibernate-orm-panache.
 * Now extends reactive PanacheRepositoryBase from quarkus-hibernate-reactive-panache.
 */
@ApplicationScoped
public class TenantRepository implements PanacheRepositoryBase<TenantEntity, UUID> {

    public Uni<TenantEntity> findByExternalId(String externalId) {
        return find("externalId", externalId).firstResult();
    }

    public Uni<TenantEntity> findByName(String name) {
        return find("name", name).firstResult();
    }
}