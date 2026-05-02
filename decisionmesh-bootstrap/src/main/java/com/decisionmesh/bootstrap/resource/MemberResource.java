package com.decisionmesh.bootstrap.resource;

import com.decisionmesh.contracts.security.context.TenantContext;
import com.decisionmesh.contracts.security.entity.UserOrganizationEntity;
import com.decisionmesh.contracts.security.service.UserOrganizationService;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Path("/api/members")
@Produces(MediaType.APPLICATION_JSON)
public class MemberResource {

    @Inject UserOrganizationService service;
    @Inject JsonWebToken            jwt;
    @Inject TenantContext tenantContext;

    @GET
    public Uni<List<UserOrganizationEntity>> list() {
        return service.findAllByUserId(userId());
    }

    @DELETE
    @Path("/{userId}")
    public Uni<Void> remove(@PathParam("userId") UUID userId) {
        return service.deactivateMembership(userId, null);
    }

    @PATCH
    @Path("/{userId}/role")
    public Uni<UserOrganizationEntity> updateRole(@PathParam("userId") UUID userId,
                                                  Map<String, String> body) {
        return service.findByUserAndTenant(userId, tenantId())
                .invoke(m -> m.role = body.get("role"))
                .flatMap(service.repository::persist);
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

    private UUID userId() {
        String uid = jwt.getSubject();
        if (uid == null || uid.isBlank()) throw new ForbiddenException("Missing userId in token");
        // Handle Zitadel numeric sub IDs (not UUID format)
        try { return UUID.fromString(uid); }
        catch (IllegalArgumentException e) {
            return java.util.UUID.nameUUIDFromBytes(
                    uid.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}