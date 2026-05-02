package com.decisionmesh.intelligence.validator;

import com.decisionmesh.intelligence.ledger.ExplorationLedgerEntry;

public class ExplorationConsistencyValidator {

    public void validate(ExplorationLedgerEntry entry) {
        if (entry == null) {
            throw new IllegalStateException("Missing exploration ledger entry");
        }
    }
}