package com.decisionmesh.application.lock;

import java.time.Instant;
import java.util.Objects;

/**
 * Proof of lock ownership.
 * Must be presented on release/extend operations to prevent accidental deletion
 * of locks held by other threads/processes.
 */
public record LockToken(
        String partitionKey,
        String token,        // UUID proving ownership
        Instant acquiredAt,
        Instant expiresAt
) {
    public LockToken {
        Objects.requireNonNull(partitionKey, "partitionKey");
        Objects.requireNonNull(token,        "token");
        Objects.requireNonNull(acquiredAt,   "acquiredAt");
        Objects.requireNonNull(expiresAt,    "expiresAt");
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public long remainingMillis() {
        long remaining = expiresAt.toEpochMilli() - Instant.now().toEpochMilli();
        return Math.max(0, remaining);
    }
}