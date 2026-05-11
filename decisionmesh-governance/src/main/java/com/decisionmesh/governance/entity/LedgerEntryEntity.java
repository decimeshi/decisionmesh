package com.decisionmesh.governance.entity;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import io.smallrye.mutiny.Uni;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Persistent governance ledger entry.
 *
 * FIX v2:
 *  1. Renamed @Id field from 'id' to 'ledgerId' to match domain model getLedgerId()
 *  2. Removed @Lob — Hibernate Reactive does not support @Lob on TEXT columns;
 *     use @Column(columnDefinition = "TEXT") instead
 *  3. Added @Column mappings for snake_case DB columns
 *  4. Added static query methods needed by PostgresLedgerStore
 *  5. getLedgerId() now correctly returns the ledgerId field
 *  6. Table name aligned to 'ledger_entry' (matches existing entity definition)
 */
@Entity
@Table(
    name = "ledger_entry",
    indexes = {
        @Index(name = "idx_ledger_entry_intent_id",    columnList = "intent_id"),
        @Index(name = "idx_ledger_entry_tenant_intent", columnList = "tenant_id, intent_id"),
    }
)
public class LedgerEntryEntity extends PanacheEntityBase {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    public UUID ledgerId;               // FIX: was 'id', renamed to match getLedgerId()

    @Column(name = "intent_id", nullable = false)
    public UUID intentId;

    @Column(name = "tenant_id", nullable = false)
    public String tenantId;

    @Column(name = "aggregate_version", nullable = false)
    public long aggregateVersion;

    @Column(name = "event_id")
    public UUID eventId;

    @Column(name = "event_type", length = 120)
    public String eventType;

    // FIX: @Lob removed — Hibernate Reactive (Vert.x/Agroal) treats @Lob
    // as BLOB/CLOB which causes type binding issues. Use TEXT instead.
    @Column(name = "policy_snapshot_json", columnDefinition = "TEXT")
    public String policySnapshotJson;

    @Column(name = "budget_snapshot_json", columnDefinition = "TEXT")
    public String budgetSnapshotJson;

    @Column(name = "sla_snapshot_json", columnDefinition = "TEXT")
    public String slaSnapshotJson;

    @Column(name = "previous_hash", length = 64)
    public String previousHash;

    @Column(name = "current_hash", nullable = false, length = 64)
    public String currentHash;

    @Column(name = "timestamp", nullable = false)
    public Instant timestamp;

    // ── Getters (used by PostgresLedgerStore mapping) ─────────────────────────

    public UUID getLedgerId()   { return ledgerId; }   // FIX: was returning undefined 'ledgerId'
    public Instant getTimestamp() { return timestamp; }

    // ── Static queries ────────────────────────────────────────────────────────

    /**
     * All entries for an intent, ordered by aggregate version ascending.
     * Used by LedgerStore.load() for replay and chain validation.
     */
    public static Uni<List<LedgerEntryEntity>> findByIntentOrdered(UUID intentId) {
        return list("intentId = ?1 order by aggregateVersion asc", intentId);
    }

    /**
     * Tenant-scoped load — prevents cross-tenant replay.
     */
    public static Uni<List<LedgerEntryEntity>> findByTenantAndIntentOrdered(
            String tenantId, UUID intentId) {
        return list("tenantId = ?1 and intentId = ?2 order by aggregateVersion asc",
                tenantId, intentId);
    }

    /**
     * Count entries for an intent — used for health/metrics.
     */
    public static Uni<Long> countByIntent(UUID intentId) {
        return count("intentId", intentId);
    }
}
