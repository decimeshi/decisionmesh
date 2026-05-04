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
 *
 * FIX (tenant_id IS NULL): Global adapters (tenant_id = NULL) are shared
 *      across all tenants. Both findByTenant() and findActiveByTenant() now
 *      include them via "tenantId = ?1 OR tenantId IS NULL".
 *      Tenant-owned (BYOM) adapters sort first; global ones follow.
 *      Write operations (update/toggle/delete via findById) remain
 *      tenant-scoped — tenants cannot mutate global adapters.
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

    /**
     * Lists adapters visible to this tenant:
     *   - Tenant-owned adapters (tenantId = ?)      — BYOM
     *   - Global shared adapters (tenantId IS NULL)  — e.g. Claude Haiku
     *
     * Tenant-owned adapters sort first so they appear above global ones in the UI.
     * Write operations (update/toggle/delete) use findById() which is still
     * tenant-scoped, preventing tenants from mutating global adapters.
     */
    public Uni<List<AdapterEntity>> findByTenant(UUID tenantId) {
        return AdapterEntity.findByTenantOrGlobal(tenantId);
    }

    /**
     * Returns active adapters visible to this tenant (owned + global).
     *
     * Falls back to a synthetic default Anthropic adapter ONLY when there
     * are truly no active adapters at all — neither tenant-owned nor global.
     * With a global adapter seeded in the DB this fallback should never fire,
     * but is kept as a safety net for fresh environments.
     *
     * IMPORTANT: the synthetic fallback has a deterministic UUID derived from
     * tenantId — it will NOT match any real adapter row. Once a global adapter
     * is present in the DB this branch is unreachable and can be removed.
     */
    public Uni<List<AdapterEntity>> findActiveByTenant(UUID tenantId) {
        return AdapterEntity.findActiveByTenantOrGlobal(tenantId)
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

    /**
     * Looks up a specific adapter by tenant + id.
     * Intentionally tenant-scoped only — tenants must not mutate global adapters.
     * Used by update(), toggle(), and delete() in AdapterService.
     */
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
     * NOTE: This adapter's UUID is derived from tenantId and will NOT match
     * the real adapter row in the DB. Once a global (tenant_id IS NULL) adapter
     * is seeded this method is never reached.
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