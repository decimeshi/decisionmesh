package com.decisionmesh.intelligence.ledger;

import java.time.Instant;
import java.util.UUID;

public class ExplorationLedgerEntry {

    private final UUID entryId;
    private final String adapterId;
    private final String intentType;
    private final boolean exploration;
    private final double reward;
    private final double regret;
    private final double confidence;
    private final Instant timestamp;

    public ExplorationLedgerEntry(UUID entryId,
                                   String adapterId,
                                   String intentType,
                                   boolean exploration,
                                   double reward,
                                   double regret,
                                   double confidence,
                                   Instant timestamp) {
        this.entryId = entryId;
        this.adapterId = adapterId;
        this.intentType = intentType;
        this.exploration = exploration;
        this.reward = reward;
        this.regret = regret;
        this.confidence = confidence;
        this.timestamp = timestamp;
    }
}