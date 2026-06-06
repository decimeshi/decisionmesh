package com.decisionmesh.bootstrap.api;

import com.decisionmesh.bootstrap.service.GdprErasureService;
import com.decisionmesh.contracts.security.context.TenantContext;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.Map;
import java.util.UUID;

/**
 * GDPR Right to Erasure — Article 17
 *
 * DELETE /api/account
 *   Permanently deletes all personal data for the authenticated tenant.
 *   Requires confirmation phrase in request body.
 *   Invalidates session after erasure.
 */
@Path("/api/account")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class GdprResource {

    @Inject GdprErasureService erasureService;
    @Inject TenantContext      tenantContext;
    @Inject JsonWebToken       jwt;

    /**
     * DELETE /api/account
     *
     * Body: { "confirmation": "delete my account" }
     *
     * Returns 200 on success, 400 if confirmation missing/wrong, 403 if not authenticated.
     */
    @DELETE
    public Uni<Response> deleteAccount(DeleteAccountRequest request) {

        if (tenantContext.getTenantId() == null) {
            return Uni.createFrom().item(
                Response.status(403).entity(Map.of("error", "Not authenticated")).build()
            );
        }

        // Require explicit confirmation to prevent accidental deletion
        if (request == null || !"delete my account".equalsIgnoreCase(request.confirmation)) {
            return Uni.createFrom().item(
                Response.status(400).entity(Map.of(
                    "error", "Confirmation required",
                    "detail", "Send { \"confirmation\": \"delete my account\" } to confirm erasure"
                )).build()
            );
        }

        UUID tenantId    = tenantContext.getTenantId();
        String userEmail = jwt.getClaim("email");
        String userId    = jwt.getSubject();

        Log.warnf("[GDPR] Account deletion requested: tenant=%s user=%s", tenantId, userEmail);

        return erasureService.eraseAll(tenantId, userEmail != null ? userEmail : userId, userId)
                .onItem().transform(v -> {
                    Log.infof("[GDPR] Account deleted successfully: tenant=%s", tenantId);
                    return Response.ok(Map.of(
                        "success", true,
                        "message", "Your account and all associated data have been permanently deleted.",
                        "compliance", "GDPR Article 17 — Right to Erasure"
                    )).build();
                })
                .onFailure().recoverWithItem(e -> {
                    Log.errorf(e, "[GDPR] Account deletion failed: tenant=%s", tenantId);
                    return Response.status(500).entity(Map.of(
                        "error", "Erasure failed",
                        "detail", "Please contact support@decimeshi.com with your request ID"
                    )).build();
                });
    }

    /**
     * GET /api/account/erasure-status
     * Returns status of any pending erasure request for this tenant.
     */
    @GET
    @Path("/erasure-status")
    public Response getErasureStatus() {
        if (tenantContext.getTenantId() == null) {
            return Response.status(403).entity(Map.of("error", "Not authenticated")).build();
        }
        return Response.ok(Map.of(
            "tenantId", tenantContext.getTenantId().toString(),
            "status", "No pending erasure request",
            "info", "Submit DELETE /api/account to request erasure"
        )).build();
    }

    // ── DTO ──────────────────────────────────────────────────────────────────

    public static class DeleteAccountRequest {
        public String confirmation;
        public String reason;  // optional — user can state reason
    }
}
