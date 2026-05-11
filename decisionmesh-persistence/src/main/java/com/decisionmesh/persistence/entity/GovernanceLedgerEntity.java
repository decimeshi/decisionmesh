package com.decisionmesh.persistence.entity;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Persisted ledger entry — one row per governance decision event.
 * Forms an immutable, cryptographically chained audit trail.
 */
@Entity
@Table(name = "governance_ledger",
       indexes = {
           @Index(name = "idx_ledger_intent_id",     columnList = "intent_id"),
           @Index(name = "idx_ledger_tenant_intent",  columnList = "tenant_id, intent_id"),
       })
public class GovernanceLedgerEntity extends PanacheEntityBase {

    @Id
    @Column(name = "ledger_id", updatable = false, nullable = false)
    public UUID ledgerId;

    @Column(name = "intent_id", nullable = false)
    public UUID intentId;

    @Column(name = "tenant_id", nullable = false)
    public UUID tenantId;

    @Column(name = "aggregate_version", nullable = false)
    public long aggregateVersion;

    @Column(name = "event_id", nullable = false)
    public UUID eventId;

    @Column(name = "event_type", nullable = false, length = 120)
    public String eventType;

    @Column(name = "policy_snapshot", columnDefinition = "TEXT")
    public String policySnapshot;

    @Column(name = "budget_snapshot", columnDefinition = "TEXT")
    public String budgetSnapshot;

    @Column(name = "sla_snapshot", columnDefinition = "TEXT")
    public String slaSnapshot;

    @Column(name = "previous_hash", length = 64)
    public String previousHash;

    @Column(name = "current_hash", nullable = false, length = 64)
    public String currentHash;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    // ── Queries ───────────────────────────────────────────────────────────────

    public static io.smallrye.mutiny.Uni<java.util.List<GovernanceLedgerEntity>>
    findByIntentOrdered(UUID intentId) {
        return list("intentId = ?1 order by aggregateVersion asc", intentId);
    }

    public static io.smallrye.mutiny.Uni<java.util.List<GovernanceLedgerEntity>>
    findByTenantAndIntentOrdered(UUID tenantId, UUID intentId) {
        return list("tenantId = ?1 and intentId = ?2 order by aggregateVersion asc",
                tenantId, intentId);
    }

    public static io.smallrye.mutiny.Uni<Long>
    countByIntent(UUID intentId) {
        return count("intentId", intentId);
    }
}
