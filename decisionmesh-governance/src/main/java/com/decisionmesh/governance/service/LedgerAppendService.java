package com.decisionmesh.governance.service;

import com.decisionmesh.common.ledger.LedgerEntry;
import com.decisionmesh.common.store.LedgerStore;
import com.decisionmesh.governance.snapshot.PolicySnapshot;
import com.decisionmesh.governance.validator.LedgerValidator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.UUID;

/**
 * Appends a governance decision to the immutable ledger during intent execution.
 * Called by the orchestrator/policy engine after each governance decision.
 * Builds the hash chain automatically by loading the last entry's hash.
 *
 * FIX: Removed @Inject LedgerAppendService — circular self-injection causes
 *      Quarkus CDI deployment to fail with AmbiguousResolutionException.
 */
@ApplicationScoped
public class LedgerAppendService {

    @Inject LedgerStore     ledgerStore;
    @Inject LedgerValidator ledgerValidator;
    @Inject ObjectMapper    mapper;

    // ── Append a governance decision ──────────────────────────────────────────

    public Uni<Void> appendDecision(UUID intentId,
                                    String tenantId,
                                    String eventType,
                                    PolicySnapshot policySnapshot,
                                    String budgetSnapshotJson,
                                    String slaSnapshotJson) {

        return ledgerStore.load(intentId)
                .flatMap(existing -> {
                    long   nextVersion = existing.size();
                    String prevHash    = existing.isEmpty()
                            ? null
                            : existing.get(existing.size() - 1).getCurrentHash();

                    String policyJson = serializeSnapshot(policySnapshot);

                    // Build draft with placeholder hash to compute deterministic payload
                    LedgerEntry draft = new LedgerEntry(
                            UUID.randomUUID(),
                            intentId,
                            tenantId,
                            nextVersion,
                            UUID.randomUUID(),
                            eventType,
                            policyJson,
                            budgetSnapshotJson,
                            slaSnapshotJson,
                            prevHash,
                            "PENDING",
                            Instant.now()
                    );

                    // Compute real SHA-256 hash
                    String currentHash = ledgerValidator.computeHash(draft);

                    // Build final immutable entry with real hash
                    LedgerEntry entry = new LedgerEntry(
                            draft.getLedgerId(),
                            intentId,
                            tenantId,
                            nextVersion,
                            draft.getEventId(),
                            eventType,
                            policyJson,
                            budgetSnapshotJson,
                            slaSnapshotJson,
                            prevHash,
                            currentHash,
                            draft.getTimestamp()
                    );

                    Log.debugf("[Ledger] Appending: intent=%s v=%d hash=%s...",
                            intentId, nextVersion, currentHash.substring(0, 8));

                    return ledgerStore.append(entry);
                })
                .onFailure().invoke(ex ->
                        Log.errorf("[Ledger] Failed to append for intent %s: %s",
                                intentId, ex.getMessage()))
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }

    // ── Validate full chain ───────────────────────────────────────────────────

    public Uni<Boolean> validateChain(UUID intentId) {
        return ledgerStore.load(intentId)
                .map(entries -> {
                    if (entries.isEmpty()) return true;
                    try {
                        ledgerValidator.validateChain(entries, null);
                        return true;
                    } catch (LedgerValidator.LedgerIntegrityException e) {
                        Log.warnf("[Ledger] Chain invalid for intent %s: %s",
                                intentId, e.getMessage());
                        return false;
                    }
                });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String serializeSnapshot(PolicySnapshot snapshot) {
        if (snapshot == null) return null;
        try {
            return mapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            Log.warnf("[Ledger] Failed to serialize policy snapshot: %s", e.getMessage());
            return null;
        }
    }
}
