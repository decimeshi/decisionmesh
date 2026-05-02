package com.decisionmesh.application.lock;


import io.smallrye.mutiny.Uni;
import java.time.Duration;

/**
 * Distributed lock manager for intent partition coordination.
 *
 * <p>Guarantees:
 * <ul>
 *   <li>At-most-once lock ownership (safety)</li>
 *   <li>Automatic expiry prevents deadlocks (liveness)</li>
 *   <li>Token-based ownership prevents accidental release by wrong holder</li>
 *   <li>Extension mechanism for long-running operations</li>
 * </ul>
 */
public interface LockManager {

    /**
     * Acquire a distributed lock for the given partition.
     *
     * @param partitionKey Partition identifier (e.g., "intent:tenant-123:uuid")
     * @param ttl          Lock time-to-live (auto-expires after this duration)
     * @return LockToken if acquired, or failure if lock already held
     */
    Uni<LockToken> acquire(String partitionKey, Duration ttl);

    /**
     * Acquire with exponential backoff retry.
     *
     * @param partitionKey  Partition identifier
     * @param ttl           Lock time-to-live
     * @param maxRetries    Maximum retry attempts
     * @param initialBackoff Initial backoff duration (doubles each retry)
     */
    Uni<LockToken> acquireWithRetry(String partitionKey, Duration ttl,
                                    int maxRetries, Duration initialBackoff);

    /**
     * Extend the TTL of an existing lock.
     * Used for long-running operations that need to keep the lock alive.
     *
     * @param token    The lock token proving ownership
     * @param extension Additional time to extend the lock
     * @return true if extended, false if lock no longer exists or token mismatch
     */
    Uni<Boolean> extend(LockToken token, Duration extension);

    /**
     * Release a lock.
     * ONLY releases if the provided token matches the current lock holder.
     *
     * @param token The lock token proving ownership
     * @return true if released, false if lock no longer exists or token mismatch
     */
    Uni<Boolean> release(LockToken token);

    /**
     * Check if a lock is currently held (non-blocking).
     *
     * @param partitionKey Partition identifier
     * @return true if locked, false if available
     */
    Uni<Boolean> isLocked(String partitionKey);

    /**
     * Force-release a lock (admin/cleanup only).
     * Does NOT validate token. Use only for emergency cleanup.
     *
     * @param partitionKey Partition identifier
     * @return true if deleted, false if not found
     */
    Uni<Boolean> forceRelease(String partitionKey);
    Uni<Boolean> exists(String partitionKey);
}