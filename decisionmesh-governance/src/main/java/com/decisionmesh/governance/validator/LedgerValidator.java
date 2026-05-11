package com.decisionmesh.governance.validator;

import com.decisionmesh.common.ledger.LedgerEntry;
import com.decisionmesh.governance.snapshot.PolicySnapshot;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Validates the cryptographic hash chain of a governance ledger.
 *
 * Each ledger entry contains:
 *   - currentHash  = SHA-256(deterministicPayload)
 *   - previousHash = currentHash of the previous entry (null for first)
 *
 * Chain is valid if:
 *   1. Each entry's currentHash matches SHA-256 of its payload
 *   2. Each entry's previousHash matches the previous entry's currentHash
 *   3. The first entry's previousHash is null or "GENESIS"
 */
@ApplicationScoped
public class LedgerValidator {

    private static final String GENESIS = "GENESIS";

    // ── Chain validation ──────────────────────────────────────────────────────

    public void validateChain(List<LedgerEntry> entries, PolicySnapshot snapshot) {
        if (entries == null || entries.isEmpty()) {
            Log.warn("[LedgerValidator] Empty ledger — nothing to validate");
            return;
        }

        String expectedPrevious = null;

        for (int i = 0; i < entries.size(); i++) {
            LedgerEntry entry = entries.get(i);

            // 1. Verify current hash
            String computed = computeHash(entry);
            if (!computed.equals(entry.getCurrentHash())) {
                throw new LedgerIntegrityException(String.format(
                        "Hash mismatch at entry %d (version %d): expected %s got %s",
                        i, entry.getAggregateVersion(), computed, entry.getCurrentHash()
                ));
            }

            // 2. Verify chain linkage
            if (i == 0) {
                // First entry — previousHash must be null or GENESIS
                String prev = entry.getPreviousHash();
                if (prev != null && !prev.isBlank() && !GENESIS.equals(prev)) {
                    throw new LedgerIntegrityException(
                            "First ledger entry has unexpected previousHash: " + prev);
                }
            } else {
                // Subsequent entries — previousHash must match prior currentHash
                if (!expectedPrevious.equals(entry.getPreviousHash())) {
                    throw new LedgerIntegrityException(String.format(
                            "Chain broken at entry %d (version %d): previousHash mismatch",
                            i, entry.getAggregateVersion()
                    ));
                }
            }

            expectedPrevious = entry.getCurrentHash();
        }

        Log.debugf("[LedgerValidator] Chain valid — %d entries verified", entries.size());
    }

    // ── Single entry hash ─────────────────────────────────────────────────────

    public String computeHash(LedgerEntry entry) {
        try {
            String payload = entry.computeDeterministicPayload();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    // ── Exception ─────────────────────────────────────────────────────────────

    public static class LedgerIntegrityException extends RuntimeException {
        public LedgerIntegrityException(String message) {
            super(message);
        }
    }
}
