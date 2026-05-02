package com.decisionmesh.contracts.security.entity;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import io.smallrye.mutiny.Uni;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "tenant_idempotency",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_tenant_idempotency",
                        columnNames = {"tenant_id", "idempotency_key"}
                )
        }
)
public class TenantIdempotencyEntity extends PanacheEntityBase {

    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    public UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    public UUID tenantId;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 255)
    public String idempotencyKey;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    public OffsetDateTime createdAt;

    // ── Reactive finders ──────────────────────────────────────────────────────

    /**
     * Finds an idempotency record scoped to a tenant + key.
     * Composite unique constraint is (tenant_id, idempotency_key).
     */
    public static Uni<TenantIdempotencyEntity> findByTenantAndKey(UUID tenantId, String key) {
        return find("tenantId = ?1 and idempotencyKey = ?2", tenantId, key).firstResult();
    }

    /**
     * Finds by idempotency key only — used during tenant creation before
     * a tenantId has been assigned.
     */
    public static Uni<TenantIdempotencyEntity> findByKey(String key) {
        return find("idempotencyKey", key).firstResult();
    }

    /**
     * Convenience factory — creates a new idempotency record ready to persist.
     * Call inside Panache.withTransaction() to ensure atomicity.
     */
    public static TenantIdempotencyEntity of(UUID tenantId, String idempotencyKey) {
        TenantIdempotencyEntity entity = new TenantIdempotencyEntity();
        entity.tenantId       = tenantId;
        entity.idempotencyKey = idempotencyKey;
        return entity;
    }
}