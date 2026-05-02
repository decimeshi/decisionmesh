package com.decisionmesh.bootstrap.resource;

import com.decisionmesh.contracts.security.context.TenantContext;
import com.decisionmesh.bootstrap.service.CostAnalyticsService;
import com.decisionmesh.bootstrap.service.CostAnalyticsService.CostAnalyticsDto;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.UUID;

@Path("/api/analytics")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"sys_admin", "tenant_admin", "tenant_user"})
public class AnalyticsResource {

    @Inject JsonWebToken                             jwt;
    @Inject
    CostAnalyticsService costAnalyticsService;
    @Inject TenantContext tenantContext;

    @GET
    @Path("/cost")
    public Uni<CostAnalyticsDto> getCostAnalytics(
            @QueryParam("period") @DefaultValue("30d") String period) {
        return costAnalyticsService.getAnalytics(tenantId());
    }

    private UUID tenantId() {
        // ── 1. TenantContext (DB fallback set by TenantContextFilter) ─────────
        UUID ctxTid = tenantContext.getTenantId();
        if (ctxTid != null) return ctxTid;
        // ── 2. JWT claim (set by Zitadel Action) ─────────────────────────────
        String tid = jwt.getClaim("tenantId");
        if (tid == null || tid.isBlank()) throw new ForbiddenException("Missing tenantId — onboarding not complete");
        try { return UUID.fromString(tid); }
        catch (IllegalArgumentException e) { throw new BadRequestException("Invalid tenantId format"); }
    }
}