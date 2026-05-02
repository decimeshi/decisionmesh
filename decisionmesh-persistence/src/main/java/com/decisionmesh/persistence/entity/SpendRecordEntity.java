package com.decisionmesh.persistence.entity;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(
        name = "spend_records",
        indexes = {
                @Index(name = "idx_spend_intent", columnList = "intent_id"),
                @Index(name = "idx_spend_tenant", columnList = "tenant_id")
        }
)
public class SpendRecordEntity extends PanacheEntityBase {

    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    public UUID id;

    @Column(name = "intent_id", nullable = false)
    public UUID intentId;

    @Column(name = "execution_id", nullable = false)
    public UUID executionId;

    @Column(name = "tenant_id", nullable = false)
    public UUID tenantId;

    @Column(name = "adapter_id")
    public UUID adapterId;

    @Column(name = "amount_usd", nullable = false, precision = 12, scale = 6)
    public BigDecimal amountUsd = BigDecimal.ZERO;

    @Column(name = "token_count")
    public Integer tokenCount = 0;

    @Column(name = "budget_ceiling_usd", precision = 12, scale = 6)
    public BigDecimal budgetCeilingUsd;

    @CreationTimestamp
    @Column(name = "recorded_at", nullable = false, updatable = false)
    public OffsetDateTime recordedAt;

    // ── Reactive finders ──────────────────────────────────────────────────────

    public static Uni<List<SpendRecordEntity>> findByIntent(UUID intentId) {
        return find("intentId = ?1 order by recordedAt asc", intentId)
                .<SpendRecordEntity>list();
    }

    /**
     * Sums total spend for an intent reactively.
     *
     * Uses explicit .<SpendRecordEntity>list() type witness so the compiler
     * resolves the concrete type in the stream — without it, Panache's raw
     * return type causes "cannot find symbol: variable amountUsd".
     */
    @WithSession
    public static Uni<BigDecimal> totalSpendByIntent(UUID intentId) {
        return SpendRecordEntity.<SpendRecordEntity>find("intentId = ?1", intentId)
                .list()
                .map(records -> records.stream()
                        .map(r -> r.amountUsd != null ? r.amountUsd : BigDecimal.ZERO)
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
    }
}