
package com.decisionmesh.contracts.security.repository;

import com.decisionmesh.contracts.security.entity.UserOrganizationEntity;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.UUID;

/**
 * Reactive Panache repository for UserOrganizationEntity.
 *
 * Using a repository instead of static entity methods avoids IDE symbol
 * resolution issues with Quarkus bytecode-enhanced static methods, and
 * keeps query logic in one place rather than scattered across service classes.
 */
@ApplicationScoped
public class UserOrganizationRepository
        implements PanacheRepositoryBase<UserOrganizationEntity, UUID> {

    public Uni<Long> countByUserAndOrg(UUID userId, UUID orgId) {
        return count("userId = ?1 and organizationId = ?2", userId, orgId);
    }

    public Uni<UserOrganizationEntity> findByUserAndOrg(UUID userId, UUID orgId) {
        return find("userId = ?1 and organizationId = ?2", userId, orgId)
                .firstResult();
    }

    public Uni<UserOrganizationEntity> findFirstActiveByUser(UUID userId) {
        return find("userId = ?1 and isActive = true", userId)
                .firstResult();
    }

    public Uni<List<UserOrganizationEntity>> findAllActiveByUser(UUID userId) {
        return find("userId = ?1 and isActive = true", userId)
                .list();
    }

    public Uni<UserOrganizationEntity> findActiveByUserAndTenant(UUID userId, UUID tenantId) {
        return find("userId = ?1 and tenantId = ?2 and isActive = true", userId, tenantId)
                .firstResult();
    }
}