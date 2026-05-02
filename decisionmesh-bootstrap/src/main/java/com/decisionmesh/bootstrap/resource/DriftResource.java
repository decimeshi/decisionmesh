package com.decisionmesh.bootstrap.resource;


import com.decisionmesh.contracts.security.context.TenantContext;
import com.decisionmesh.application.port.ExecutionRecordQueryPort;
import com.decisionmesh.application.port.ExecutionRecordQueryPort.AdapterDriftSummary;
import com.decisionmesh.application.port.ExecutionRecordQueryPort.DriftTrendPoint;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.quarkus.security.Authenticated;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST resource for drift analytics dashboard.
 *
 * GET /api/analytics/drift         — summary + trend for tenant
 *
 * Returns:
 * {
 *   "summary": [ { adapterId, adapterName, avgDriftScore, ... } ],
 *   "trend":   [ { date, avgDrift, avgCost, avgLatency, executionCount } ]
 * }
 *
 * Auth: JWT Bearer — tenantId resolved from token claim.
 */
@Path("/api/analytics/drift")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class DriftResource {

    @Inject
    ExecutionRecordQueryPort queryPort;
    @Inject TenantContext tenantContext;

    @Inject
    JsonWebToken jwt;

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/analytics/drift
    // Query params:
    //   days — look-back window in days (default 30)
    // ─────────────────────────────────────────────────────────────────────────

    @GET
    @WithTransaction
    @RolesAllowed({"sys_admin", "tenant_user"})
    public Uni<Response> getDrift(
            @QueryParam("days") @DefaultValue("30") int days
    ) {
        // Clamp days to a sensible range
        int lookback = Math.min(Math.max(days, 1), 90);

        return getTenantId()
                .onItem().transformToUni(tenantId ->
                        // Fetch summary + trend in parallel
                        Uni.combine().all()
                                .unis(
                                        queryPort.getDriftSummary(tenantId, lookback),
                                        queryPort.getDriftTrend(tenantId, lookback)
                                )
                                .asTuple()
                )
                .map(tuple -> {
                    List<AdapterDriftSummary> summary = tuple.getItem1();
                    List<DriftTrendPoint>     trend   = tuple.getItem2();

                    // Overall drift score = weighted average across adapters
                    double overallDrift = summary.stream()
                            .mapToDouble(AdapterDriftSummary::avgDriftScore)
                            .average()
                            .orElse(0.0);

                    Map<String, Object> body = Map.of(
                            "overallDrift", Math.round(overallDrift * 10000.0) / 10000.0,
                            "lookbackDays", lookback,
                            "summary",      summary,
                            "trend",        trend
                    );

                    return Response.ok(body).build();
                })
                .onFailure().recoverWithItem(e ->
                        Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                                .entity(Map.of("message", e.getMessage()))
                                .build()
                );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tenant resolution — same pattern as OrgBrandingResource
    // ─────────────────────────────────────────────────────────────────────────

    private Uni<UUID> getTenantId() {
        return Uni.createFrom().item(() -> {
            // ── 1. TenantContext (DB fallback set by TenantContextFilter) ─────
            UUID ctxTid = tenantContext.getTenantId();
            if (ctxTid != null) return ctxTid;
            // ── 2. JWT claim (set by Zitadel Action) ──────────────────────────
            String tid = jwt.getClaim("tenantId");
            if (tid == null || tid.isBlank())
                throw new ForbiddenException("Missing tenantId — onboarding not complete");
            try { return UUID.fromString(tid); }
            catch (IllegalArgumentException e) { throw new BadRequestException("Invalid tenantId format"); }
        });
    }
}