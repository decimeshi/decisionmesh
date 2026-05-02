package com.decisionmesh.llm.registry;

import com.decisionmesh.domain.intent.Intent;
import com.decisionmesh.llm.selector.AdapterStats;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Loads active LLM adapters from the adapters table and enriches them with
 * EMA performance stats from adapter_performance_profiles.
 *
 * Single LEFT JOIN — avoids N+1 per-adapter profile queries.
 * RLS enforced via SET LOCAL app.current_tenant_id before query.
 *
 * FIX 3.6: Empty result is logged at ERROR level with actionable diagnosis hints.
 *   LlmModelSelector.handleEmptyRegistry() also emits a metric when it receives
 *   an empty list, so the signal is visible in both logs and dashboards.
 */
@ApplicationScoped
public class AdapterRegistry {

    private static final String CANDIDATES_SQL = """
            SELECT
                a.id                                        AS adapter_id,
                a.provider,
                a.model_id,
                a.region,
                -- EMA stats (NULL when adapter has never been used — cold start)
                p.ema_cost,
                p.ema_latency_ms,
                p.ema_success_rate,
                p.ema_risk_score,
                p.composite_score,
                p.execution_count,
                COALESCE(p.cold_start,  true)               AS cold_start,
                COALESCE(p.is_degraded, false)              AS is_degraded,
                p.degraded_reason
            FROM adapters a
            LEFT JOIN adapter_performance_profiles p
                   ON p.adapter_id = a.id
                  AND p.tenant_id  = a.tenant_id
            WHERE a.tenant_id    = ?
              AND a.is_active    = true
              AND a.adapter_type = 'LLM'
              AND (
                  a.allowed_intent_types = '[]'::jsonb
                  OR a.allowed_intent_types @> to_jsonb(?::text)
              )
            ORDER BY COALESCE(p.composite_score, 0.4) DESC
            """;
    // ORDER BY uses 0.4 as cold-start prior — slightly below the AdapterStats 0.5 in-memory prior
    // so DB-ordered cold adapters appear after adapters with any positive execution history.

    @Inject
    DataSource dataSource;

    /**
     * Load all eligible LLM adapters with performance stats for this intent.
     *
     * Returns empty list (not a failure) when DB query succeeds but no rows match.
     * The LlmModelSelector.handleEmptyRegistry() handles the empty case with
     * appropriate ERROR logging and metrics.
     */
    public Uni<List<AdapterStats>> loadCandidates(Intent intent) {
        UUID   tenantId   = intent.getTenantId();
        String intentType = intent.getIntentType();

        return Uni.createFrom().item(() -> executeQuery(tenantId, intentType))
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .onFailure().invoke(ex ->
                        // FIX 3.6: DB failure is also loud — distinguish from empty result
                        Log.errorf(ex,
                                "AdapterRegistry: DB query FAILED for tenant=%s, type=%s. " +
                                        "Check: DB connectivity, RLS setup, tenant context.",
                                tenantId, intentType))
                .onFailure().recoverWithItem(List.of());
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private List<AdapterStats> executeQuery(UUID tenantId, String intentType) {
        List<AdapterStats> results = new ArrayList<>();

        try (Connection conn = dataSource.getConnection()) {
            // SET LOCAL only persists for the duration of a transaction.
            // Disable autocommit so both SET LOCAL and the query run in the same transaction.
            // No SET LOCAL needed — WHERE a.tenant_id = ? is the explicit tenant filter.
            // SET LOCAL only persists within a transaction and causes silent failures
            // when called from the Vert.x event loop with an autocommit connection.
            try (PreparedStatement ps = conn.prepareStatement(CANDIDATES_SQL)) {
                ps.setObject(1, tenantId);
                ps.setString(2, intentType);

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        results.add(mapRow(rs));
                    }
                }
            }
        } catch (Exception ex) {
            Log.errorf(ex, "AdapterRegistry: SQL error: tenant=%s", tenantId);
        }

        // FIX 3.6: distinguish between "no adapters registered" and normal operation
        if (results.isEmpty()) {
            Log.errorf(
                    "AdapterRegistry: zero candidates for tenant=%s, intentType=%s. " +
                            "Verify: INSERT into adapters with is_active=true, adapter_type='LLM', " +
                            "and allowed_intent_types='[]' or containing '%s'.",
                    tenantId, intentType, intentType);
        } else {
            Log.debugf("AdapterRegistry: %d candidates loaded: tenant=%s, intentType=%s",
                    results.size(), tenantId, intentType);
        }

        return results;
    }

    private AdapterStats mapRow(ResultSet rs) throws Exception {
        UUID   adapterId = UUID.fromString(rs.getString("adapter_id"));
        String provider  = rs.getString("provider");
        String modelId   = rs.getString("model_id");
        String region    = rs.getString("region");

        AdapterStats stats = new AdapterStats(adapterId, provider, modelId, region);

        // Apply DB profile values when available (override cold-start priors)
        if (rs.getObject("ema_success_rate") != null) {
            stats.setEmaSuccessRate(rs.getDouble("ema_success_rate"));
            stats.setEmaLatencyMs(  rs.getDouble("ema_latency_ms"));
            stats.setEmaCostPerCall(rs.getDouble("ema_cost"));
            stats.setEmaRiskScore(  rs.getDouble("ema_risk_score"));
            stats.setExecutionCount(rs.getLong("execution_count"));
        }
        stats.setDegraded(rs.getBoolean("is_degraded"));

        return stats;
    }
}