package com.decisionmesh.persistence.entity;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import io.smallrye.mutiny.Uni;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * JPA projection of the Intent aggregate for PostgreSQL storage.
 *
 * Design notes:
 *   - id is set from the domain object — never DB-generated (@UuidGenerator removed)
 *   - version is managed by the domain, not by Hibernate (@Version removed to
 *     avoid conflicts with Intent.version)
 *   - payload (JSONB) stores the full serialized Intent — used by
 *     CachedIntentRepository to rehydrate Intent.fromJson() on cache miss
 *   - Queryable scalar columns (phase, tenantId, terminal etc.) exist alongside
 *     payload for efficient SQL filtering without parsing JSONB
 *   - @CreationTimestamp / @UpdateTimestamp removed — timestamps are managed
 *     by the domain object and written explicitly to avoid drift
 */
@Entity
@Table(
        name = "intents",
        indexes = {
                @Index(name = "idx_intents_tenant",
                        columnList = "tenant_id, created_at DESC"),
                @Index(name = "idx_intents_tenant_phase",
                        columnList = "tenant_id, phase, created_at DESC"),
                @Index(name = "idx_intents_terminal",
                        columnList = "tenant_id, terminal")
        }
)
public class IntentEntity extends PanacheEntityBase {

    // ── Identity ──────────────────────────────────────────────────────────────

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    public UUID id;                         // set from Intent.getId() — never DB-generated

    @Column(name = "tenant_id", nullable = false, updatable = false)
    public UUID tenantId;

    @Column(name = "user_id")
    public UUID userId;

    // ── Immutable classification ──────────────────────────────────────────────

    @Column(name = "intent_type", nullable = false, updatable = false, length = 255)
    public String intentType;

    // ── Mutable state — updated on every save ─────────────────────────────────

    @Column(name = "phase", nullable = false, length = 50)
    public String phase;

    @Column(name = "satisfaction_state", nullable = false, length = 50)
    public String satisfactionState = "UNKNOWN";

    @Column(name = "retry_count", nullable = false)
    public int retryCount = 0;

    @Column(name = "max_retries", nullable = false)
    public int maxRetries = 0;

    @Column(name = "terminal", nullable = false)
    public boolean terminal = false;

    // Domain version — managed by Intent aggregate, not by Hibernate.
    // @Version intentionally omitted: Hibernate's auto-increment conflicts with
    // the domain's own version tracking and causes OptimisticLockException.
    @Column(name = "version", nullable = false)
    public long version = 0;

    // ── Full aggregate payload — used for rehydration ─────────────────────────

    // Stores the complete Intent as JSON so Intent.fromJson() can reconstruct
    // the aggregate on a Redis cache miss without mapping every nested value type
    // (Budget, DriftScore, IntentObjective, IntentConstraints) to columns.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb", nullable = false)
    public String payload;

    // ── Timestamps — written explicitly from domain object ────────────────────

    @Column(name = "created_at", nullable = false, updatable = false)
    public OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;

    // Model tier used for this intent — drives credit cost calculation.
    // Populated from Intent.getModelTier() in the repository adapter.
    @Column(name = "model_tier", length = 20)
    public String modelTier;

    // ── Finders ───────────────────────────────────────────────────────────────

    public static Uni<IntentEntity> findByTenantAndId(UUID tenantId, UUID intentId) {
        return find("tenantId = ?1 and id = ?2", tenantId, intentId).firstResult();
    }
}