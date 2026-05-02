package com.decisionmesh.llm.persistence;

import com.decisionmesh.application.port.ExecutionRecordQueryPort;
import com.decisionmesh.domain.execution.ExecutionRecord;
import com.decisionmesh.domain.intent.Intent;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Writes execution results to execution_records and spend_records.
 * Also provides read queries used by DriftEvaluatorService,
 * ExecutionResource, and DriftResource.
 *
 * ExecutionRow field order (19 fields — must match ExecutionRecordQueryPort.ExecutionRow):
 *   1  id                   UUID
 *   2  intentId             UUID
 *   3  adapterId            UUID
 *   4  adapterName          String
 *   5  attemptNumber        int
 *   6  status               String
 *   7  costUsd              BigDecimal
 *   8  latencyMs            long
 *   9  promptTokens         int
 *   10 completionTokens     int
 *   11 totalTokens          int
 *   12 riskScore            BigDecimal
 *   13 qualityScore         BigDecimal
 *   14 hallucinationRisk    BigDecimal   ← nullable
 *   15 hallucinationDetected boolean
 *   16 failureReason        String       ← nullable
 *   17 responseText         String       ← nullable
 *   18 qualityReasoning     String       ← nullable
 *   19 executedAt           Instant
 */
@ApplicationScoped
public class ExecutionRecordRepository implements ExecutionRecordQueryPort {

    // =========================================================================
    // Write queries
    // =========================================================================

    private static final String INSERT_EXECUTION = """
            INSERT INTO execution_records (
                id, intent_id, plan_id, plan_step_id, tenant_id, adapter_id,
                status,
                latency_ms, prompt_tokens, completion_tokens, total_tokens,
                cost_usd, risk_score,
                failure_reason,
                response_text,
                metadata,
                executed_at
            ) VALUES (
                ?, ?, ?, ?, ?, ?,
                ?,
                ?, ?, ?, ?,
                ?, ?,
                ?,
                ?,
                '{}'::jsonb,
                now()
            )
            """;

    private static final String INSERT_SPEND = """
            INSERT INTO spend_records (
                id, intent_id, execution_id, tenant_id, adapter_id,
                amount_usd, token_count,
                budget_ceiling_usd,
                recorded_at
            ) VALUES (
                gen_random_uuid(), ?, ?, ?, ?,
                ?, 0,
                ?,
                now()
            )
            """;

    // =========================================================================
    // Read queries — DriftEvaluatorService
    // =========================================================================

    private static final String SELECT_RECENT_BY_ADAPTER = """
            SELECT
                id,
                status,
                cost_usd,
                latency_ms,
                quality_score,
                executed_at,
                response_text
            FROM execution_records
            WHERE adapter_id  = ?
              AND executed_at >= ?
            ORDER BY executed_at DESC
            LIMIT 500
            """;

    // =========================================================================
    // Read queries — ExecutionResource (UI list)
    // =========================================================================

    private static final String SELECT_LIST_BY_TENANT = """
            SELECT
                er.id,
                er.intent_id,
                er.adapter_id,
                a.name                 AS adapter_name,
                1                              AS attempt_number,
                er.status,
                er.cost_usd,
                er.latency_ms,
                er.prompt_tokens,
                er.completion_tokens,
                er.total_tokens,
                er.risk_score,
                er.quality_score,
                er.hallucination_risk,
                er.hallucination_detected,
                er.failure_reason,
                er.executed_at
            FROM execution_records er
            LEFT JOIN adapters a ON a.id = er.adapter_id
            WHERE er.tenant_id = ?
            """;

    // =========================================================================
    // Read queries — findByIntentId (IntentDetail page)
    // =========================================================================

    private static final String SELECT_BY_INTENT = """
            SELECT
                er.id,
                er.intent_id,
                er.adapter_id,
                a.name                 AS adapter_name,
                1                              AS attempt_number,
                er.status,
                er.cost_usd,
                er.latency_ms,
                er.prompt_tokens,
                er.completion_tokens,
                er.total_tokens,
                er.risk_score,
                er.quality_score,
                er.hallucination_risk,
                er.hallucination_detected,
                er.failure_reason,
                er.response_text,
                er.quality_reasoning,
                er.executed_at
            FROM execution_records er
            LEFT JOIN adapters a ON a.id = er.adapter_id
            WHERE er.tenant_id = ?
              AND er.intent_id  = ?
            ORDER BY er.executed_at ASC
            """;

