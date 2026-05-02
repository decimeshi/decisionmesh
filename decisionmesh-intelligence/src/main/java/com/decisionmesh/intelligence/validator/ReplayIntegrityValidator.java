package com.decisionmesh.intelligence.validator;

import com.decisionmesh.common.ledger.LedgerEntry;
import com.decisionmesh.governance.snapshot.PolicySnapshot;
import com.decisionmesh.governance.validator.LedgerValidator;
import com.decisionmesh.domain.event.DomainEvent;

import java.util.List;

public class ReplayIntegrityValidator {

    private final LedgerValidator ledgerValidator;

    public ReplayIntegrityValidator(LedgerValidator ledgerValidator) {
        this.ledgerValidator = ledgerValidator;
    }

    public void validate(List<LedgerEntry> ledgerEntries,
                         List<DomainEvent> eventStream,
                         PolicySnapshot snapshot) {

        ledgerValidator.validateChain(ledgerEntries, snapshot);

        if (ledgerEntries.size() != eventStream.size()) {
            throw new IllegalStateException("Event stream mismatch with ledger entries");
        }
    }
}