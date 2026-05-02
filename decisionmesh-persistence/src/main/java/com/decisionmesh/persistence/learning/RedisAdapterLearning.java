package com.decisionmesh.persistence.learning;

import com.decisionmesh.application.port.AdapterLearningPort;
import com.decisionmesh.application.port.AdapterStats;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;

/**
 * Implements AdapterLearningPort by querying adapter_performance_profiles
 * joined with adapters — single query, tenant-isolated via RLS.
 *
 * Schema used:
 *   adapters:                     id, provider, model_id, region, allowed_intent_types
 *   adapter_performance_profiles: ema_cost, ema_latency_ms, ema_success_rate,
 *                                 ema_risk_score, composite_score,
 *                                 execution_count, cold_start, is_degraded
 *
 * The planner receives AdapterStats (application type) — not the infrastructure
 * AdapterStats from the selector package. They carry the same data but
 * the application port type is deliberately thin.
 */
@Alternative
@Priority(0)
@ApplicationScoped
public class RedisAdapterLearning implements AdapterLearningPort {

    // Query for a specific set of adapter IDs
    private static final String STATS_BY_IDS = """
            SELECT
                a.id                                        AS adapter_id,
                a.provider,
                a.model_id,
                a.region,
                COALESCE(p.ema_cost,         0.01)         AS ema_cost,
                COALESCE(p.ema_latency_ms,   2000.0)       AS ema_latency_ms,
                COALESCE(p.ema_success_rate, 0.8)          AS ema_success_rate,
                COALESCE(p.ema_risk_score,   0.1)          AS ema_risk_score,
                COALESCE(p.composite_score,  0.5)          AS composite_score,
                COALESCE(p.execution_count,  0)            AS execution_count,
                COALESCE(p.cold_start,       true)         AS cold_start,
                COALESCE(p.is_degraded,      false)        AS is_degraded
            FROM adapters a
            LEFT JOIN adapter_performance_profiles p
                   ON p.adapter_id = a.id AND p.tenant_id = a.tenant_id
            WHERE a.tenant_id = ?
              AND a.is_active  = true
              AND a.id         = ANY(?)
            """;

    // Query for all active LLM adapters eligible for an intent type
    private static final String STATS_BY_INTENT_TYPE = """
            SELECT
                a.id                                        AS adapter_id,
                a.provider,
                a.model_id,
                a.region,
                COALESCE(p.ema_cost,         0.01)         AS ema_cost,
                COALESCE(p.ema_latency_ms,   2000.0)       AS ema_latency_ms,
                COALESCE(p.ema_success_rate, 0.8)          AS ema_success_rate,
                COALESCE(p.ema_risk_score,   0.1)          AS ema_risk_score,
                COALESCE(p.composite_score,  0.5)          AS composite_score,
                COALESCE(p.execution_count,  0)            AS execution_count,
                COALESCE(p.cold_start,       true)         AS cold_start,
                COALESCE(p.is_degraded,      false)        AS is_degraded
            FROM adapters a
            LEFT JOIN adapter_performance_profiles p
                   ON p.adapter_id = a.id AND p.tenant_id = a.tenant_id
            WHERE a.tenant_id    = ?
              AND a.is_active    = true
              AND a.adapter_type = 'LLM'
              AND (
                  a.allowed_intent_types = '[]'::jsonb
                  OR a.allowed_intent_types @> to_jsonb(?::text)
              )
            ORDER BY COALESCE(p.composite_score, 0.5) DESC
            """;

    @Inject
    DataSource dataSource;

    // ── AdapterLearningPort ───────────────────────────────────────────────────

    @Override
    public Uni<Map<String, AdapterStats>> getStats(UUID tenantId, List<String> adapterIds) {
        if (adapterIds == null || adapterIds.isEmpty()) {
            return Uni.createFrom().item(Map.of());
        }
        return Uni.createFrom().item(() -> queryByIds(tenantId, adapterIds))
                .onFailure().invoke(ex ->
                        Log.errorf(ex, "AdapterLearningPort: failed to load stats: tenant=%s", tenantId))
                .onFailure().recoverWithItem(Map.of());
    }

    @Override
    public Uni<Map<String, AdapterStats>> getStatsForIntentType(UUID tenantId, String intentType) {
        return Uni.createFrom().item(() -> queryByIntentType(tenantId, intentType))
                .onFailure().invoke(ex ->
                        Log.errorf(ex, "AdapterLearningPort: failed to load stats: tenant=%s, type=%s",
                                tenantId, intentType))
                .onFailure().recoverWithItem(Map.of());
    }

    // ── DB queries ────────────────────────────────────────────────────────────

    private Map<String, AdapterStats> queryByIds(UUID tenantId, List<String> adapterIds) {
        Map<String, AdapterStats> result = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection()) {
            setRls(conn, tenantId);
            try (PreparedStatement ps = conn.prepareStatement(STATS_BY_IDS)) {
                ps.setObject(1, tenantId);
                // Convert List<String> to UUID[] for ANY(?) operator
                UUID[] uuids = adapterIds.stream()
                        .map(UUID::fromString)
                        .toArray(UUID[]::new);
                ps.setArray(2, conn.createArrayOf("uuid", uuids));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        AdapterStats stats = mapRow(rs);
                        result.put(stats.adapterId().toString(), stats);
                    }
                }
            }
        } catch (Exception ex) {
            Log.errorf(ex, "DB error loading adapter stats: tenant=%s", tenantId);
        }
        return result;
    }

    private Map<String, AdapterStats> queryByIntentType(UUID tenantId, String intentType) {
        Map<String, AdapterStats> result = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection()) {
            setRls(conn, tenantId);
            try (PreparedStatement ps = conn.prepareStatement(STATS_BY_INTENT_TYPE)) {
                ps.setObject(1, tenantId);
                ps.setString(2, intentType);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        AdapterStats stats = mapRow(rs);
                        result.put(stats.adapterId().toString(), stats);
                    }
                }
            }
        } catch (Exception ex) {
            Log.errorf(ex, "DB error loading adapter stats by intentType: tenant=%s, type=%s",
                    tenantId, intentType);
        }
        return result;
    }

    private void setRls(Connection conn, UUID tenantId) throws Exception {

        String sql = "SET LOCAL app.current_tenant_id = '" + tenantId + "'";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.execute();
        }
    }

    private AdapterStats mapRow(ResultSet rs) throws Exception {
        return new AdapterStats(
                // identity
                UUID.fromString(rs.getString("adapter_id")),
                rs.getString("provider"),
                rs.getString("model_id"),
                rs.getString("region"),
                // performance
                rs.getDouble("ema_cost"),
                rs.getLong("ema_latency_ms"),              // avgLatency is long
                1.0 - rs.getDouble("ema_success_rate"),   // failureRate = 1 - successRate
                rs.getDouble("ema_risk_score"),
                rs.getDouble("composite_score"),
                // state
                rs.getLong("execution_count"),
                rs.getBoolean("cold_start"),
                rs.getBoolean("is_degraded")
        );
    }
}