    // =========================================================================
    // Read queries — DriftResource (dashboard summary per adapter)
    // =========================================================================

    private static final String SELECT_DRIFT_SUMMARY = """
            SELECT
                er.adapter_id::TEXT                              AS adapter_id,
                a.name                                           AS adapter_name,
                AVG(er.cost_usd)                                 AS avg_cost_usd,
                AVG(er.latency_ms)                               AS avg_latency_ms,
                AVG(er.quality_score)                            AS avg_quality_score,
                AVG(CASE WHEN er.status != 'SUCCESS' THEN 1.0 ELSE 0.0 END) AS failure_rate,
                COUNT(*)                                         AS execution_count,
                MAX(er.executed_at)                              AS last_executed_at
            FROM execution_records er
            LEFT JOIN adapters a ON a.id = er.adapter_id
            WHERE er.tenant_id  = ?
              AND er.executed_at >= ?
            GROUP BY er.adapter_id, a.name
            ORDER BY execution_count DESC
            """;

    // =========================================================================
    // Read queries — DriftResource (daily trend chart)
    // =========================================================================

    private static final String SELECT_DRIFT_TREND = """
            SELECT
                DATE_TRUNC('day', executed_at)   AS day,
                AVG(cost_usd)                    AS avg_cost,
                AVG(latency_ms)                  AS avg_latency,
                COUNT(*)                         AS execution_count
            FROM execution_records
            WHERE tenant_id  = ?
              AND executed_at >= ?
            GROUP BY DATE_TRUNC('day', executed_at)
            ORDER BY day ASC
            """;

    @Inject
    DataSource dataSource;

    // =========================================================================
    // Public write API
    // =========================================================================

    /**
     * Persists an execution record to the database.
     *
     * CRITICAL: persist() uses blocking JDBC (DataSource.getConnection()).
     * Uni.createFrom().item() by default runs on the caller's thread — which
     * in a Quarkus Reactive application is the Vert.x event loop.
     * Running blocking I/O on the event loop causes Vert.x to detect a
     * "blocked thread" violation, throw an exception, and the onFailure handler
     * logs a warning and discards it. The table stays permanently empty.
     *
     * Fix: runSubscriptionOn(Infrastructure.getDefaultWorkerPool()) moves the
     * lambda to a worker thread where blocking JDBC calls are safe.
     */
    /**
     * Persists an execution record synchronously.
     *
     * Called directly by ControlPlaneOrchestrator via
     * Infrastructure.getDefaultWorkerPool().execute(() -> persistBlocking(...))
     * — completely outside the Mutiny pipeline so it cannot affect
     * the thread context of subsequent Hibernate Reactive operators.
     *
     * Do NOT call this from a Mutiny flatMap/invoke — use the executor pattern
     * in the orchestrator to avoid HR000068 thread violations.
     */
    public void persistBlocking(ExecutionRecord record, Intent intent) {
        persist(record, intent);
    }

    public Uni<Void> save(ExecutionRecord record, Intent intent) {
        return Uni.createFrom().item(() -> {
                    persist(record, intent);
                    return null;
                })
                .runSubscriptionOn(io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool())
                .replaceWithVoid()
                .onFailure().invoke(ex ->
                        Log.errorf(ex, "Failed to persist execution record: intent=%s", intent.getId()));
    }

    /**
     * Updates quality scoring fields on an existing execution record.
     * Called by OutputQualityScorerService after EVALUATING phase completes.
     * These fields populate the Decision Output card in IntentDetail.jsx.
     *
     * Runs on worker pool — same JDBC blocking concern as save().
     */
    public Uni<Void> updateQualityScore(UUID executionId, UUID tenantId,
                                        java.math.BigDecimal qualityScore,
                                        java.math.BigDecimal hallucinationRisk,
                                        boolean hallucinationDetected,
                                        String qualityReasoning) {
        return Uni.createFrom().item(() -> {
                    persistQualityScore(executionId, tenantId, qualityScore,
                            hallucinationRisk, hallucinationDetected, qualityReasoning);
                    return null;
                })
                .runSubscriptionOn(io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool())
                .replaceWithVoid()
                .onFailure().invoke(ex ->
                        Log.errorf(ex, "Failed to update quality score: execution=%s", executionId));
    }

