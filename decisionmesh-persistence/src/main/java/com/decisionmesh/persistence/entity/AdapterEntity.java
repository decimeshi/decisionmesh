package com.decisionmesh.persistence.entity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import io.smallrye.mutiny.Uni;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcType;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;
import com.decisionmesh.persistence.type.VertxJsonbStringJdbcType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * JPA entity for the adapters table.
 *
 * JSONB fields stored as String — matches IntentEntity.payload pattern.
 * Never used directly as a JAX-RS request body (use AdapterRequest DTO instead)
 * to avoid Jackson type mismatch errors on JSONB fields.
 */
@Entity
@Table(name = "adapters", indexes = {
        @Index(name = "idx_adapters_tenant",   columnList = "tenant_id"),
        @Index(name = "idx_adapters_active",   columnList = "tenant_id, is_active"),
        @Index(name = "idx_adapters_type",     columnList = "tenant_id, adapter_type"),
        @Index(name = "idx_adapters_provider", columnList = "tenant_id, provider"),
})
public class AdapterEntity extends PanacheEntityBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    public UUID id;

    @Column(name = "tenant_id", nullable = true)
    public UUID tenantId; // NULL = platform-wide adapter available to all tenants

    @Column(name = "name", nullable = false, length = 255)
    public String name;

    @Column(name = "adapter_type", nullable = false, length = 100)
    public String adapterType;

    @Column(name = "provider", nullable = false, length = 100)
    public String provider;

    @Column(name = "model_id", length = 255)
    public String modelId;

    @Column(name = "region", length = 100)
    public String region;

    @Column(name = "base_cost_per_token", precision = 18, scale = 8)
    public BigDecimal baseCostPerToken;

    @Column(name = "max_tokens_per_call")
    public Integer maxTokensPerCall;

    @Column(name = "avg_latency_ms")
    public Long avgLatencyMs;

    @Column(name = "is_active", nullable = false)
    public boolean isActive = true;

    // JSONB fields mapped to String via VertxJsonbStringJdbcType.
    //
    // WHY NOT @JdbcTypeCode(SqlTypes.VARCHAR):
    //   SqlTypes.VARCHAR makes Hibernate call getString() on the ResultSet.
    //   Hibernate Reactive's Vert.x PG client decodes jsonb at the wire-protocol
    //   level into JsonObject ({}) or JsonArray ([]) — getString() then throws:
    //     ClassCastException: Invalid String value type class JsonArray
    //
    // FIX — VertxJsonbStringJdbcType:
    //   Calls getObject() to get the raw Vert.x value, then toString() to convert.
    //   JsonObject.toString() → "{...}"  |  JsonArray.toString() → "[...]"
    //   Binds back to the DB as Types.OTHER — PostgreSQL casts text → jsonb.
    @JdbcType(VertxJsonbStringJdbcType.class)
    @Column(name = "config", columnDefinition = "jsonb")
    public String config = "{}";

    @JdbcType(VertxJsonbStringJdbcType.class)
    @Column(name = "capability_flags", columnDefinition = "jsonb")
    public String capabilityFlags = "{}";

    @JdbcType(VertxJsonbStringJdbcType.class)
    @Column(name = "allowed_intent_types", columnDefinition = "jsonb")
    public String allowedIntentTypes = "[]";

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    public OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void initJsonbDefaults() {
        if (config == null || config.isBlank())                   config = "{}";
        if (capabilityFlags == null || capabilityFlags.isBlank()) capabilityFlags = "{}";
        if (allowedIntentTypes == null || allowedIntentTypes.isBlank()) allowedIntentTypes = "[]";
    }

    // ── Parsed accessors ──────────────────────────────────────────────────────

    public Map<String, Object> getConfigMap() {
        if (config == null || config.isBlank() || "{}".equals(config)) return Map.of();
        try { return MAPPER.readValue(config, new TypeReference<>() {}); }
        catch (Exception e) { return Map.of(); }
    }

    public List<String> getAllowedIntentTypesList() {
        if (allowedIntentTypes == null || "[]".equals(allowedIntentTypes)) return List.of();
        try { return MAPPER.readValue(allowedIntentTypes, new TypeReference<>() {}); }
        catch (Exception e) { return List.of(); }
    }

    // ── Reactive finders ──────────────────────────────────────────────────────

    public static Uni<List<AdapterEntity>> findByTenant(UUID tenantId) {
        return find("tenantId = ?1 order by createdAt asc", tenantId).list();
    }

    public static Uni<List<AdapterEntity>> findActiveByTenant(UUID tenantId) {
        return find("tenantId = ?1 and isActive = true order by name asc", tenantId).list();
    }

    public static Uni<AdapterEntity> findByTenantAndId(UUID tenantId, UUID adapterId) {
        return find("tenantId = ?1 and id = ?2", tenantId, adapterId).firstResult();
    }

    /**
     * Returns all adapters visible to this tenant — both tenant-owned (BYOM)
     * and global shared adapters (tenantId IS NULL, e.g. platform Claude Haiku).
     * Tenant-owned adapters sort first; global ones follow.
     *
     * Used by AdapterResource LIST — read-only. Write operations (update/toggle/
     * delete) still use findByTenantAndId() to prevent tenants mutating globals.
     */
    public static Uni<List<AdapterEntity>> findByTenantOrGlobal(UUID tenantId) {
        return find("(tenantId = ?1 or tenantId is null) order by tenantId asc nulls last, createdAt asc", tenantId).list();
    }

    /**
     * Returns active adapters visible to this tenant — both tenant-owned and global.
     * Used by the execution engine (AdapterRepository.findActiveByTenant) to select
     * which adapters are eligible for dispatching an intent.
     */
    public static Uni<List<AdapterEntity>> findActiveByTenantOrGlobal(UUID tenantId) {
        return find("(tenantId = ?1 or tenantId is null) and isActive = true order by tenantId asc nulls last, name asc", tenantId).list();
    }
}