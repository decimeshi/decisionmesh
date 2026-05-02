package com.decisionmesh.cqrs.replay;

import com.decisionmesh.common.dto.ReplayResponse;
import com.decisionmesh.common.ledger.LedgerEntry;
import com.decisionmesh.common.store.LedgerStore;
import com.decisionmesh.governance.snapshot.PolicySnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class ReplayService {

    @Inject
    LedgerStore ledgerStore;   // load() → Uni<List<LedgerEntry>>  ✓

    @Inject
    ObjectMapper mapper;

    public Uni<List<ReplayResponse>> replay(UUID intentId) {
        Uni<List<LedgerEntry>> entriesUni = ledgerStore.load(intentId);
        return entriesUni.map(this::mapEntries);
    }

    private List<ReplayResponse> mapEntries(List<LedgerEntry> entries) {
        return entries.stream()
                .map(this::toResponse)
                .toList();
    }

    private ReplayResponse toResponse(LedgerEntry entry) {
        try {
            PolicySnapshot snapshot = mapper.readValue(
                    entry.getPolicySnapshotJson(),
                    PolicySnapshot.class
            );

            String decision = snapshot.getDecision().isAllowed() ? "ALLOW" : "DENY";
            String reason   = snapshot.getDecision().getReason();
            String plan     = snapshot.getPlan() != null
                    ? snapshot.getPlan().name()
                    : "UNKNOWN";

            return new ReplayResponse(entry.getTimestamp(), decision, reason, plan);

        } catch (Exception e) {
            Log.warnf("Snapshot parse failed for entry at %s: %s",
                    entry.getTimestamp(), e.getMessage());

            return new ReplayResponse(
                    entry.getTimestamp(),
                    "ERROR",
                    "Failed to parse snapshot: " + e.getMessage(),
                    "UNKNOWN"
            );
        }
    }
}