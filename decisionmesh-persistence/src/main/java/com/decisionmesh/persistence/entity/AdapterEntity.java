package com.decisionmesh.persistence.entity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import io.smallrye.mutiny.Uni;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

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

    @Column(name = "tenant_id", nullable = false)
    public UUID tenantId;

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

    // config and capabilityFlags: @JdbcTypeCode(SqlTypes.JSON) works for objects {}.
    // ReactiveJsonJdbcType.toJsonObject() calls new JsonObject(value) which handles {} fine.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config", columnDefinition = "jsonb")
    public String config = "{}";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "capability_flags", columnDefinition = "jsonb")
    public String capabilityFlags = "{}";

    // allowedIntentTypes MUST NOT use @JdbcTypeCode(SqlTypes.JSON).
    // ReactiveJsonJdbcType.toJsonObject() calls new JsonObject("[]") for arrays
    // which throws DecodeException — JsonObject only handles {} not [].
    // SqlTypes.VARCHAR bypasses ReactiveJsonJdbcType entirely: the String "[]"
    // is bound as text and PostgreSQL casts it to jsonb implicitly.
    @JdbcTypeCode(SqlTypes.VARCHAR)
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
}