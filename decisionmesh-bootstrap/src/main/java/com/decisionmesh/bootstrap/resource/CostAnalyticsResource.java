package com.decisionmesh.bootstrap.resource;

import com.decisionmesh.contracts.security.context.TenantContext;
import io.quarkus.logging.Log;
import io.smallrye.common.annotation.NonBlocking;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.hibernate.reactive.mutiny.Mutiny;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Cost analytics for CostAnalytics.jsx and Dashboard.jsx.
 *
 * GET /api/analytics/cost → { totalCostUsd, avgCostPerIntent, costOverTime[], costByAdapter[] }
 *
 * Key notes:
 *  1. UUID tenantId passed as String to setParameter() — Hibernate Reactive native
 *     queries cannot resolve JDBC type for UUID directly; passing UUID.toString()
 *     avoids a silent type-binding failure that caused all queries to return 0/empty.
 *  2. Each query in its own sf.withSession() — HR sessions are single-threaded,
 *     running concurrent queries on one session causes errors.
 */
@Path("/api/analytics")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"sys_admin", "tenant_admin", "tenant_user"})
public class CostAnalyticsResource {

    @Inject JsonWebToken          jwt;
    @Inject Mutiny.SessionFactory sf;
    @Inject TenantContext         tenantContext;

    @GET
    @Path("/cost")
    @NonBlocking
    public Uni<Map<String, Object>> getCostAnalytics() {
        String tid = tenantIdStr();

        // ── 1. Total cost ─────────────────────────────────────────────────────
        Uni<Object> totalUni = sf.withSession(s ->
                        s.createNativeQuery(
                                        "SELECT COALESCE(SUM(cost_usd), 0) " +
                                                "FROM execution_records WHERE tenant_id = CAST(:tid AS uuid)")
                                .setParameter("tid", tid)
                                .getSingleResult()
                ).onFailure().invoke(ex -> Log.warnf(ex, "[Cost] totalUni failed"))
                .onFailure().recoverWithItem(0);

        // ── 2. Avg cost per intent ────────────────────────────────────────────
        Uni<Object> avgUni = sf.withSession(s ->
                        s.createNativeQuery(
                                        "SELECT COALESCE(AVG(intent_total), 0) FROM " +
                                                "  (SELECT SUM(cost_usd) AS intent_total " +
                                                "   FROM execution_records WHERE tenant_id = CAST(:tid AS uuid) " +
                                                "   GROUP BY intent_id) sub")
                                .setParameter("tid", tid)
                                .getSingleResult()
                ).onFailure().invoke(ex -> Log.warnf(ex, "[Cost] avgUni failed"))
                .onFailure().recoverWithItem(0);

        // ── 3. Cost over time — daily ─────────────────────────────────────────
        Uni<List<Object>> timeUni = sf.withSession(s ->
                        s.createNativeQuery(
                                        "SELECT TO_CHAR(DATE_TRUNC('day', executed_at), 'Mon DD') AS lbl, " +
                                                "       SUM(cost_usd) AS total " +
                                                "FROM execution_records " +
                                                "WHERE tenant_id = CAST(:tid AS uuid) AND cost_usd IS NOT NULL " +
                                                "GROUP BY DATE_TRUNC('day', executed_at) " +
                                                "ORDER BY DATE_TRUNC('day', executed_at) ASC")
                                .setParameter("tid", tid)
                                .getResultList()
                ).onFailure().invoke(ex -> Log.warnf(ex, "[Cost] timeUni failed"))
                .onFailure().recoverWithItem(Collections.emptyList());

        // ── 4. Cost by adapter ────────────────────────────────────────────────
        Uni<List<Object>> adapterUni = sf.withSession(s ->
                        s.createNativeQuery(
                                        "SELECT a.name, COALESCE(SUM(e.cost_usd), 0) AS total " +
                                                "FROM execution_records e " +
                                                "JOIN adapters a ON e.adapter_id = a.id " +
                                                "WHERE e.tenant_id = CAST(:tid AS uuid) " +
                                                "GROUP BY a.id, a.name " +
                                                "ORDER BY total DESC")
                                .setParameter("tid", tid)
                                .getResultList()
                ).onFailure().invoke(ex -> Log.warnf(ex, "[Cost] adapterUni failed"))
                .onFailure().recoverWithItem(Collections.emptyList());

        return Uni.combine().all()
                .unis(totalUni, avgUni, timeUni, adapterUni)
                .asTuple()
                .map(t -> buildResponse(t.getItem1(), t.getItem2(), t.getItem3(), t.getItem4()))
                .onFailure().invoke(ex -> Log.warnf(ex, "[Cost] combine failed tenant=%s", tid))
                .onFailure().recoverWithItem(emptyResponse());
    }

    // ── Response builder ──────────────────────────────────────────────────────

    private Map<String, Object> buildResponse(Object total, Object avg,
                                              List<Object> timeRows,
                                              List<Object> adapterRows) {
        List<Map<String, Object>> costOverTime = new ArrayList<>();
        for (Object row : timeRows) {
            Object[] r = toArray(row);
            if (r != null && r.length >= 2) {
                costOverTime.add(Map.of(
                        "date",      r[0] != null ? r[0].toString() : "—",
                        "totalCost", toDouble(r[1])
                ));
            }
        }

        List<Map<String, Object>> costByAdapter = new ArrayList<>();
        for (Object row : adapterRows) {
            Object[] r = toArray(row);
            if (r != null && r.length >= 2) {
                costByAdapter.add(Map.of(
                        "adapterName", r[0] != null ? r[0].toString() : "Unknown",
                        "totalCost",   toDouble(r[1])
                ));
            }
        }

        return Map.of(
                "totalCostUsd",     round(toDouble(total)),
                "avgCostPerIntent", round(toDouble(avg)),
                "costOverTime",     costOverTime,
                "costByAdapter",    costByAdapter
        );
    }

    private Map<String, Object> emptyResponse() {
        return Map.of(
                "totalCostUsd",     0.0,
                "avgCostPerIntent", 0.0,
                "costOverTime",     List.of(),
                "costByAdapter",    List.of()
        );
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String tenantIdStr() {
        // ── 1. TenantContext (DB fallback set by TenantContextFilter) ─────────
        UUID ctxTid = tenantContext.getTenantId();
        if (ctxTid != null) return ctxTid.toString();

        // ── 2. JWT claim (set by Zitadel Action) ─────────────────────────────
        String tid = jwt.getClaim("tenantId");
        if (tid == null || tid.isBlank())
            throw new ForbiddenException("Missing tenantId — onboarding not complete");
        try { UUID.fromString(tid); }
        catch (IllegalArgumentException e) { throw new BadRequestException("Invalid tenantId format"); }
        return tid;
    }

    private Object[] toArray(Object row) {
        if (row instanceof Object[] arr) return arr;
        if (row != null) return new Object[]{ row };
        return null;
    }

    private double toDouble(Object val) {
        if (val == null) return 0.0;
        if (val instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(val.toString()); } catch (Exception e) { return 0.0; }
    }

    private double round(double val) {
        return BigDecimal.valueOf(val).setScale(6, RoundingMode.HALF_UP).doubleValue();
    }
}