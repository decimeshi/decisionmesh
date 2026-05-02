package com.decisionmesh.bootstrap.service;

import com.decisionmesh.persistence.entity.AuditLogEntity;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Writes entries to the {@code audit_log} table.
 *
 * <h3>Session strategy</h3>
 * All callers (IntentResource, ApiKeyResource, PolicyResource) are annotated
 * with {@code @WithTransaction}, so an open Hibernate Reactive session already
 * exists in the Vert.x duplicated context when {@code log()} is called.
 * {@code entity.persist()} uses that session via Panache — no new session or
 * transaction is needed.
 *
 * Audit writes are chained into the caller's reactive pipeline via
 * {@code .flatMap()} so they commit with the same transaction as the main
 * operation. If the main operation rolls back, the audit row is not written —
 * which is the correct behaviour (no orphaned audit events).
 *
 * <h3>Fire-and-forget was abandoned</h3>
 * Three attempts at fire-and-forget all failed due to Hibernate Reactive's
 * session-per-Vert.x-context model:
 * <ol>
 *   <li>{@code Panache.withTransaction()} — "No current Mutiny.Session found"
 *       (outer transaction's context already torn down)</li>
 *   <li>{@code sf.withTransaction()} — "Session is closed"
 *       (finds the closed outer session in context local map)</li>
 *   <li>{@code emitOn(workerPool) + sf.withTransaction()} — "must be invoked
 *       from Vert.x EventLoop thread"</li>
 * </ol>
 */
@ApplicationScoped
public class AuditService {

    // ── Action constants ──────────────────────────────────────────────────────

    public static final String ACTION_INTENT_SUBMITTED  = "INTENT_SUBMITTED";
    public static final String ACTION_INTENT_DELETED    = "INTENT_DELETED";
    public static final String ACTION_API_KEY_CREATED   = "API_KEY_CREATED";
    public static final String ACTION_API_KEY_REVOKED   = "API_KEY_REVOKED";
    public static final String ACTION_POLICY_CREATED    = "POLICY_CREATED";
    public static final String ACTION_POLICY_UPDATED    = "POLICY_UPDATED";
    public static final String ACTION_POLICY_DELETED    = "POLICY_DELETED";
    public static final String ACTION_ADAPTER_CREATED   = "ADAPTER_CREATED";
    public static final String ACTION_ADAPTER_UPDATED   = "ADAPTER_UPDATED";
    public static final String ACTION_ADAPTER_TOGGLED   = "ADAPTER_TOGGLED";

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns a {@code Uni<Void>} that writes one audit row using the caller's
     * already-open Panache session.
     *
     * <p>Chain this into the caller's reactive pipeline:
     * <pre>{@code
     *   return mainOperation()
     *       .flatMap(result -> auditService.log(tenantId, userId, ACTION, "TYPE", id, null)
     *           .replaceWith(result));
     * }</pre>
     *
     * Must be called from within a {@code @WithSession} or {@code @WithTransaction}
     * scope — Panache's {@code entity.persist()} resolves the session from the
     * current Vert.x context.
     */
    public Uni<Void> log(UUID tenantId,
                         String userId,
                         String action,
                         String entityType,
                         UUID entityId,
                         String detail) {
        return persist(tenantId, userId, action, entityType, entityId,
                null, null, "SUCCESS", detail);
    }

    /** Full variant with outcome and resource info. */
    public Uni<Void> log(UUID tenantId,
                         String userId,
                         String action,
                         String entityType,
                         UUID entityId,
                         String resourceType,
                         String resourceId,
                         String outcome,
                         String detail) {
        return persist(tenantId, userId, action, entityType, entityId,
                resourceType, resourceId, outcome, detail);
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private Uni<Void> persist(UUID tenantId,
                              String userId,
                              String action,
                              String entityType,
                              UUID entityId,
                              String resourceType,
                              String resourceId,
                              String outcome,
                              String detail) {
        AuditLogEntity e = new AuditLogEntity();
        e.tenantId      = tenantId;
        e.userId        = userId;
        e.action        = action;
        e.entityType    = entityType;
        e.entityId      = entityId;
        e.resourceType  = resourceType;
        e.resourceId    = resourceId;
        e.outcome       = outcome != null ? outcome : "SUCCESS";
        e.detail        = detail;
        e.occurredAt    = OffsetDateTime.now();

        return e.<AuditLogEntity>persist()
                .invoke(saved -> Log.debugf("[Audit] %s entity=%s id=%s",
                        action, entityType, entityId))
                .onFailure().invoke(ex ->
                        Log.warnf(ex, "[Audit] Failed to write: %s", action))
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }
}