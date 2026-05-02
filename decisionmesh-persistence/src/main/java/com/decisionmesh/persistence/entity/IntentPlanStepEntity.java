package com.decisionmesh.persistence.entity;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import io.smallrye.mutiny.Uni;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(
        name = "intent_plan_steps",
        indexes = {
                @Index(name = "idx_plan_steps_plan",   columnList = "plan_id"),
                @Index(name = "idx_plan_steps_intent", columnList = "intent_id")
        }
)
public class IntentPlanStepEntity extends PanacheEntityBase {

    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    public UUID id;

    @Column(name = "plan_id", nullable = false)
    public UUID planId;

    @Column(name = "tenant_id", nullable = false)
    public UUID tenantId;

    @Column(name = "intent_id", nullable = false)
    public UUID intentId;

    @Column(name = "step_order", nullable = false)
    public int stepOrder = 0;

    @Column(name = "adapter_id")
    public UUID adapterId;

    @Column(name = "step_type", nullable = false, length = 50)
    public String stepType = "PRIMARY";

    @Column(name = "is_conditional", nullable = false)
    public boolean isConditional = false;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "condition_expr", columnDefinition = "jsonb")
    public Map<String, Object> conditionExpr;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config_snapshot", nullable = false, columnDefinition = "jsonb")
    public Map<String, Object> configSnapshot;

    @Column(name = "estimated_cost_usd", precision = 12, scale = 6)
    public BigDecimal estimatedCostUsd;

    @Column(name = "estimated_latency_ms")
    public Long estimatedLatencyMs;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    public OffsetDateTime createdAt;

    // ── Reactive finders ──────────────────────────────────────────────

    public static Uni<List<IntentPlanStepEntity>> findByPlanOrdered(UUID planId) {
        return find("planId = ?1 order by stepOrder asc", planId).list();
    }

    public static Uni<List<IntentPlanStepEntity>> findByIntentOrdered(UUID intentId) {
        return find("intentId = ?1 order by stepOrder asc", intentId).list();
    }
}
