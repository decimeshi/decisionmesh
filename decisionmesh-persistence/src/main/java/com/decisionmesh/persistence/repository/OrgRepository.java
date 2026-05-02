
package com.decisionmesh.persistence.repository;

import com.decisionmesh.common.dto.BrandingRequest;
import com.decisionmesh.persistence.entity.OrgBrandingEntity;
import com.decisionmesh.persistence.entity.OrgEntity;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.UUID;

@ApplicationScoped
public class OrgRepository implements PanacheRepositoryBase<OrgEntity, UUID> {

    // ── Org ───────────────────────────────────────────────────────────────────

    public Uni<OrgEntity> findByTenantId(UUID tenantId) {
        return findById(tenantId);   // id IS the tenantId
    }

    // ── Branding ──────────────────────────────────────────────────────────────

    public Uni<OrgBrandingEntity> findBrandingByTenantId(UUID tenantId) {
        return OrgBrandingEntity.findById(tenantId);
    }

    public Uni<Void> upsertBranding(UUID tenantId, BrandingRequest req) {
        return OrgBrandingEntity.<OrgBrandingEntity>findById(tenantId)
                .flatMap(existing -> {
                    OrgBrandingEntity b = existing != null ? existing : new OrgBrandingEntity();
                    b.tenantId     = tenantId;
                    b.orgName      = req.orgName();
                    b.primaryColor = req.primaryColor();
                    b.logoUrl      = req.logoUrl();
                    b.favicon      = req.favicon();
                    b.updatedAt    = Instant.now();
                    return b.persist();
                })
                .replaceWithVoid();
    }

    public Uni<Void> updateLogoUrl(UUID tenantId, String logoUrl) {
        return OrgBrandingEntity.<OrgBrandingEntity>findById(tenantId)
                .flatMap(b -> {
                    if (b == null) {
                        b           = new OrgBrandingEntity();
                        b.tenantId  = tenantId;
                        b.updatedAt = Instant.now();
                    }
                    b.logoUrl   = logoUrl;
                    b.updatedAt = Instant.now();
                    return b.persist();
                })
                .replaceWithVoid();
    }
}