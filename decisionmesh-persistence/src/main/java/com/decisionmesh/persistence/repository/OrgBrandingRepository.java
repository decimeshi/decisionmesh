package com.decisionmesh.persistence.repository;


import com.decisionmesh.persistence.entity.OrgBrandingEntity;
import io.quarkus.hibernate.reactive.panache.PanacheRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.UUID;

@ApplicationScoped
public class OrgBrandingRepository implements PanacheRepository<OrgBrandingEntity> {

    public Uni<OrgBrandingEntity> findByTenantId(UUID tenantId) {
        return find("tenantId", tenantId).firstResult();
    }

    public Uni<Void> upsert(OrgBrandingEntity branding) {
        return findByTenantId(branding.tenantId)
                .onItem().ifNotNull().transformToUni(existing -> {
                    existing.orgName = branding.orgName;
                    existing.primaryColor = branding.primaryColor;
                    existing.logoUrl = branding.logoUrl;
                    existing.favicon = branding.favicon;
                    existing.updatedAt = branding.updatedAt;
                    return persist(existing).replaceWithVoid();
                })
                .onItem().ifNull().switchTo(() -> persist(branding).replaceWithVoid());
    }
}