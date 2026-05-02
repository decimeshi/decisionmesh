package com.decisionmesh.persistence.entity;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import io.smallrye.mutiny.Uni;
import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Immutable
@Entity
@Table(
        name = "intent_events",
        indexes = {
                @Index(name = "idx_events_intent", columnList = "intent_id"),
                @Index(name = "idx_events_tenant", columnList = "tenant_id")
        }
)
public class IntentEventEntity extends PanacheEntityBase {

    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    public UUID id;

    @Column(name = "event_id", nullable = false, unique = true)
    public UUID eventId;

    @Column(name = "intent_id", nullable = false)
    public UUID intentId;

    @Column(name = "tenant_id", nullable = false)
    public UUID tenantId;

    @Column(name = "version", nullable = false)
    public long version;

    @Column(name = "event_type", nullable = false, length = 255)
    public String eventType;

    @Column(name = "aggregate_type", nullable = false, length = 255)
    public String aggregateType = "Intent";

    @Column(name = "occurred_at", nullable = false)
    public OffsetDateTime occurredAt;


    /**
     * Payload stored as JSONB — mapped to Map<String, Object> so Hibernate's
     * JSON codec handles serialisation without a double-string round-trip.
     * DomainEvent.toJson() already returns Map<String, Object>.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb", nullable = false)
    public Map<String, Object> payload;

    @Column(name = "phase_from", length = 50)
    public String phaseFrom;

    @Column(name = "phase_to", length = 50)
    public String phaseTo;

    @Column(name = "actor_id")
    public UUID actorId;

    @Column(name = "actor_type", length = 100)
    public String actorType;

    @Column(name = "plan_id")
    public UUID planId;

    @Column(name = "plan_version")
    public Integer planVersion;

    @Column(name = "execution_id")
    public UUID executionId;

    @Column(name = "attempt_number")
    public Integer attemptNumber;

    @Column(name = "adapter_id")
    public UUID adapterId;

    @Column(name = "policy_id")
    public UUID policyId;

    @Column(name = "drift_score_snapshot", precision = 5, scale = 4)
    public BigDecimal driftScoreSnapshot;

    @Column(name = "cost_usd_snapshot", precision = 12, scale = 6)
    public BigDecimal costUsdSnapshot;

    @Column(name = "risk_score_snapshot", precision = 5, scale = 4)
    public BigDecimal riskScoreSnapshot;

    @Column(name = "trace_id", length = 64)
    public String traceId;

    @Column(name = "span_id", length = 64)
    public String spanId;

    @Column(name = "parent_span_id", length = 64)
    public String parentSpanId;

    // ── Reactive finders ──────────────────────────────────────────────

    public static Uni<List<IntentEventEntity>> findByIntent(UUID intentId) {
        return find("intentId = ?1 order by occurredAt asc", intentId).list();
    }

    public static Uni<List<IntentEventEntity>> findByTenantAndIntent(
            UUID tenantId, UUID intentId) {
        return find("tenantId = ?1 and intentId = ?2 order by occurredAt asc",
                tenantId, intentId).list();
    }
}
