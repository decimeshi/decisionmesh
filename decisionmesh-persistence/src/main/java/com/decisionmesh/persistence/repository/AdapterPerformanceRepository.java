package com.decisionmesh.persistence.repository;

import com.decisionmesh.persistence.entity.AdapterEntity;
import com.decisionmesh.persistence.entity.AdapterPerformanceEntity;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Persists adapter execution feedback into adapter_performance_profiles
 * using EMA (Exponential Moving Average) updates.
 *
 * Also appends an immutable snapshot row to adapter_profile_versions
 * after each update (for learning history and audit).
 *
 * Schema used:
 *
 *   adapter_performance_profiles:
 *     UPSERT on (adapter_id, tenant_id) — one row per adapter per tenant
 *     Updates: ema_cost, ema_latency_ms, ema_success_rate, ema_risk_score,
 *              composite_score, execution_count, success_count/failure_count,
 *              cold_start (cleared after cold_start_threshold executions),
 *              is_degraded / degraded_since / degraded_reason, version (OCC)
 *
 *   adapter_profile_versions:
 *     INSERT — immutable history snapshot after each update
 *     trigger_execution_id links snapshot to the execution that caused the change
 *
 * EMA formula: new_ema = alpha * new_value + (1 - alpha) * old_ema
 * Alpha = 0.1 (matches AdapterStats in-memory calculation)
 */
@ApplicationScoped
public class AdapterPerformanceRepository {

    private static final double EMA_ALPHA                = 0.1;
    private static final double DEGRADED_THRESHOLD       = 0.60; // success rate below this → degraded
    private static final double RECOVERY_THRESHOLD       = 0.75; // success rate above this → recovered
    private static final int    COLD_START_THRESHOLD_DEF = 10;

    // ── UPSERT profile ─────────────────────────────────────────────────────
    // Uses ON CONFLICT to handle both INSERT (new adapter) and UPDATE (existing).
    // composite_score recomputed inline:
    //   0.40 * success_rate + 0.25 * latency_score + 0.20 * cost_score + 0.15 * (1-risk)
    private static final String UPSERT_SQL = """
            INSERT INTO adapter_performance_profiles (
                id, adapter_id, tenant_id,
                ema_cost, ema_latency_ms, ema_success_rate, ema_risk_score, ema_confidence,
                composite_score,
                execution_count, success_count, failure_count,
                cold_start, cold_start_threshold,
                is_degraded, degraded_since, degraded_reason,
                last_executed_at, version
            ) VALUES (
                gen_random_uuid(), ?, ?,
                ?, ?, ?, ?, 0.0,
                ?,
                1, ?, ?,
                true, ?,
                ?, ?, ?,
                now(), 0
            )
            ON CONFLICT (adapter_id, tenant_id) DO UPDATE SET
                ema_cost            = EXCLUDED.ema_cost,
                ema_latency_ms      = EXCLUDED.ema_latency_ms,
                ema_success_rate    = EXCLUDED.ema_success_rate,
                ema_risk_score      = EXCLUDED.ema_risk_score,
                composite_score     = EXCLUDED.composite_score,
                execution_count     = adapter_performance_profiles.execution_count + 1,
                success_count       = adapter_performance_profiles.success_count   + EXCLUDED.success_count,
                failure_count       = adapter_performance_profiles.failure_count   + EXCLUDED.failure_count,
                cold_start          = CASE
                    WHEN (adapter_performance_profiles.execution_count + 1) >= adapter_performance_profiles.cold_start_threshold
                    THEN false
                    ELSE adapter_performance_profiles.cold_start
                END,
                is_degraded         = EXCLUDED.is_degraded,
                degraded_since      = CASE
                    WHEN EXCLUDED.is_degraded = true AND adapter_performance_profiles.is_degraded = false
                    THEN now()
                    WHEN EXCLUDED.is_degraded = false THEN NULL
                    ELSE adapter_performance_profiles.degraded_since
                END,
                degraded_reason     = EXCLUDED.degraded_reason,
                last_executed_at    = now(),
                version             = adapter_performance_profiles.version + 1,
                updated_at          = now()
            RETURNING id, version, execution_count, is_degraded
            """;

    // Load current EMA values for computing new EMA
    private static final String LOAD_SQL = """
            SELECT ema_cost, ema_latency_ms, ema_success_rate, ema_risk_score,
                   execution_count, cold_start, is_degraded, version, id
            FROM   adapter_performance_profiles
            WHERE  adapter_id = ? AND tenant_id = ?
            FOR UPDATE
            """;

