package com.decisionmesh.persistence.entity;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import io.smallrye.mutiny.Uni;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(
        name = "policy_evaluations",
        indexes = {
                @Index(name = "idx_poleval_intent", columnList = "intent_id"),
                @Index(name = "idx_poleval_tenant", columnList = "tenant_id")
        }
)
public class PolicyEvaluationEntity extends PanacheEntityBase {

    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    public UUID id;

    @Column(name = "intent_id", nullable = false)
    public UUID intentId;

    @Column(name = "policy_id", nullable = false)
    public UUID policyId;

    @Column(name = "tenant_id", nullable = false)
    public UUID tenantId;

    @Column(name = "phase", nullable = false, length = 50)
    public String phase;

    @Column(name = "result", nullable = false, length = 50)
    public String result = "ALLOWED";

    @Column(name = "enforcement_mode", nullable = false, length = 50)
    public String enforcementMode = "LOG_ONLY";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "context_snapshot", nullable = false, columnDefinition = "jsonb")
    public Map<String, Object> contextSnapshot;

    @Column(name = "block_reason", length = 512)
    public String blockReason;

    @Column(name = "adapter_id")
    public UUID adapterId;

    @Column(name = "attempt_number")
    public Integer attemptNumber;

    @CreationTimestamp
    @Column(name = "evaluated_at", nullable = false, updatable = false)
    public OffsetDateTime evaluatedAt;

    // ── Reactive finders ──────────────────────────────────────────────

    public static Uni<List<PolicyEvaluationEntity>> findByIntent(UUID intentId) {
        return find("intentId = ?1 order by evaluatedAt asc", intentId).list();
    }

    public static Uni<List<PolicyEvaluationEntity>> findViolationsByTenant(UUID tenantId) {
        return find("tenantId = ?1 and result = 'VIOLATION' order by evaluatedAt desc",
                tenantId).list();
    }
}
