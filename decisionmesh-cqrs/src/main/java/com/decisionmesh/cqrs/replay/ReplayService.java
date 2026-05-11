package com.decisionmesh.cqrs.replay;

import com.decisionmesh.common.dto.ReplayResponse;
import com.decisionmesh.common.ledger.LedgerEntry;
import com.decisionmesh.common.store.LedgerStore;
import com.decisionmesh.governance.snapshot.PolicySnapshot;
import com.decisionmesh.governance.validator.LedgerValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Loads the governance ledger for an intent and reconstructs
 * the full decision history with integrity validation.
 *
 * Each entry in the result represents one governance checkpoint:
 *   - Which policy was evaluated
 *   - What decision was made (ALLOW/DENY)
 *   - Whether the cryptographic chain is intact
 */
@ApplicationScoped
public class ReplayService {

    @Inject LedgerStore     ledgerStore;
    @Inject LedgerValidator ledgerValidator;
    @Inject ObjectMapper    mapper;

    // ── Replay ────────────────────────────────────────────────────────────────

    public Uni<List<ReplayResponse>> replay(UUID intentId) {
        return ledgerStore.load(intentId)
                .map((java.util.function.Function<? super List<com.decisionmesh.common.ledger.LedgerEntry>, ? extends List<ReplayResponse>>) this::buildReplayWithValidation);
    }

    // ── Tenant-scoped replay (preferred — prevents cross-tenant access) ───────

    public Uni<List<ReplayResponse>> replayForTenant(UUID tenantId, UUID intentId) {
        // Load all entries and filter by tenant for safety
        return ledgerStore.load(intentId)
                .map(entries -> entries.stream()
                        .filter(e -> tenantId.toString().equals(e.getTenantId()))
                        .toList())
                .map(this::buildReplayWithValidation);
    }

    // ── Build replay response with per-entry hash validation ──────────────────

    private List<ReplayResponse> buildReplayWithValidation(List<LedgerEntry> entries) {
        if (entries.isEmpty()) {
            Log.debug("[ReplayService] No ledger entries found");
            return List.of();
        }

        List<ReplayResponse> responses = new ArrayList<>(entries.size());
        String expectedPrevHash = null;

        for (int i = 0; i < entries.size(); i++) {
            LedgerEntry entry = entries.get(i);

            // Validate this entry's hash
            boolean hashValid = validateEntryHash(entry, expectedPrevHash, i);
            expectedPrevHash = entry.getCurrentHash();

            // Parse policy snapshot
            PolicySnapshot snapshot = parseSnapshot(entry.getPolicySnapshotJson());

            String decision;
            String reason;
            String plan;
            String policyVersion    = null;
            String enforcementMode  = null;

            if (snapshot != null) {
                decision        = snapshot.getDecision().isAllowed() ? "ALLOW" : "DENY";
                reason          = snapshot.getDecision().getReason();
                plan            = snapshot.getPlan() != null ? snapshot.getPlan().name() : "UNKNOWN";
                policyVersion   = snapshot.getPolicyVersion();
                enforcementMode = snapshot.getEnforcementMode();
            } else if (entry.getPolicySnapshotJson() != null) {
                decision = "ERROR";
                reason   = "Failed to parse policy snapshot";
                plan     = "UNKNOWN";
            } else {
                decision = "NO_POLICY";
                reason   = "No policy snapshot recorded";
                plan     = "UNKNOWN";
            }

            responses.add(new ReplayResponse(
                    entry.getTimestamp(),
                    decision,
                    reason,
                    plan,
                    entry.getAggregateVersion(),
                    entry.getCurrentHash(),
                    entry.getPreviousHash(),
                    entry.getEventType(),
                    policyVersion,
                    enforcementMode,
                    hashValid
            ));
        }

        long invalidCount = responses.stream().filter(r -> !r.chainValid).count();
        if (invalidCount > 0) {
            Log.warnf("[ReplayService] %d/%d entries have invalid hashes for this intent",
                    invalidCount, entries.size());
        } else {
            Log.debugf("[ReplayService] Replay complete — %d entries, chain intact",
                    entries.size());
        }

        return responses;
    }

    // ── Per-entry hash validation (non-throwing) ──────────────────────────────

    private boolean validateEntryHash(LedgerEntry entry, String expectedPrevHash, int index) {
        try {
            // Validate hash of this entry's payload
            String computed = ledgerValidator.computeHash(entry);
            if (!computed.equals(entry.getCurrentHash())) {
                Log.warnf("[ReplayService] Hash mismatch at entry %d (version %d)",
                        index, entry.getAggregateVersion());
                return false;
            }

            // Validate chain linkage
            if (index > 0 && expectedPrevHash != null) {
                if (!expectedPrevHash.equals(entry.getPreviousHash())) {
                    Log.warnf("[ReplayService] Chain break at entry %d (version %d)",
                            index, entry.getAggregateVersion());
                    return false;
                }
            }

            return true;
        } catch (Exception e) {
            Log.warnf("[ReplayService] Failed to validate entry %d: %s",
                    index, e.getMessage());
            return false;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private PolicySnapshot parseSnapshot(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return mapper.readValue(json, PolicySnapshot.class);
        } catch (Exception e) {
            Log.warnf("[ReplayService] Failed to parse policy snapshot: %s", e.getMessage());
            return null;
        }
    }
}
