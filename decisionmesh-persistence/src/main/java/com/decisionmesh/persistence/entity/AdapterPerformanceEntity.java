package com.decisionmesh.persistence.entity;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import io.smallrye.mutiny.Uni;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(
        name = "adapter_performance_profiles",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_profile_adapter_tenant",
                columnNames = {"adapter_id", "tenant_id"}
        ),
        indexes = {
                @Index(name = "idx_profile_tenant",    columnList = "tenant_id"),
                @Index(name = "idx_profile_composite", columnList = "tenant_id, composite_score")
        }
)
public class AdapterPerformanceEntity extends PanacheEntityBase {

    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    public UUID id;

    @Column(name = "adapter_id", nullable = false)
    public UUID adapterId;

    @Column(name = "tenant_id", nullable = false)
    public UUID tenantId;

    @Column(name = "ema_cost", nullable = false)
    public double emaCost = 0.0;

    @Column(name = "ema_latency_ms", nullable = false)
    public double emaLatencyMs = 0.0;

    @Column(name = "ema_success_rate", nullable = false)
    public double emaSuccessRate = 1.0;

    @Column(name = "ema_risk_score", nullable = false)
    public double emaRiskScore = 0.0;

    @Column(name = "ema_confidence", nullable = false)
    public double emaConfidence = 0.0;

    @Column(name = "composite_score", nullable = false)
    public double compositeScore = 0.0;

    @Column(name = "execution_count", nullable = false)
    public long executionCount = 0;

    @Column(name = "success_count", nullable = false)
    public long successCount = 0;

    @Column(name = "failure_count", nullable = false)
    public long failureCount = 0;

    @Column(name = "cold_start", nullable = false)
    public boolean coldStart = true;

    @Column(name = "cold_start_threshold", nullable = false)
    public int coldStartThreshold = 10;

    @Column(name = "is_degraded", nullable = false)
    public boolean isDegraded = false;

    @Column(name = "degraded_since")
    public OffsetDateTime degradedSince;

    @Column(name = "degraded_reason", length = 255)
    public String degradedReason;

    @Column(name = "last_executed_at")
    public OffsetDateTime lastExecutedAt;

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

    public static Uni<AdapterPerformanceEntity> findByAdapterAndTenant(
            UUID tenantId, UUID adapterId) {

        return find("tenantId = ?1 and adapterId = ?2",
                tenantId, adapterId)
                .firstResult();
    }


    public static Uni<List<AdapterPerformanceEntity>> findActiveByTenant(UUID tenantId) {
        return find("tenantId = ?1 and isDegraded = false order by compositeScore desc", tenantId)
                .list();
    }
}