    // =========================================================================
    // Public read API — DriftEvaluatorService
    // =========================================================================

    @Override
    public Uni<List<DriftRow>> findRecentByAdapter(String adapterId, Instant since) {
        return Uni.createFrom().item(() -> {
                    List<DriftRow> rows = new ArrayList<>();
                    UUID adapterUuid = toUuidOrNull(adapterId);
                    if (adapterUuid == null) return rows;

                    try (Connection conn = dataSource.getConnection();
                         PreparedStatement ps = conn.prepareStatement(SELECT_RECENT_BY_ADAPTER)) {

                        ps.setObject(1, adapterUuid);
                        ps.setTimestamp(2, Timestamp.from(since));

                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                rows.add(new DriftRow(
                                        UUID.fromString(rs.getString("id")),
                                        rs.getString("status"),
                                        rs.getBigDecimal("cost_usd"),
                                        rs.getLong("latency_ms"),
                                        rs.getBigDecimal("quality_score"),
                                        rs.getTimestamp("executed_at").toInstant(),
                                        rs.getString("response_text")  // for Ollama semantic drift
                                ));
                            }
                        }
                    } catch (Exception ex) {
                        Log.warnf(ex, "findRecentByAdapter failed: adapter=%s since=%s", adapterId, since);
                    }
                    return rows;
                })
                .runSubscriptionOn(io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool());
    }

    // =========================================================================
    // Public read API — ExecutionResource (UI list)
    // =========================================================================

    @Override
    public Uni<List<ExecutionRow>> listByTenant(UUID tenantId, int limit,
                                                String phase, String adapterId) {
        return Uni.createFrom().item(() -> {
                    List<ExecutionRow> rows = new ArrayList<>();

                    StringBuilder sql = new StringBuilder(SELECT_LIST_BY_TENANT);
                    if (phase != null && !phase.isBlank())
                        sql.append(" AND er.status = ?");
                    if (adapterId != null && !adapterId.isBlank())
                        sql.append(" AND er.adapter_id = ?::uuid");
                    sql.append(" ORDER BY er.executed_at DESC LIMIT ?");

                    try (Connection conn = dataSource.getConnection();
                         PreparedStatement ps = conn.prepareStatement(sql.toString())) {

                        int idx = 1;
                        ps.setObject(idx++, tenantId);
                        if (phase != null && !phase.isBlank())
                            ps.setString(idx++, phase);
                        if (adapterId != null && !adapterId.isBlank())
                            ps.setString(idx++, adapterId);
                        ps.setInt(idx, limit);

                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                rows.add(new ExecutionRow(
                                        UUID.fromString(rs.getString("id")),     // 1  id
                                        uuidOrNull(rs.getString("intent_id")),   // 2  intentId
                                        uuidOrNull(rs.getString("adapter_id")),  // 3  adapterId
                                        rs.getString("adapter_name"),            // 4  adapterName
                                        rs.getInt("attempt_number"),             // 5  attemptNumber
                                        rs.getString("status"),                  // 6  status
                                        rs.getBigDecimal("cost_usd"),            // 7  costUsd
                                        rs.getLong("latency_ms"),                // 8  latencyMs
                                        rs.getInt("prompt_tokens"),              // 9  promptTokens
                                        rs.getInt("completion_tokens"),          // 10 completionTokens
                                        rs.getInt("total_tokens"),               // 11 totalTokens
                                        rs.getBigDecimal("risk_score"),          // 12 riskScore
                                        rs.getBigDecimal("quality_score"),       // 13 qualityScore
                                        rs.getBigDecimal("hallucination_risk"),  // 14 hallucinationRisk
                                        rs.getBoolean("hallucination_detected"), // 15 hallucinationDetected
                                        rs.getString("failure_reason"),          // 16 failureReason
                                        null,                                    // 17 responseText — not in list view
                                        null,                                    // 18 qualityReasoning — not in list view
                                        rs.getTimestamp("executed_at").toInstant() // 19 executedAt
                                ));
                            }
                        }
                    } catch (Exception ex) {
                        Log.errorf(ex, "listByTenant failed: tenantId=%s", tenantId);
                    }
                    return rows;
                })
                .runSubscriptionOn(io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool());
    }

    // =========================================================================
    // Public read API — IntentDetail page (full execution output)
    // =========================================================================

    @Override
    public Uni<List<ExecutionRow>> findByIntentId(UUID tenantId, UUID intentId) {
        return Uni.createFrom().item(() -> {
                    List<ExecutionRow> rows = new ArrayList<>();

                    try (Connection conn = dataSource.getConnection();
                         PreparedStatement ps = conn.prepareStatement(SELECT_BY_INTENT)) {

                        ps.setObject(1, tenantId);
                        ps.setObject(2, intentId);

                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                rows.add(new ExecutionRow(
                                        UUID.fromString(rs.getString("id")),     // 1  id
                                        uuidOrNull(rs.getString("intent_id")),   // 2  intentId
                                        uuidOrNull(rs.getString("adapter_id")),  // 3  adapterId
                                        rs.getString("adapter_name"),            // 4  adapterName
                                        rs.getInt("attempt_number"),             // 5  attemptNumber
                                        rs.getString("status"),                  // 6  status
                                        rs.getBigDecimal("cost_usd"),            // 7  costUsd
                                        rs.getLong("latency_ms"),                // 8  latencyMs
                                        rs.getInt("prompt_tokens"),              // 9  promptTokens
                                        rs.getInt("completion_tokens"),          // 10 completionTokens
                                        rs.getInt("total_tokens"),               // 11 totalTokens
                                        rs.getBigDecimal("risk_score"),          // 12 riskScore
                                        rs.getBigDecimal("quality_score"),       // 13 qualityScore
                                        rs.getBigDecimal("hallucination_risk"),  // 14 hallucinationRisk
                                        rs.getBoolean("hallucination_detected"), // 15 hallucinationDetected
                                        rs.getString("failure_reason"),          // 16 failureReason
                                        rs.getString("response_text"),           // 17 responseText
                                        rs.getString("quality_reasoning"),       // 18 qualityReasoning
                                        rs.getTimestamp("executed_at").toInstant() // 19 executedAt
                                ));
                            }
                        }
                    } catch (Exception ex) {
                        Log.errorf(ex, "findByIntentId failed: intentId=%s", intentId);
                    }
                    return rows;
                })
                .runSubscriptionOn(io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool());
    }

    // =========================================================================
    // Public read API — DriftResource (adapter summary)
    // =========================================================================

    @Override
    public Uni<List<AdapterDriftSummary>> getDriftSummary(UUID tenantId, int days) {
        return Uni.createFrom().item(() -> {
                    List<AdapterDriftSummary> rows = new ArrayList<>();
                    Instant since = Instant.now().minus(days, ChronoUnit.DAYS);

                    try (Connection conn = dataSource.getConnection();
                         PreparedStatement ps = conn.prepareStatement(SELECT_DRIFT_SUMMARY)) {

                        ps.setObject(1, tenantId);
                        ps.setTimestamp(2, Timestamp.from(since));

                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                double failureRate = rs.getDouble("failure_rate");
                                double avgCost     = rs.getDouble("avg_cost_usd");
                                double avgLatency  = rs.getDouble("avg_latency_ms");
                                double avgQuality  = rs.getDouble("avg_quality_score");

                                double driftScore = Math.min(1.0, failureRate * 0.5
                                        + (avgCost    > 0.01   ? 0.1 : 0.0)
                                        + (avgLatency > 5000.0 ? 0.2 : 0.0));

                                Timestamp lastTs = rs.getTimestamp("last_executed_at");

                                rows.add(new AdapterDriftSummary(
                                        rs.getString("adapter_id"),
                                        rs.getString("adapter_name"),
                                        Math.round(driftScore * 10000.0) / 10000.0,
                                        avgCost,
                                        avgLatency,
                                        avgQuality,
                                        failureRate,
                                        rs.getLong("execution_count"),
                                        lastTs != null ? lastTs.toInstant() : null
                                ));
                            }
                        }
                    } catch (Exception ex) {
                        Log.errorf(ex, "getDriftSummary failed: tenantId=%s days=%d", tenantId, days);
                    }
                    return rows;
                })
                .runSubscriptionOn(io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool());
    }

    // =========================================================================
    // Public read API — DriftResource (daily trend)
    // =========================================================================

    @Override
    public Uni<List<DriftTrendPoint>> getDriftTrend(UUID tenantId, int days) {
        return Uni.createFrom().item(() -> {
                    List<DriftTrendPoint> rows = new ArrayList<>();
                    Instant since = Instant.now().minus(days, ChronoUnit.DAYS);

                    try (Connection conn = dataSource.getConnection();
                         PreparedStatement ps = conn.prepareStatement(SELECT_DRIFT_TREND)) {

                        ps.setObject(1, tenantId);
                        ps.setTimestamp(2, Timestamp.from(since));

                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                Timestamp day = rs.getTimestamp("day");
                                rows.add(new DriftTrendPoint(
                                        day != null ? day.toInstant() : Instant.now(),
                                        0.0,
                                        rs.getDouble("avg_cost"),
                                        rs.getDouble("avg_latency"),
                                        rs.getLong("execution_count")
                                ));
                            }
                        }
                    } catch (Exception ex) {
                        Log.errorf(ex, "getDriftTrend failed: tenantId=%s days=%d", tenantId, days);
                    }
                    return rows;
                })
                .runSubscriptionOn(io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool());
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private static final String UPDATE_QUALITY_SCORE = """
            UPDATE execution_records
               SET quality_score          = ?,
                   hallucination_risk     = ?,
                   hallucination_detected = ?,
                   quality_reasoning      = ?
             WHERE id        = ?
               AND tenant_id = ?
            """;

    private void persistQualityScore(UUID executionId, UUID tenantId,
                                     java.math.BigDecimal qualityScore,
                                     java.math.BigDecimal hallucinationRisk,
                                     boolean hallucinationDetected,
                                     String qualityReasoning) {
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(UPDATE_QUALITY_SCORE)) {
                ps.setBigDecimal(1, qualityScore);
                ps.setBigDecimal(2, hallucinationRisk);
                ps.setBoolean(3,    hallucinationDetected);
                ps.setString(4,     truncate(qualityReasoning, 500));
                ps.setObject(5,     executionId);
                ps.setObject(6,     tenantId);
                ps.execute();
            }
        } catch (Exception ex) {
            Log.errorf(ex, "DB error updating quality score: execution=%s", executionId);
        }
    }

    private void persist(ExecutionRecord record, Intent intent) {
        try (Connection conn = dataSource.getConnection()) {
            // Note: RLS (SET LOCAL app.current_tenant_id) is intentionally omitted.
            // The INSERT explicitly sets tenant_id from intent.getTenantId() so every
            // row is correctly scoped. RLS is only needed for reads without a WHERE
            // clause — our read queries always filter by tenant_id explicitly.
            // Including SET LOCAL here caused silent failures when app.current_tenant_id
            // is not configured in the PostgreSQL instance.

            UUID executionId = record.getExecutionId() != null
                    ? record.getExecutionId()
                    : UUID.randomUUID();

            String status    = record.isSuccess() ? "SUCCESS"
                    : (record.getFailureReason() != null ? record.getFailureReason() : "ADAPTER_ERROR");
            status           = truncate(status, 50);
            String failReason = truncate(record.getFailureReason(), 255);
            double cost      = record.getCost().doubleValue();
            String adapterId = record.getAdapterId();

            // Verify adapter exists in the adapters table before using the UUID.
            // If the mock/stub executor returns an ID that isn't in the DB, the FK
            // constraint on adapter_id → adapters(id) would reject the INSERT.
            // Setting null is safe — adapter_id is nullable (ON DELETE SET NULL).
            UUID adapterUuid = resolveAdapterUuid(conn, adapterId, intent.getTenantId());

            try (PreparedStatement ps = conn.prepareStatement(INSERT_EXECUTION)) {
                ps.setObject(1,  executionId);
                ps.setObject(2,  record.getIntentId());
                ps.setObject(3,  null);   // plan_id
                ps.setObject(4,  null);   // plan_step_id
                ps.setObject(5,  intent.getTenantId());
                ps.setObject(6,  adapterUuid);
                ps.setString(7,  status);
                ps.setLong(8,    record.getLatencyMs());
                ps.setInt(9,     0);      // prompt_tokens
                ps.setInt(10,    0);      // completion_tokens
                ps.setInt(11,    0);      // total_tokens
                ps.setDouble(12, cost);
                ps.setDouble(13, 0.0);    // risk_score
                ps.setString(14, failReason);
                ps.setString(15, record.getResponseText());
                ps.execute();
            }

            if (record.isSuccess() && cost > 0.0) {
                double ceiling = getBudgetCeiling(intent);
                try (PreparedStatement ps = conn.prepareStatement(INSERT_SPEND)) {
                    ps.setObject(1, record.getIntentId());
                    ps.setObject(2, executionId);
                    ps.setObject(3, intent.getTenantId());
                    ps.setObject(4, adapterUuid);
                    ps.setDouble(5, cost);
                    if (ceiling > 0.0) ps.setDouble(6, ceiling);
                    else ps.setNull(6, java.sql.Types.NUMERIC);
                    ps.execute();
                }
            }
            // autoCommit=true by default — each statement commits immediately
        } catch (Exception ex) {
            Log.errorf(ex, "DB error persisting execution record: intent=%s", intent.getId());
        }
    }

    private void setRls(Connection conn, UUID tenantId) throws Exception {
        // PostgreSQL SET LOCAL does not accept JDBC parameters ($1 / ?).
        // Parameters are only valid in DML/SELECT statements.
        // UUID.toString() is safe to inline — no SQL injection risk (fixed format).
        conn.createStatement().execute(
                "SET LOCAL app.current_tenant_id = '" + tenantId + "'");
    }

    private double getBudgetCeiling(Intent intent) {
        if (intent.getBudget() == null) return 0.0;
        return intent.getBudget().getCeilingUsd();
    }

    private UUID toUuidOrNull(String id) {
        if (id == null || id.isBlank()) return null;
        try { return UUID.fromString(id); } catch (Exception e) { return null; }
    }

    private UUID uuidOrNull(String s) {
        if (s == null) return null;
        try { return UUID.fromString(s); } catch (Exception e) { return null; }
    }

    /**
     * Resolves adapterId to a UUID by:
     *   1. If it's a valid UUID — check it exists in adapters table for this tenant
     *   2. If it's a plain string (e.g. "openai") — look up by provider or name
     *   3. Returns null if no match found — adapter_id stored as NULL (nullable FK)
     */
    private UUID resolveAdapterUuid(Connection conn, String adapterId, UUID tenantId) {
        if (adapterId == null || adapterId.isBlank()) return null;

        // Try UUID lookup first
        UUID uuid = uuidOrNull(adapterId);
        if (uuid != null) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id FROM adapters WHERE id = ? AND tenant_id = ? LIMIT 1")) {
                ps.setObject(1, uuid);
                ps.setObject(2, tenantId);
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return uuid;
                }
            } catch (Exception ex) {
                Log.warnf("resolveAdapterUuid UUID lookup failed: %s", ex.getMessage());
            }
        }

        // Fall back to name/provider match (handles "openai", "anthropic" etc.)
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM adapters " +
                        "WHERE tenant_id = ? " +
                        "  AND (LOWER(provider) = LOWER(?) OR LOWER(name) = LOWER(?)) " +
                        "  AND is_active = true " +
                        "LIMIT 1")) {
            ps.setObject(1, tenantId);
            ps.setString(2, adapterId);
            ps.setString(3, adapterId);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    UUID found = rs.getObject("id", UUID.class);
                    Log.infof("resolveAdapterUuid: matched '%s' → %s by provider/name", adapterId, found);
                    return found;
                }
            }
        } catch (Exception ex) {
            Log.warnf("resolveAdapterUuid provider lookup failed for '%s': %s",
                    adapterId, ex.getMessage());
        }

        Log.warnf("resolveAdapterUuid: no adapter found for '%s' in tenant %s — storing null",
                adapterId, tenantId);
        return null;
    }

    /** Safely truncates a string to maxLen chars — prevents VARCHAR constraint failures. */
    private static String truncate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) return s;
        return s.substring(0, maxLen);
    }
}