package com.decisionmesh.governance.validator;

import com.decisionmesh.common.ledger.LedgerEntry;
import com.decisionmesh.common.hash.HashUtil;
import com.decisionmesh.governance.snapshot.PolicySnapshot;

import java.util.List;

public class LedgerValidator {

    public void validateChain(List<LedgerEntry> entries, PolicySnapshot snapshot) {

        // 🔥 STEP 1: POLICY ENFORCEMENT
        if (!snapshot.getDecision().isAllowed()) {
            throw new IllegalStateException(snapshot.getDecision().getReason());
        }

        // 🔒 STEP 2: LEDGER INTEGRITY
        String previousHash = "GENESIS";

        for (LedgerEntry entry : entries) {

            String recomputed = HashUtil.sha256(entry.computeDeterministicPayload());

            if (!recomputed.equals(entry.getCurrentHash()))
                throw new IllegalStateException("Ledger hash mismatch");

            if (!entry.getPreviousHash().equals(previousHash))
                throw new IllegalStateException("Ledger chain broken");

            previousHash = entry.getCurrentHash();
        }
    }
}