package com.decisionmesh.bootstrap.service;


import com.decisionmesh.billing.service.CreditLedgerService;
import com.decisionmesh.persistence.entity.OrgBrandingEntity;
import com.decisionmesh.persistence.entity.OrgEntity;
import com.decisionmesh.contracts.security.entity.ProjectEntity;
import com.decisionmesh.persistence.repository.OrgRepository;
import com.decisionmesh.persistence.repository.ProjectRepository;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.UUID;

/**
 * Provisions the default org, project, and credit gift for a brand-new tenant.
 *
 * Called from TenantProvisioningResource on first authenticated request,
 * or from a Keycloak event listener via webhook.
 *
 * Idempotent — safe to call multiple times; skips if already provisioned.
 */
@ApplicationScoped
public class TenantProvisioningService {

    @Inject OrgRepository        orgRepository;
    @Inject ProjectRepository    projectRepository;
    @Inject CreditLedgerService  creditLedgerService;

    @WithTransaction
    public Uni<Void> provisionIfAbsent(UUID tenantId, String orgName, String email) {
        return orgRepository.findByTenantId(tenantId)
                .flatMap(existing -> {
                    if (existing != null) {
                        Log.debugf("[Provisioning] Tenant already provisioned: %s", tenantId);
                        return Uni.createFrom().voidItem();
                    }
                    Log.infof("[Provisioning] New tenant detected — provisioning: %s", tenantId);
                    return createOrg(tenantId, orgName)
                            .flatMap(v -> createDefaultProject(tenantId))
                            .flatMap(v -> createDefaultBranding(tenantId, orgName))
                            .flatMap(v -> creditLedgerService.grantRegistrationGift(tenantId))
                            .invoke(() -> Log.infof(
                                    "[Provisioning] Done: tenantId=%s orgName=%s +500 credits",
                                    tenantId, orgName));
                });
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private Uni<Void> createOrg(UUID tenantId, String name) {
        OrgEntity org = new OrgEntity();
        org.id        = tenantId;
        org.name      = name != null && !name.isBlank() ? name : "My Organisation";
        org.plan      = "FREE";
        org.createdAt = Instant.now();
        return orgRepository.persist(org).replaceWithVoid();
    }

    private Uni<Void> createDefaultProject(UUID tenantId) {
        ProjectEntity project = new ProjectEntity();
        project.id          = UUID.randomUUID();
        project.tenantId    = tenantId;
        project.name        = "Default Project";
        project.description = "Default project";
        project.environment = "Production";
        project.isDefault   = true;
        project.createdAt   = Instant.now();
        return projectRepository.persist(project).replaceWithVoid();
    }

    private Uni<Void> createDefaultBranding(UUID tenantId, String orgName) {
        OrgBrandingEntity branding = new OrgBrandingEntity();
        branding.tenantId     = tenantId;
        branding.orgName      = orgName != null ? orgName : "My Organisation";
        branding.primaryColor = "#2563eb";
        branding.logoUrl      = null;
        branding.updatedAt    = Instant.now();
        return branding.persist().replaceWithVoid();
    }
}
