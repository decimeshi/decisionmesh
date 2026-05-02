package com.decisionmesh.governance.store.impl;

import com.decisionmesh.common.ledger.LedgerEntry;
import com.decisionmesh.common.store.LedgerStore;
import com.decisionmesh.governance.entity.LedgerEntryEntity;
import com.decisionmesh.governance.repository.LedgerRepository;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class LedgerStoreImpl implements LedgerStore {

    @Inject
    LedgerRepository repository;

    // ── append ────────────────────────────────────────────────────────────────

    @Override
    public Uni<Void> append(LedgerEntry entry) {
        LedgerEntryEntity entity = new LedgerEntryEntity();

        // Identity
        entity.intentId         = entry.getIntentId();
        entity.tenantId         = entry.getTenantId();
        entity.aggregateVersion = entry.getAggregateVersion();  // was hardcoded 0
        entity.eventId          = entry.getEventId();           // was UUID.randomUUID()
        entity.eventType        = entry.getEventType();         // was hardcoded "EVENT"

        // Snapshots — each field mapped correctly
        // was: entry.computeDeterministicPayload() written to policySnapshotJson only
        entity.policySnapshotJson = entry.getPolicySnapshotJson();
        entity.budgetSnapshotJson = entry.getBudgetSnapshotJson();
        entity.slaSnapshotJson    = entry.getSlaSnapshotJson();

        // Integrity chain
        entity.previousHash = entry.getPreviousHash();
        entity.currentHash  = entry.getCurrentHash();

        // Timestamp from the entry, not generated here
        entity.timestamp = entry.getTimestamp();

        Log.debugf("Appending ledger entry: intentId=%s eventId=%s version=%d",
                entity.intentId, entity.eventId, entity.aggregateVersion);

        return repository.persist(entity).replaceWithVoid();
    }

    // ── load ──────────────────────────────────────────────────────────────────

    @Override
    public Uni<List<LedgerEntry>> load(UUID intentId) {
        return repository.findByIntentId(intentId)
                .map(list -> list.stream()
                        .map(this::toDomain)
                        .toList());
    }

    // ── mapping ───────────────────────────────────────────────────────────────

    private LedgerEntry toDomain(LedgerEntryEntity e) {
        return new LedgerEntry(
                e.id,              // was UUID.randomUUID() — loses the real DB identity
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
                e.timestamp
        );
    }
}