package com.decisionmesh.contracts.security.entity;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import io.smallrye.mutiny.Uni;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(
        name = "tenants",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_tenants_external_id", columnNames = "external_id")
        }
)
public class TenantEntity extends PanacheEntityBase {

    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    public UUID id;

    @Column(name = "external_id", nullable = false, unique = true, length = 255)
    public String externalId;

    @Column(name = "name", nullable = false, length = 255)
    public String name;

    @Column(name = "account_type", length = 20)
    public String accountType;       // "INDIVIDUAL" | "ORGANIZATION"

    @Column(name = "keycloak_group_id", length = 100)
    public String keycloakGroupId;


    @Column(name = "status", nullable = false, length = 50)
    public String status = "ACTIVE";

    /**
     * Tenant-level configuration stored as JSONB.
     * Mapped to Map<String, Object> so Hibernate serialises/deserialises
     * it directly without a double JSON string round-trip.
     * Use Jackson or Hibernate's built-in JSON codec — no manual parsing needed.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config", columnDefinition = "jsonb")
    public Map<String, Object> config;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    public OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;


    // ── Reactive finders ──────────────────────────────────────────────────────

    /**
     * Finds a tenant by its external IdP identifier (e.g. Keycloak realm name).
     * Must be called inside a Panache session context:
     *   Panache.withSession(() -> TenantEntity.findByExternalId(id))
     */
    public static Uni<TenantEntity> findByExternalId(String externalId) {
        return find("externalId", externalId).firstResult();
    }

    /**
     * Finds a tenant by display name.
     * Must be called inside a Panache session context.
     */
    public static Uni<TenantEntity> findByName(String name) {
        return find("name", name).firstResult();
    }

    public static Uni<TenantEntity> upsert(TenantEntity tenant) {
        return getSession().chain(session ->
                session.createNativeQuery(
                                "INSERT INTO tenants (id, external_id, name, status, created_at, updated_at) " +
                                        "VALUES (:id, :externalId, :name, :status, NOW(), NOW()) " +
                                        "ON CONFLICT (external_id) DO NOTHING " +
                                        "RETURNING id", TenantEntity.class)
                        .setParameter("id",         UUID.randomUUID())
                        .setParameter("externalId", tenant.externalId)
                        .setParameter("name",       tenant.name)
                        .setParameter("status",     tenant.status)
                        .getSingleResultOrNull()
        );
    }
}