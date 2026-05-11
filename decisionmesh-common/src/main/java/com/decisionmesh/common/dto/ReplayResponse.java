package com.decisionmesh.common.dto;

import java.time.Instant;

/**
 * Response DTO for a single ledger entry in a governance replay.
 *
 * Each entry represents one governance decision point during intent execution:
 *   - What policy was in effect
 *   - What decision was made (ALLOW/DENY)
 *   - Why the decision was made
 *   - Chain integrity proof (hash)
 */
public class ReplayResponse {

    // ── Core fields ───────────────────────────────────────────────────────────

    public final Instant timestamp;
    public final String  decision;      // ALLOW | DENY | ERROR
    public final String  reason;
    public final String  plan;

    // ── Chain integrity ───────────────────────────────────────────────────────

    public final long   aggregateVersion;  // sequence number in the chain
    public final String currentHash;       // SHA-256 of this entry's payload
    public final String previousHash;      // SHA-256 of prior entry (null = genesis)
    public final String eventType;

    // ── Policy context ────────────────────────────────────────────────────────

    public final String policyVersion;
    public final String enforcementMode;

    // ── Chain validity ────────────────────────────────────────────────────────

    public final boolean chainValid;       // true if this entry's hash is intact

    // ── Full constructor ──────────────────────────────────────────────────────

    public ReplayResponse(Instant timestamp,
                          String  decision,
                          String  reason,
                          String  plan,
                          long    aggregateVersion,
                          String  currentHash,
                          String  previousHash,
                          String  eventType,
                          String  policyVersion,
                          String  enforcementMode,
                          boolean chainValid) {
        this.timestamp        = timestamp;
        this.decision         = decision;
        this.reason           = reason;
        this.plan             = plan;
        this.aggregateVersion = aggregateVersion;
        this.currentHash      = currentHash;
        this.previousHash     = previousHash;
        this.eventType        = eventType;
        this.policyVersion    = policyVersion;
        this.enforcementMode  = enforcementMode;
        this.chainValid       = chainValid;
    }

    // ── Backward-compatible simple constructor (used by existing ReplayService) ──

    public ReplayResponse(Instant timestamp, String decision, String reason, String plan) {
        this(timestamp, decision, reason, plan,
             0L, null, null, null, null, null, true);
    }
}
