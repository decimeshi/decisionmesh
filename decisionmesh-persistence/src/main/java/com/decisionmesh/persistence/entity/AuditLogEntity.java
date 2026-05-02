package com.decisionmesh.persistence.entity;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import io.smallrye.mutiny.Uni;
import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Read-only entity mapping the {@code audit_log} table.
 *
 * {@code @Immutable} tells Hibernate never to issue UPDATE statements —
 * audit rows are append-only by design.
 *
 * Key column mapping:
 *   occurred_at  →  occurredAt  →  DTO.timestamp   (UI reads e.timestamp)
 *   user_id      →  userId      (VARCHAR — not a UUID FK)
 *   entity_type  →  entityType
 *   entity_id    →  entityId
 */
@Entity
@Immutable
@Table(name = "audit_log")
public class AuditLogEntity extends PanacheEntityBase {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    public UUID id;

    @Column(name = "tenant_id")
    public UUID tenantId;

    @Column(name = "user_id", length = 255)
    public String userId;

    @Column(name = "entity_type", length = 100)
    public String entityType;

    @Column(name = "entity_id")
    public UUID entityId;

    @Column(name = "resource_type", length = 100)
    public String resourceType;

    @Column(name = "resource_id", length = 255)
    public String resourceId;

    @Column(name = "action", length = 100)
    public String action;

    @Column(name = "outcome", length = 20)
    public String outcome;

    @Column(name = "detail", columnDefinition = "TEXT")
    public String detail;

    /**
     * DB column: {@code occurred_at}.
     * Exposed in DTO as {@code timestamp} because the UI reads {@code e.timestamp}.
     */
    @Column(name = "occurred_at")
    public OffsetDateTime occurredAt;

    // ── Finders ───────────────────────────────────────────────────────────────

    /**
     * Paginated, tenant-scoped list with optional ILIKE filters.
     * Ordered newest-first (occurred_at DESC).
     */
    public static Uni<List<AuditLogEntity>> findByTenant(
            UUID tenantId, String userId, String action, int page, int size) {

        boolean fu = userId != null && !userId.isBlank();
        boolean fa = action != null && !action.isBlank();

        if (fu && fa) {
            return find("tenantId = ?1 and lower(userId) like lower(?2) " +
                            "and lower(action) like lower(?3) order by occurredAt desc",
                    tenantId, "%" + userId + "%", "%" + action + "%")
                    .page(page, size).list();
        } else if (fu) {
            return find("tenantId = ?1 and lower(userId) like lower(?2) order by occurredAt desc",
                    tenantId, "%" + userId + "%")
                    .page(page, size).list();
        } else if (fa) {
            return find("tenantId = ?1 and lower(action) like lower(?2) order by occurredAt desc",
                    tenantId, "%" + action + "%")
                    .page(page, size).list();
        } else {
            return find("tenantId = ?1 order by occurredAt desc", tenantId)
                    .page(page, size).list();
        }
    }

    /** Matching count — used to compute totalElements and totalPages. */
    public static Uni<Long> countByTenant(UUID tenantId, String userId, String action) {
        boolean fu = userId != null && !userId.isBlank();
        boolean fa = action != null && !action.isBlank();

        if (fu && fa) {
            return count("tenantId = ?1 and lower(userId) like lower(?2) " +
                            "and lower(action) like lower(?3)",
                    tenantId, "%" + userId + "%", "%" + action + "%");
        } else if (fu) {
            return count("tenantId = ?1 and lower(userId) like lower(?2)",
                    tenantId, "%" + userId + "%");
        } else if (fa) {
            return count("tenantId = ?1 and lower(action) like lower(?2)",
                    tenantId, "%" + action + "%");
        } else {
            return count("tenantId = ?1", tenantId);
        }
    }
}