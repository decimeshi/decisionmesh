package com.decisionmesh.persistence.entity;

import com.decisionmesh.persistence.converter.MapListJsonConverter;
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import io.smallrye.mutiny.Uni;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(
        name = "intent_plans",
        indexes = {
                @Index(name = "idx_plans_intent", columnList = "intent_id"),
                @Index(name = "idx_plans_tenant", columnList = "tenant_id")
        }
)
public class IntentPlanEntity extends PanacheEntityBase {

    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    public UUID id;

    @Column(name = "intent_id", nullable = false)
    public UUID intentId;

    @Column(name = "tenant_id", nullable = false)
    public UUID tenantId;

    @Column(name = "plan_version", nullable = false)
    public int planVersion = 1;

    @Column(name = "strategy", nullable = false, length = 50)
    public String strategy = "SINGLE_ADAPTER";

    @Column(name = "status", nullable = false, length = 50)
    public String status = "ACTIVE";

    /**
     * Ranking snapshot stored as JSONB array of objects.
     * Uses AttributeConverter instead of @JdbcTypeCode(SqlTypes.JSON) because
     * Hibernate Reactive's ReactiveJsonJdbcType uses Vert.x JsonObject which
     * cannot bind JSON arrays — throws DecodeException on any List field.
     */
    @Convert(converter = MapListJsonConverter.class)
    @Column(name = "ranking_snapshot", nullable = false, columnDefinition = "jsonb")
    public List<Map<String, Object>> rankingSnapshot = new ArrayList<>();

    /**
     * Budget allocation stored as JSONB object — Map<String, Object> is safe
     * with @JdbcTypeCode(SqlTypes.JSON) since it serializes to {} not [].
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "budget_allocation", nullable = false, columnDefinition = "jsonb")
    public Map<String, Object> budgetAllocation;

    @Column(name = "was_exploration", nullable = false)
    public boolean wasExploration = false;

    @Column(name = "planner_notes", columnDefinition = "TEXT")
    public String plannerNotes;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    public OffsetDateTime createdAt;

    // ── Reactive finders ──────────────────────────────────────────────────────

    public static Uni<IntentPlanEntity> findActiveByIntent(UUID intentId) {
        return find("intentId = ?1 and status = 'ACTIVE' order by planVersion desc", intentId)
                .firstResult();
    }

    public static Uni<List<IntentPlanEntity>> findAllByIntent(UUID intentId) {
        return find("intentId = ?1 order by planVersion asc", intentId).list();
    }

    public static Uni<IntentPlanEntity> findLatestByIntent(UUID intentId) {
        return find("intentId = ?1 order by planVersion desc", intentId).firstResult();
    }
}