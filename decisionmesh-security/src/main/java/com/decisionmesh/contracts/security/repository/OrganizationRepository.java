package com.decisionmesh.contracts.security.repository;

import com.decisionmesh.contracts.security.entity.OrganizationEntity;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.UUID;

/**
 * Reactive Panache repository for OrganizationEntity.
 * * Provides non-blocking database access for organization management
 * and tenant-based lookups.
 */
@ApplicationScoped
public class OrganizationRepository
        implements PanacheRepositoryBase<OrganizationEntity, UUID> {

    /**
     * Finds an organization by its business-level tenantId.
     * * @param tenantId The unique UUID representing the tenant.
     * @return A Uni containing the organization if found.
     */
    public Uni<OrganizationEntity> findByTenantId(UUID tenantId) {
        return find("tenantId", tenantId).firstResult();
    }

    /**
     * Retrieves all active organizations.
     */
    public Uni<List<OrganizationEntity>> findAllActive() {
        return find("isActive = true").list();
    }

    /**
     * Finds an organization by name (case-insensitive).
     */
    public Uni<OrganizationEntity> findByName(String name) {
        return find("LOWER(name) = LOWER(?1)", name).firstResult();
    }

    /**
     * Checks if a tenantId is already assigned.
     */
    public Uni<Boolean> existsByTenantId(UUID tenantId) {
        return findByTenantId(tenantId)
                .onItem().transform(org -> org != null);
    }
}