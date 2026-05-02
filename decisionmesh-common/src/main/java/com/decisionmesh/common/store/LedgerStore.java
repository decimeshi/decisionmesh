package com.decisionmesh.common.store;

import io.smallrye.mutiny.Uni;
import java.util.List;
import java.util.UUID;
import com.decisionmesh.common.ledger.LedgerEntry;

public interface LedgerStore {

    Uni<Void> append(LedgerEntry entry);

    Uni<List<LedgerEntry>> load(UUID intentId);
}