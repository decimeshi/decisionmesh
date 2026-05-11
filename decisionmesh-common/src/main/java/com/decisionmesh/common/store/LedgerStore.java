package com.decisionmesh.common.store;

import com.decisionmesh.common.ledger.LedgerEntry;
import io.smallrye.mutiny.Uni;
import java.util.List;
import java.util.UUID;

public interface LedgerStore {

    Uni<Void> append(LedgerEntry entry);

    Uni<List<LedgerEntry>> load(UUID intentId);
}