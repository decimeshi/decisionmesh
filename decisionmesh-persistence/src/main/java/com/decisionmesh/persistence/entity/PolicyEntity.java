package com.decisionmesh.persistence.entity;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import io.smallrye.mutiny.Uni;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(
        name = "policies",
        indexes = {
                @Index(name = "idx_policies_tenant", columnList = "tenant_id"),
                @Index(name = "idx_policies_phase",  columnList = "tenant_id, phase, is_active")
        }
)
public class PolicyEntity extends PanacheEntityBase {

    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    public UUID id;

    @Column(name = "tenant_id", nullable = false)
    public UUID tenantId;

    @Column(name = "name", nullable = false, length = 255)
    public String name;

    @Column(name = "description", columnDefinition = "TEXT")
    public String description;

    @Column(name = "scope", nullable = false, length = 50)
    public String scope = "TENANT";

    @Column(name = "scope_ref_id")
    public UUID scopeRefId;

    @Column(name = "phase", nullable = false, length = 50)
    public String phase = "PRE_EXECUTION";

    @Column(name = "enforcement_mode", nullable = false, length = 50)
    public String enforcementMode = "LOG_ONLY";

    @Column(name = "policy_type", nullable = false, length = 100)
    public String policyType = "CUSTOM_DSL";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rule_dsl", nullable = false, columnDefinition = "jsonb")
    public Map<String, Object> ruleDsl;

    @Column(name = "priority", nullable = false)
    public int priority = 100;

    @Column(name = "is_active", nullable = false)
    public boolean isActive = true;

    @Version
    @Column(name = "version", nullable = false)
    public int version = 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    public OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;

    // ── Reactive finders ──────────────────────────────────────────────

    public static Uni<List<PolicyEntity>> findActiveByTenantAndPhase(
            UUID tenantId, String phase) {
        return find("tenantId = ?1 and phase = ?2 and isActive = true order by priority asc",
                tenantId, phase).list();
    }

    public static Uni<List<PolicyEntity>> findActiveByTenant(UUID tenantId) {
        return find("tenantId = ?1 and isActive = true order by priority asc", tenantId).list();
    }
}
