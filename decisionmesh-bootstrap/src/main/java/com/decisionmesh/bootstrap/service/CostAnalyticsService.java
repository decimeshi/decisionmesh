package com.decisionmesh.bootstrap.service;

import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Computes cost analytics for the Dashboard and CostAnalytics pages.
 *
 * Uses blocking JDBC (same pattern as ExecutionRecordRepository) to run
 * aggregation queries against spend_records and adapters tables.
 * Wrapped in Uni.createFrom().item() to keep the Mutiny chain intact.
 *
 * Queries are tenant-scoped — no cross-tenant data leaks.
 */
@ApplicationScoped
public class CostAnalyticsService {

    @Inject
    DataSource dataSource;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns all cost metrics in a single object.
     * Called by AnalyticsResource for GET /api/analytics/cost.
     */
    public Uni<CostAnalyticsDto> getAnalytics(UUID tenantId) {
        return Uni.createFrom().item(() -> compute(tenantId));
    }

    // ── Computation ───────────────────────────────────────────────────────────

    private CostAnalyticsDto compute(UUID tenantId) {
        BigDecimal         totalCostUsd      = fetchTotalCost(tenantId);
        BigDecimal         avgCostPerIntent  = fetchAvgCostPerIntent(tenantId);
        List<CostOverTime> costOverTime      = fetchCostOverTime(tenantId);
        List<CostByAdapter> costByAdapter    = fetchCostByAdapter(tenantId);

        return new CostAnalyticsDto(totalCostUsd, avgCostPerIntent, costOverTime, costByAdapter);
    }

    // ── Query: total cost ─────────────────────────────────────────────────────

    private static final String SQL_TOTAL_COST = """
            SELECT COALESCE(SUM(amount_usd), 0)
            FROM   spend_records
            WHERE  tenant_id = ?
            """;

    private BigDecimal fetchTotalCost(UUID tenantId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_TOTAL_COST)) {
            ps.setObject(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBigDecimal(1) : BigDecimal.ZERO;
            }
        } catch (Exception e) {
            Log.warnf(e, "[Analytics] fetchTotalCost failed for tenant=%s", tenantId);
            return BigDecimal.ZERO;
        }
    }

    // ── Query: avg cost per intent ────────────────────────────────────────────

    private static final String SQL_AVG_PER_INTENT = """
            SELECT COALESCE(AVG(intent_total), 0)
            FROM (
                SELECT SUM(amount_usd) AS intent_total
                FROM   spend_records
                WHERE  tenant_id = ?
                GROUP  BY intent_id
            ) sub
            """;

    private BigDecimal fetchAvgCostPerIntent(UUID tenantId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_AVG_PER_INTENT)) {
            ps.setObject(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    BigDecimal avg = rs.getBigDecimal(1);
                    return avg != null ? avg.setScale(6, RoundingMode.HALF_UP) : BigDecimal.ZERO;
                }
            }
        } catch (Exception e) {
            Log.warnf(e, "[Analytics] fetchAvgCostPerIntent failed for tenant=%s", tenantId);
        }
        return BigDecimal.ZERO;
    }

    // ── Query: cost over time (last 30 days, daily) ───────────────────────────

    private static final String SQL_COST_OVER_TIME = """
            SELECT
                DATE(recorded_at AT TIME ZONE 'UTC') AS day,
                SUM(amount_usd)                       AS total_cost
            FROM   spend_records
            WHERE  tenant_id  = ?
              AND  recorded_at >= NOW() - INTERVAL '30 days'
            GROUP  BY day
            ORDER  BY day ASC
            """;

    private List<CostOverTime> fetchCostOverTime(UUID tenantId) {
        List<CostOverTime> rows = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_COST_OVER_TIME)) {
            ps.setObject(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new CostOverTime(
                            rs.getDate("day").toString(),          // "YYYY-MM-DD"
                            rs.getBigDecimal("total_cost")
                    ));
                }
            }
        } catch (Exception e) {
            Log.warnf(e, "[Analytics] fetchCostOverTime failed for tenant=%s", tenantId);
        }
        return rows;
    }

    // ── Query: cost by adapter (JOIN with adapters for name) ──────────────────

    private static final String SQL_COST_BY_ADAPTER = """
            SELECT
                COALESCE(a.name, 'Unknown')   AS adapter_name,
                SUM(s.amount_usd)              AS total_cost
            FROM   spend_records s
            LEFT   JOIN adapters a ON a.id = s.adapter_id
            WHERE  s.tenant_id = ?
            GROUP  BY a.id, a.name
            ORDER  BY total_cost DESC
            LIMIT  20
            """;

    private List<CostByAdapter> fetchCostByAdapter(UUID tenantId) {
        List<CostByAdapter> rows = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_COST_BY_ADAPTER)) {
            ps.setObject(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new CostByAdapter(
                            rs.getString("adapter_name"),
                            rs.getBigDecimal("total_cost")
                    ));
                }
            }
        } catch (Exception e) {
            Log.warnf(e, "[Analytics] fetchCostByAdapter failed for tenant=%s", tenantId);
        }
        return rows;
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    /** Top-level response for GET /api/analytics/cost */
    public record CostAnalyticsDto(
            BigDecimal          totalCostUsd,
            BigDecimal          avgCostPerIntent,
            List<CostOverTime>  costOverTime,
            List<CostByAdapter> costByAdapter
    ) {}

    /** One data point in the cost-over-time chart */
    public record CostOverTime(
            String     date,       // "YYYY-MM-DD"
            BigDecimal totalCost
    ) {}

    /** One bar in the cost-by-adapter chart */
    public record CostByAdapter(
            String     adapterName,
            BigDecimal totalCost
    ) {}
}
