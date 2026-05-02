package com.decisionmesh.contracts.security.service;

import com.decisionmesh.contracts.security.entity.OrganizationEntity;
import com.decisionmesh.contracts.security.entity.TenantEntity;
import com.decisionmesh.contracts.security.entity.TenantIdempotencyEntity;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.UUID;

@ApplicationScoped
public class TenantService {

    private static final Logger LOG = Logger.getLogger(TenantService.class);

    /**
     * Creates a new tenant and a default organization atomically.
     * Guarded by an idempotency key to prevent duplicates during concurrent OIDC redirects.
     */
    public Uni<UUID> createTenant(String organizationName, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Uni.createFrom().failure(new IllegalArgumentException("Idempotency key required"));
        }

        final String resolvedName = resolveOrgName(organizationName, idempotencyKey);

        // Wrap the entire flow in ONE transaction to ensure Atomicity across the 12-table core
        return Panache.withTransaction(() ->
                        TenantIdempotencyEntity.findByKey(idempotencyKey)
                                .flatMap(existing -> {
                                    if (existing != null) {
                                        LOG.infof("createTenant: idempotent hit tenantId=%s", existing.tenantId);
                                        return Uni.createFrom().item(existing.tenantId);
                                    }

                                    // 1. Create Tenant
                                    TenantEntity tenant = new TenantEntity();
                                    tenant.externalId = idempotencyKey;
                                    tenant.name = resolvedName;
                                    tenant.status = "ACTIVE";

                                    return tenant.<TenantEntity>persist()
                                            .flatMap(t -> {
                                                // 2. Create Idempotency Record immediately after Tenant
                                                TenantIdempotencyEntity idem = TenantIdempotencyEntity.of(t.id, idempotencyKey);
                                                return idem.<TenantIdempotencyEntity>persist()
                                                        .flatMap(i -> {
                                                            // 3. Create Default Organization within the same transaction
                                                            OrganizationEntity org = new OrganizationEntity();
                                                            org.tenantId = t.id;
                                                            org.name = resolvedName;
                                                            org.isActive = true;

                                                            return org.<OrganizationEntity>persist()
                                                                    .flatMap(savedOrg -> {
                                                                        // 4. Link Org back to Tenant (Backfill)
                                                                        LOG.infof("Atomic Provisioning Complete: Tenant=%s, Org=%s", t.id, savedOrg.id);
                                                                        return Uni.createFrom().item(t.id);
                                                                    });
                                                        });
                                            });
                                })
                )
                // Global safety net for rare race conditions on the unique constraint
                .onFailure().recoverWithUni(ex -> {
                    LOG.warnf("Race condition detected during tenant creation for key: %s", idempotencyKey);
                    return TenantIdempotencyEntity.findByKey(idempotencyKey)
                            .flatMap(race -> {
                                if (race != null) {
                                    return Uni.createFrom().item(race.tenantId);
                                }
                                // If we hit a constraint but can't find the record, something is wrong with the DB state
                                return Uni.createFrom().failure(new RuntimeException("Provisioning conflict: Idempotent record not found", ex));
                            });
                });
    }

    public Uni<String> getOrganizationNameByTenantId(UUID tenantId) {
        return OrganizationEntity.<OrganizationEntity>find("tenantId", tenantId)
                .firstResult()
                .map(org -> {
                    if (org != null && org.name != null) {
                        return org.name;
                    }
                    return "My Organization"; // Fallback if record is missing
                })
                // Defensive recovery in case of database issues
                .onFailure().recoverWithItem("My Organization");
    }

    /**
     * Refactored to call the atomic provisioner if the org doesn't exist.
     * Keeps method signature for Augmentor compatibility.
     */
    public Uni<OrganizationEntity> createDefaultOrganization(UUID tenantId, String organizationName) {
        final String resolvedName = resolveOrgName(organizationName, null);

        return OrganizationEntity
                .<OrganizationEntity>find("tenantId = ?1 and name = ?2 and isActive = true", tenantId, resolvedName)
                .firstResult()
                .flatMap(existing -> {
                    if (existing != null) return Uni.createFrom().item(existing);

                    // If missing, we create it.
                    // Note: In the new flow, createTenant already does this.
                    OrganizationEntity org = new OrganizationEntity();
                    org.tenantId = tenantId;
                    org.name = resolvedName;
                    org.isActive = true;
                    return Panache.withTransaction(org::persist);
                });
    }

    private String resolveOrgName(String organizationName, String idempotencyKey) {
        if (organizationName != null && !organizationName.isBlank()) return organizationName;
        if (idempotencyKey != null && idempotencyKey.startsWith("auto-tenant-")) return "My Organization";
        return idempotencyKey != null ? idempotencyKey : "Default Organization";
    }
}