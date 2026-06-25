package com.decisionmesh.common.ledger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
        // Truncate timestamp to MICROSECONDS before hashing.
        // PostgreSQL stores timestamps at microsecond precision — Java Instant
        // has nanosecond precision. Without truncation, Instant.now() produces
        // "...842983000Z" at write time but PostgreSQL returns "...842983Z" at
        // read time, causing a hash mismatch even though the value is the same.
        String ts = timestamp != null
                ? timestamp.truncatedTo(ChronoUnit.MICROS).toString()
                : "";

        return (intentId  != null ? intentId.toString()  : "")
                + (tenantId           != null ? tenantId           : "")
                + aggregateVersion
                + (eventId   != null ? eventId.toString()   : "")
                + (eventType          != null ? eventType          : "")
                + (policySnapshotJson != null ? policySnapshotJson : "")
                + (budgetSnapshotJson != null ? budgetSnapshotJson : "")
                + (slaSnapshotJson    != null ? slaSnapshotJson    : "")
                + (previousHash       != null ? previousHash       : "")
                + ts;
    }

    public UUID getLedgerId()             { return ledgerId; }
    public UUID getIntentId()             { return intentId; }
    public String getTenantId()           { return tenantId; }
    public long getAggregateVersion()     { return aggregateVersion; }
    public UUID getEventId()              { return eventId; }
    public String getEventType()          { return eventType; }
    public String getPolicySnapshotJson() { return policySnapshotJson; }
    public String getBudgetSnapshotJson() { return budgetSnapshotJson; }
    public String getSlaSnapshotJson()    { return slaSnapshotJson; }
    public String getPreviousHash()       { return previousHash; }
    public String getCurrentHash()        { return currentHash; }
    public Instant getTimestamp()         { return timestamp; }
}