    // Snapshot into profile versions
    private static final String VERSION_SQL = """
            INSERT INTO adapter_profile_versions (
                id, profile_id, adapter_id, tenant_id,
                profile_version, snapshot,
                trigger_execution_id, trigger_intent_id,
                update_cause, created_at
            ) VALUES (
                gen_random_uuid(), ?, ?, ?,
                ?, ?::jsonb,
                ?, ?,
                ?, now()
            )
            """;

    @Inject
    DataSource dataSource;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Record a successful execution and update EMA metrics.
     *
     * @param tenantId      for RLS and profile scoping
     * @param adapterId     FK to adapters.id
     * @param provider      used for logging
     * @param model         used for logging
     * @param region        used for logging
     * @param latencyMs     measured wall-clock time of the adapter call
     * @param costUsd       actual metered cost
     * @param riskScore     0..1 risk score from policy evaluation (0 = no risk)
     * @param executionId   FK to execution_records.id — links profile version to execution
     * @param intentId      FK to intents.id
     */
    public Uni<Void> recordSuccess(UUID tenantId, UUID adapterId,
                                   String provider, String model, String region,
                                   long latencyMs, double costUsd, double riskScore,
                                   UUID executionId, UUID intentId) {
        return Uni.createFrom().item(() -> {
                    upsertProfile(tenantId, adapterId, latencyMs, costUsd, riskScore,
                            true, executionId, intentId);
                    return null;
                }).replaceWithVoid()
                .onFailure().invoke(ex ->
                        Log.warnf(ex, "Failed to record success stats: adapter=%s", adapterId));
    }

