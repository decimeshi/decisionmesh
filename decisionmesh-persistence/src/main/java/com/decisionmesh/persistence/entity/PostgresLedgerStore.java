package com.decisionmesh.persistence.entity;

import com.decisionmesh.common.ledger.LedgerEntry;
import com.decisionmesh.common.store.LedgerStore;
import com.decisionmesh.governance.entity.LedgerEntryEntity;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.UUID;

/**
 * PostgreSQL-backed LedgerStore using LedgerEntryEntity (Panache).
 * Persists governance ledger entries and loads them in aggregate version
 * order for deterministic replay.
 */
@ApplicationScoped
@Priority(1)
public class PostgresLedgerStore implements LedgerStore {

    // ── Append ────────────────────────────────────────────────────────────────

    @Override
    @WithTransaction
    public Uni<Void> append(LedgerEntry entry) {
        LedgerEntryEntity entity = toEntity(entry);
        return entity.persist()
                .invoke(() -> Log.debugf("[LedgerStore] Persisted entry %s for intent %s (v%d)",
                        entity.ledgerId, entity.intentId, entity.aggregateVersion))
                .replaceWithVoid()
                .onFailure().invoke(ex ->
                        Log.errorf("[LedgerStore] Failed to persist entry for intent %s: %s",
                                entry.getIntentId(), ex.getMessage()));
    }

    // ── Load all entries for an intent ────────────────────────────────────────

    @Override
    @WithSession
    public Uni<List<LedgerEntry>> load(UUID intentId) {
        return LedgerEntryEntity
                .findByIntentOrdered(intentId)
                .map(entities -> entities.stream()
                        .map(this::toDomain)
                        .toList())
                .invoke(entries -> Log.debugf("[LedgerStore] Loaded %d entries for intent %s",
                        entries.size(), intentId));
    }

    // ── Tenant-scoped load (used by ReplayResource) ───────────────────────────

    @WithSession
    public Uni<List<LedgerEntry>> loadForTenant(String tenantId, UUID intentId) {
        return LedgerEntryEntity
                .findByTenantAndIntentOrdered(tenantId, intentId)
                .map(entities -> entities.stream()
                        .map(this::toDomain)
                        .toList());
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    private LedgerEntryEntity toEntity(LedgerEntry e) {
        LedgerEntryEntity entity  = new LedgerEntryEntity();
        entity.ledgerId           = e.getLedgerId() != null ? e.getLedgerId() : UUID.randomUUID();
        entity.intentId           = e.getIntentId();
        entity.tenantId           = e.getTenantId();
        entity.aggregateVersion   = e.getAggregateVersion();
        entity.eventId            = e.getEventId();
        entity.eventType          = e.getEventType();
        entity.policySnapshotJson = e.getPolicySnapshotJson();
        entity.budgetSnapshotJson = e.getBudgetSnapshotJson();
        entity.slaSnapshotJson    = e.getSlaSnapshotJson();
        entity.previousHash       = e.getPreviousHash();
        entity.currentHash        = e.getCurrentHash();
        entity.timestamp          = e.getTimestamp();
        return entity;
    }

    private LedgerEntry toDomain(LedgerEntryEntity e) {
        return new LedgerEntry(
                e.getLedgerId(),
                e.intentId,
                e.tenantId,
                e.aggregateVersion,
                e.eventId,
                e.eventType,
                e.policySnapshotJson,
                e.budgetSnapshotJson,
                e.slaSnapshotJson,
                e.previousHash,
                e.currentHash,
                e.getTimestamp()
        );
    }
}