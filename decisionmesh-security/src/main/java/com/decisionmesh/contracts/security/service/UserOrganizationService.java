package com.decisionmesh.contracts.security.service;

import com.decisionmesh.contracts.security.entity.UserOrganizationEntity;
import com.decisionmesh.contracts.security.repository.UserOrganizationRepository;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class UserOrganizationService {

    private static final Logger LOG = Logger.getLogger(UserOrganizationService.class);

    @Inject
    public UserOrganizationRepository repository;

    /**
     * Creates a membership with no permissions — delegates to the full overload.
     */
    public Uni<UserOrganizationEntity> createMembership(
            UUID userId, UUID orgId, UUID tenantId, String role) {
        return createMembership(userId, orgId, tenantId, role, List.of());
    }

    /**
     * Creates a membership, guarding against duplicates.
     * If the membership already exists, returns the existing record without error.
     */
    public Uni<UserOrganizationEntity> createMembership(
            UUID userId, UUID orgId, UUID tenantId, String role,
            List<String> permissions) {

        return Panache.withTransaction(() ->
                repository.countByUserAndOrg(userId, orgId)
                        .flatMap(count -> {
                            if (count > 0) {
                                LOG.warnf("Membership already exists: userId=%s orgId=%s",
                                        userId, orgId);
                                return repository.findByUserAndOrg(userId, orgId);
                            }

                            UserOrganizationEntity membership = new UserOrganizationEntity();
                            membership.userId         = userId;
                            membership.organizationId = orgId;
                            membership.tenantId       = tenantId;
                            membership.role           = role;
                            membership.permissions    = permissions;
                            membership.isActive       = true;

                            return repository.persist(membership)
                                    .invoke(m -> LOG.infof(
                                            "Created membership: userId=%s orgId=%s tenantId=%s role=%s",
                                            userId, orgId, tenantId, role));
                        })
        );
    }

    /**
     * Returns the tenantId for the first active membership of a user.
     * Returns null inside the Uni if no active membership is found.
     */
    public Uni<UUID> findTenantIdByUserId(UUID userId) {
        return Panache.withSession(() ->
                repository.findFirstActiveByUser(userId)
                        .map(m -> m != null ? m.tenantId : null)
        );
    }

    public Uni<List<UserOrganizationEntity>> findAllByUserId(UUID userId) {
        return Panache.withSession(() ->
                repository.findAllActiveByUser(userId)
        );
    }

    public Uni<UserOrganizationEntity> findByUserAndTenant(UUID userId, UUID tenantId) {
        return Panache.withSession(() ->
                repository.findActiveByUserAndTenant(userId, tenantId)
        );
    }

    public Uni<Void> deactivateMembership(UUID userId, UUID orgId) {
        return Panache.withTransaction(() ->
                repository.findByUserAndOrg(userId, orgId)
                        .flatMap(membership -> {
                            if (membership == null) {
                                LOG.warnf("Membership not found: userId=%s orgId=%s",
                                        userId, orgId);
                                return Uni.createFrom().voidItem();
                            }
                            membership.isActive = false;
                            return repository.persist(membership).replaceWithVoid();
                        })
        );
    }

}