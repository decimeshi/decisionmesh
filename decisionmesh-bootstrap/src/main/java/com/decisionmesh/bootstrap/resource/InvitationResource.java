package com.decisionmesh.bootstrap.resource;

import com.decisionmesh.contracts.security.context.TenantContext;
import com.decisionmesh.bootstrap.service.InvitationService;
import com.decisionmesh.persistence.entity.InvitationEntity;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Path("/api/invitations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class InvitationResource {

    @Inject InvitationService service;
    @Inject JsonWebToken      jwt;
    @Inject TenantContext tenantContext;

    @POST
    public Uni<InvitationEntity> invite(Map<String, String> body) {
        return service.createInvitation(tenantId(), body.get("email"), body.get("role"));
    }

    @GET
    public Uni<List<InvitationEntity>> list() {
        return service.list(tenantId());
    }

    @DELETE
    @Path("/{id}")
    public Uni<Void> revoke(@PathParam("id") UUID id) {
        return service.revoke(id);
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