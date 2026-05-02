package com.decisionmesh.intelligence.store;

import com.decisionmesh.intelligence.ledger.ExplorationLedgerEntry;
import io.smallrye.mutiny.Uni;

public interface ExplorationLedgerStore {

    Uni<Void> append(ExplorationLedgerEntry entry);

}