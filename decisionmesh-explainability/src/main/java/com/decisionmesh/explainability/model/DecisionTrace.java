package com.decisionmesh.explainability.model;

import java.time.Instant;
import java.util.UUID;

public class DecisionTrace {

    private final UUID decisionId;
    private final UUID intentId;
    private final String tenantId;
    private final String decisionType;
    private final String inputsSnapshotJson;
    private final String scoringSnapshotJson;
    private final String policySnapshotJson;
    private final String portfolioContextJson;
    private final String rationale;
    private final Instant timestamp;

    public DecisionTrace(UUID decisionId,
                         UUID intentId,
                         String tenantId,
                         String decisionType,
                         String inputsSnapshotJson,
                         String scoringSnapshotJson,
                         String policySnapshotJson,
                         String portfolioContextJson,
                         String rationale,
                         Instant timestamp) {
        this.decisionId = decisionId;
        this.intentId = intentId;
        this.tenantId = tenantId;
        this.decisionType = decisionType;
        this.inputsSnapshotJson = inputsSnapshotJson;
        this.scoringSnapshotJson = scoringSnapshotJson;
        this.policySnapshotJson = policySnapshotJson;
        this.portfolioContextJson = portfolioContextJson;
        this.rationale = rationale;
        this.timestamp = timestamp;
    }

    public UUID getDecisionId() { return decisionId; }
    public UUID getIntentId() { return intentId; }
    public String getDecisionType() { return decisionType; }
}