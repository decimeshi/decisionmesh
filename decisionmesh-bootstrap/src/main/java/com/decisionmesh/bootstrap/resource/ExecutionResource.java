package com.decisionmesh.bootstrap.resource;


import com.decisionmesh.contracts.security.context.TenantContext;
import com.decisionmesh.application.port.ExecutionRecordQueryPort;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.quarkus.security.Authenticated;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.Map;
import java.util.UUID;

/**
 * REST resource for execution records.
 *
 * GET /api/executions              — list executions for tenant (paginated)
 *
 * Auth: JWT Bearer — tenantId resolved from token claim.
 * All queries are tenant-scoped — no cross-tenant data leakage possible.
 */
@Path("/api/executions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class ExecutionResource {

    @Inject
    ExecutionRecordQueryPort queryPort;

    @Inject
    JsonWebToken jwt;
    @Inject TenantContext tenantContext;

    @GET
    @Path("/by-intent/{intentId}")
    @WithTransaction
    @RolesAllowed({"sys_admin", "tenant_user"})
    public Uni<Response> byIntent(@PathParam("intentId") UUID intentId) {
        return getTenantId()
                .onItem().transformToUni(tenantId ->
                        queryPort.findByIntentId(tenantId, intentId)
                )
                .map(rows -> Response.ok(rows).build())
                .onFailure().recoverWithItem(e ->
                        Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                                .entity(Map.of("message", e.getMessage()))
                                .build()
                );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/executions
    // Query params:
    //   size      — max records to return (default 50, max 200)
    //   phase     — filter by phase e.g. EXECUTING, COMPLETED (optional)
    //   adapterId — filter by adapter UUID (optional)
    // ─────────────────────────────────────────────────────────────────────────

    @GET
    @WithTransaction
    @RolesAllowed({"sys_admin", "tenant_user"})
    public Uni<Response> list(
            @QueryParam("size")      @DefaultValue("50")  int    size,
            @QueryParam("phase")                          String phase,
            @QueryParam("adapterId")                      String adapterId
    ) {
        // Clamp size to avoid accidentally fetching thousands of rows
        int limit = Math.min(size, 200);

        return getTenantId()
                .onItem().transformToUni(tenantId ->
                        queryPort.listByTenant(tenantId, limit, phase, adapterId)
                )
                .map(rows -> Response.ok(rows).build())
                .onFailure().invoke(e ->
                        jakarta.ws.rs.core.Context.class.getEnclosingClass()) // log placeholder
                .onFailure().recoverWithItem(e ->
                        Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                                .entity(java.util.Map.of("message", e.getMessage()))
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