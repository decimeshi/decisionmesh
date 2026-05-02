package com.decisionmesh.persistence.repository;

import com.decisionmesh.persistence.entity.IntentEntity;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.UUID;

/**
 * Reactive Panache repository for IntentEntity.
 *
 * Methods:
 *   findByIdAndTenant()      — single record lookup (existing)
 *   findPageByTenant()       — paginated list for GET /api/intents
 *   countByTenant()          — total elements for pagination metadata
 *   countByTenantAndPhase()  — filtered count
 */
@ApplicationScoped
public class IntentRepository implements PanacheRepositoryBase<IntentEntity, UUID> {

    // ── Single record ─────────────────────────────────────────────────────────

    public Uni<IntentEntity> findByIdAndTenant(UUID id, UUID tenantId) {
        return find("id = ?1 and tenantId = ?2", id, tenantId).firstResult();
    }

    // ── Paginated list — used by GET /api/intents ─────────────────────────────

    /**
     * Returns a page of intents for a tenant, with optional phase filter.
     *
     * @param tenantId  required — all queries are tenant-scoped
     * @param phase     optional filter (null or blank = all phases)
     * @param sortField column to sort by: createdAt | intentType | phase | version
     * @param sortDir   "asc" or "desc"
     * @param pageIndex zero-based page index
     * @param pageSize  items per page
     */
    public Uni<List<IntentEntity>> findPageByTenant(
            UUID   tenantId,
            String phase,
            String sortField,
            String sortDir,
            int    pageIndex,
            int    pageSize) {

        String safe = safeSort(sortField);
        Sort   sort = "asc".equalsIgnoreCase(sortDir)
                ? Sort.ascending(safe)
                : Sort.descending(safe);

        if (phase != null && !phase.isBlank()) {
            return find("tenantId = ?1 and phase = ?2", sort, tenantId, phase)
                    .page(Page.of(pageIndex, pageSize))
                    .list();
        }

        return find("tenantId = ?1", sort, tenantId)
                .page(Page.of(pageIndex, pageSize))
                .list();
    }

    public Uni<Long> countByTenant(UUID tenantId) {
        return count("tenantId = ?1", tenantId);
    }

    public Uni<Long> countByTenantAndPhase(UUID tenantId, String phase) {
        return count("tenantId = ?1 and phase = ?2", tenantId, phase);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Whitelist sort fields — prevents JPQL injection. */
    private String safeSort(String field) {
        if (field == null) return "createdAt";
        return switch (field) {
            case "createdAt", "intentType", "phase", "version", "satisfactionState" -> field;
            default -> "createdAt";
        };
    }
}