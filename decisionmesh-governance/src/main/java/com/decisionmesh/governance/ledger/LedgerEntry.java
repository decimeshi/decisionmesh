package com.decisionmesh.governance.ledger;

import java.time.Instant;
import java.util.UUID;

public class LedgerEntry {

    private final UUID ledgerId;
    private final UUID intentId;
    private final String tenantId;
    private final long aggregateVersion;
    private final UUID eventId;
    private final String eventType;
    private final String policySnapshotJson;
    private final String budgetSnapshotJson;
    private final String slaSnapshotJson;
    private final String previousHash;
    private final String currentHash;
    private final Instant timestamp;

    public LedgerEntry(UUID ledgerId,
                       UUID intentId,
                       String tenantId,
                       long aggregateVersion,
                       UUID eventId,
                       String eventType,
                       String policySnapshotJson,
                       String budgetSnapshotJson,
                       String slaSnapshotJson,
                       String previousHash,
                       String currentHash,
                       Instant timestamp) {
        this.ledgerId = ledgerId;
        this.intentId = intentId;
        this.tenantId = tenantId;
        this.aggregateVersion = aggregateVersion;
        this.eventId = eventId;
        this.eventType = eventType;
        this.policySnapshotJson = policySnapshotJson;
        this.budgetSnapshotJson = budgetSnapshotJson;
        this.slaSnapshotJson = slaSnapshotJson;
        this.previousHash = previousHash;
        this.currentHash = currentHash;
        this.timestamp = timestamp;
    }

    public String computeDeterministicPayload() {
        return intentId + tenantId + aggregateVersion + eventId + eventType +
               policySnapshotJson + budgetSnapshotJson + slaSnapshotJson +
               previousHash + timestamp.toString();
    }

    public String getCurrentHash() { return currentHash; }
    public String getPreviousHash() { return previousHash; }
}