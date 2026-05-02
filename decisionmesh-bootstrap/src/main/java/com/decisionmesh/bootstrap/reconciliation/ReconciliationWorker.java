package com.decisionmesh.bootstrap.reconciliation;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class ReconciliationWorker {

    public void reconcile(String intentId) {
        // Rebuild state, verify invariants, re-trigger orchestration if needed
    }
}