package com.decisionmesh.persistence.repository;

import com.decisionmesh.persistence.entity.AdapterEntity;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.UUID;

/**
 * Repository for LLM adapter CRUD operations.
 *
 * CHANGES:
 *   - Added defaultProvider + defaultModel from OpenBao via VaultConfigSource
 *   - findActiveByTenant() falls back to synthetic default Anthropic adapter
 *     when tenant has no adapters configured — new tenants can submit intents
 *     immediately after onboarding without manually registering an adapter.
 *
 * FIX: AdapterEntity uses public fields (PanacheEntityBase pattern) —
 *      no setters exist. buildDefaultAdapter() uses direct field assignment.
 */
@ApplicationScoped
public class AdapterRepository {

    // Loaded from OpenBao secret/decisionmesh/llm via VaultConfigSource:
    //   OpenBao key 'provider' → Quarkus property 'provider' → llm.default.provider
    @ConfigProperty(name = "llm.default.provider", defaultValue = "anthropic")
    String defaultProvider;

    //   OpenBao key 'model' → Quarkus property 'model' → llm.default.model
    @ConfigProperty(name = "llm.default.model", defaultValue = "claude-haiku-4-5-20251001")
    String defaultModel;

    // ── Standard CRUD ─────────────────────────────────────────────────────────

    public Uni<List<AdapterEntity>> findByTenant(UUID tenantId) {
        return AdapterEntity.findByTenant(tenantId);
    }

    /**
     * Returns active adapters for the tenant.
     *
     * Falls back to a synthetic default Anthropic adapter when the tenant
     * has no adapters configured. The synthetic adapter is NOT persisted —
     * it exists only in memory for this execution.
     */
    public Uni<List<AdapterEntity>> findActiveByTenant(UUID tenantId) {
        return AdapterEntity.findActiveByTenant(tenantId)
                .map(adapters -> {
                    if (!adapters.isEmpty()) {
                        return adapters;
                    }
                    Log.infof("[AdapterRepository] No active adapters for tenant=%s " +
                                    "— using default: provider=%s model=%s",
                            tenantId, defaultProvider, defaultModel);
                    return List.of(buildDefaultAdapter(tenantId));
                });
    }

    public Uni<AdapterEntity> findById(UUID tenantId, UUID adapterId) {
        return AdapterEntity.findByTenantAndId(tenantId, adapterId);
    }

    public Uni<AdapterEntity> persist(AdapterEntity entity) {
        return entity.persist();
    }

    public Uni<Void> delete(AdapterEntity entity) {
        return entity.delete();
    }

    // ── Default adapter builder ───────────────────────────────────────────────

    /**
     * Builds a synthetic (non-persisted) default adapter.
     *
     * Uses direct public field assignment — AdapterEntity extends
     * PanacheEntityBase which uses public fields, not getters/setters.
     *
     * Fields not in AdapterEntity (isDefault, creditTier) are omitted —
     * AdapterEntity has no such columns.
     */
    private AdapterEntity buildDefaultAdapter(UUID tenantId) {
        AdapterEntity entity = new AdapterEntity();

        // Deterministic UUID — same tenant always gets same default adapter ID
        entity.id              = UUID.nameUUIDFromBytes(("default-" + tenantId).getBytes());
        entity.tenantId        = tenantId;
        entity.name            = "Default " + capitalize(defaultProvider) + " Adapter";
        entity.provider        = defaultProvider.toUpperCase();  // e.g. "ANTHROPIC"
        entity.modelId         = defaultModel;                   // e.g. "claude-haiku-4-5-20251001"
        entity.adapterType     = "LLM";
        entity.isActive        = true;
        entity.region          = null;                           // no region restriction
        entity.config          = "{}";
        entity.capabilityFlags = "{}";
        entity.allowedIntentTypes = "[]";

        return entity;
    }

    private String capitalize(String s) {
        if (s == null || s.isBlank()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase();
    }
}