    /**
     * Record a failed execution and penalise the success rate EMA.
     */
    public Uni<Void> recordFailure(UUID tenantId, UUID adapterId,
                                   String provider, String model, String region,
                                   long latencyMs, double costUsd,
                                   UUID executionId, UUID intentId) {
        return Uni.createFrom().item(() -> {
                    upsertProfile(tenantId, adapterId, latencyMs, costUsd, 0.0,
                            false, executionId, intentId);
                    return null;
                }).replaceWithVoid()
                .onFailure().invoke(ex ->
                        Log.warnf(ex, "Failed to record failure stats: adapter=%s", adapterId));
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private void upsertProfile(UUID tenantId, UUID adapterId,
                               long latencyMs, double costUsd, double riskScore,
                               boolean isSuccess, UUID executionId, UUID intentId) {

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);

            // Set RLS — SET LOCAL does not accept JDBC parameters ($1/?).
            // UUID.toString() is safe to inline: fixed format, no SQL injection risk.
            try (java.sql.Statement rls = conn.createStatement()) {
                rls.execute("SET LOCAL app.current_tenant_id = '" + tenantId + "'");
            }

            // Load current values for EMA computation
            CurrentProfile current = loadCurrent(conn, adapterId, tenantId);

            // Compute new EMA values
            double newEmaSuccess  = ema(current.emaSuccessRate, isSuccess ? 1.0 : 0.0);
            double newEmaLatency  = ema(current.emaLatencyMs,   latencyMs);
            double newEmaCost     = ema(current.emaCost,        costUsd);
            double newEmaRisk     = ema(current.emaRiskScore,   riskScore);
            double newComposite   = computeCompositeScore(newEmaSuccess, newEmaLatency, newEmaCost, newEmaRisk);

            // Degradation logic
            boolean nowDegraded  = newEmaSuccess < DEGRADED_THRESHOLD;
            boolean wasRecovered = current.isDegraded && newEmaSuccess > RECOVERY_THRESHOLD;
            boolean isDegraded   = nowDegraded && !wasRecovered;
            String  degradedReason = isDegraded
                    ? String.format("EMA success rate %.2f%% below threshold %.0f%%",
                    newEmaSuccess * 100, DEGRADED_THRESHOLD * 100)
                    : null;

            String updateCause = wasRecovered   ? "DEGRADATION_CLEARED"
                    : (isDegraded && !current.isDegraded) ? "DEGRADATION_TRIGGERED"
                    : "EXECUTION_FEEDBACK";

            // UPSERT profile
            UUID    profileId    = null;
            int     newVersion   = 0;
            long    newExecCount = current.executionCount + 1;

            try (PreparedStatement ps = conn.prepareStatement(UPSERT_SQL)) {
                ps.setObject(1, adapterId);
                ps.setObject(2, tenantId);
                ps.setDouble(3, newEmaCost);
                ps.setDouble(4, newEmaLatency);
                ps.setDouble(5, newEmaSuccess);
                ps.setDouble(6, newEmaRisk);
                ps.setDouble(7, newComposite);
                ps.setInt(8, isSuccess ? 1 : 0);       // success_count increment
                ps.setInt(9, isSuccess ? 0 : 1);       // failure_count increment
                ps.setInt(10, COLD_START_THRESHOLD_DEF);
                ps.setBoolean(11, isDegraded);
                if (isDegraded && !current.isDegraded) {
                    ps.setTimestamp(12, Timestamp.from(Instant.now()));
                } else {
                    ps.setTimestamp(12, null);
                }
                ps.setString(13, degradedReason);

                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        profileId  = UUID.fromString(rs.getString("id"));
                        newVersion = rs.getInt("version");
                    }
                }
            }

            // Insert immutable version snapshot
            if (profileId != null) {
                insertVersionSnapshot(conn, profileId, adapterId, tenantId, newVersion,
                        newEmaSuccess, newEmaLatency, newEmaCost, newEmaRisk,
                        newComposite, newExecCount, isDegraded,
                        executionId, intentId, updateCause);
            }

            conn.commit();

            if (isDegraded && !current.isDegraded) {
                Log.warnf("Adapter degraded: adapterId=%s, emaSuccessRate=%.2f", adapterId, newEmaSuccess);
            } else if (wasRecovered) {
                Log.infof("Adapter recovered: adapterId=%s, emaSuccessRate=%.2f", adapterId, newEmaSuccess);
            }

        } catch (Exception ex) {
            Log.errorf(ex, "Failed to upsert adapter profile: adapter=%s", adapterId);
        }
    }

    private CurrentProfile loadCurrent(Connection conn, UUID adapterId, UUID tenantId)
            throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(LOAD_SQL)) {
            ps.setObject(1, adapterId);
            ps.setObject(2, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new CurrentProfile(
                            rs.getDouble("ema_success_rate"),
                            rs.getDouble("ema_latency_ms"),
                            rs.getDouble("ema_cost"),
                            rs.getDouble("ema_risk_score"),
                            rs.getLong("execution_count"),
                            rs.getBoolean("is_degraded"),
                            UUID.fromString(rs.getString("id"))
                    );
                }
            }
        }
        // New adapter — return cold-start priors
        return new CurrentProfile(0.8, 2000.0, 0.01, 0.1, 0L, false, null);
    }

    private void insertVersionSnapshot(Connection conn,
                                       UUID profileId, UUID adapterId, UUID tenantId,
                                       int version, double successRate, double latencyMs,
                                       double cost, double riskScore, double composite,
                                       long execCount, boolean isDegraded,
                                       UUID executionId, UUID intentId,
                                       String updateCause) throws Exception {

        JsonObject snapshot = new JsonObject()
                .put("ema_success_rate", successRate)
                .put("ema_latency_ms",   latencyMs)
                .put("ema_cost",         cost)
                .put("ema_risk_score",   riskScore)
                .put("composite_score",  composite)
                .put("execution_count",  execCount)
                .put("is_degraded",      isDegraded);

        try (PreparedStatement ps = conn.prepareStatement(VERSION_SQL)) {
            ps.setObject(1, profileId);
            ps.setObject(2, adapterId);
            ps.setObject(3, tenantId);
            ps.setInt(4, version);
            ps.setString(5, snapshot.encode());
            ps.setObject(6, executionId);
            ps.setObject(7, intentId);
            ps.setString(8, updateCause);
            ps.execute();
        }
    }

    // ── Math ─────────────────────────────────────────────────────────────────

    private double ema(double current, double newValue) {
        return EMA_ALPHA * newValue + (1.0 - EMA_ALPHA) * current;
    }

    /**
     * Composite score formula mirrors AdapterStats.compositeScore():
     *   40% success rate + 25% latency (inverted) + 20% cost (inverted) + 15% risk (inverted)
     */
    private double computeCompositeScore(double successRate, double latencyMs,
                                         double costPerCall, double riskScore) {
        double latencyScore = 1.0 - Math.min(latencyMs  / 10_000.0, 1.0);
        double costScore    = 1.0 - Math.min(costPerCall / 0.10,     1.0);
        double riskPenalty  = 1.0 - Math.min(riskScore,             1.0);

        return 0.40 * successRate
                + 0.25 * latencyScore
                + 0.20 * costScore
                + 0.15 * riskPenalty;
    }

    public Uni<AdapterPerformanceEntity> find(UUID tenantId, UUID adapterId) {
        return AdapterPerformanceEntity.findByAdapterAndTenant(tenantId, adapterId);
    }

    public Uni<Void> persist(AdapterPerformanceEntity p) {
        return AdapterPerformanceEntity.persist(p).replaceWithVoid();
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    private record CurrentProfile(
            double emaSuccessRate,
            double emaLatencyMs,
            double emaCost,
            double emaRiskScore,
            long   executionCount,
            boolean isDegraded,
            UUID   profileId
    ) {}
}