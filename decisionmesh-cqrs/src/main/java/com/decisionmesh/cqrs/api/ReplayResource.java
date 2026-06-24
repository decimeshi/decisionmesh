package com.decisionmesh.cqrs.api;

import com.decisionmesh.common.dto.ReplayResponse;
import com.decisionmesh.contracts.security.context.TenantContext;
import com.decisionmesh.cqrs.replay.ReplayService;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Path("/api/governance/replay")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"sys_admin", "tenant_admin", "tenant_user"})
@ApplicationScoped
public class ReplayResource {

    @Inject ReplayService replayService;
    @Inject TenantContext tenantContext;
    @Inject JsonWebToken  jwt;

    @GET
    @Path("/{intentId}")
    @WithSession
    public Uni<Response> replay(@PathParam("intentId") UUID intentId) {
        UUID tenantId = resolveTenantId();
        Log.debugf("[ReplayResource] Replay request: intent=%s tenant=%s", intentId, tenantId);

        return replayService.replayForTenant(tenantId, intentId)
                .map(entries -> {
                    if (entries.isEmpty()) {
                        // No ledger entries — intent may be pending human review
                        // or governance ledger hasn't been written yet
                        return Response.ok(Map.of(
                                "intentId",   intentId.toString(),
                                "entries",    List.of(),
                                "chainValid", true,
                                "status",     "PENDING_REVIEW",
                                "message",    "No governance ledger entries found. " +
                                        "If this intent has requireHumanReview=true, " +
                                        "it is awaiting a decision in the Review Queue. " +
                                        "Replay will be available after the decision is made."
                        )).build();
                    }

                    boolean allValid = entries.stream().allMatch(e -> e.chainValid);
                    long    denied   = entries.stream().filter(e -> "DENY".equals(e.decision)).count();
                    long    allowed  = entries.stream().filter(e -> "ALLOW".equals(e.decision)).count();

                    return Response.ok(Map.of(
                            "intentId",    intentId.toString(),
                            "entries",     entries,
                            "totalEvents", entries.size(),
                            "chainValid",  allValid,
                            "status",      "COMPLETE",
                            "summary", Map.of(
                                    "allowed", allowed,
                                    "denied",  denied,
                                    "errors",  entries.size() - allowed - denied
                            )
                    )).build();
                })
                .onFailure().recoverWithItem(ex -> {
                    Log.errorf("[ReplayResource] Replay failed for intent %s: %s",
                            intentId, ex.getMessage());
                    return Response.serverError()
                            .entity(Map.of("message", "Replay failed: " + ex.getMessage()))
                            .build();
                });
    }

    @GET
    @Path("/{intentId}/verify")
    @WithSession
    public Uni<Response> verify(@PathParam("intentId") UUID intentId) {
        UUID tenantId = resolveTenantId();

        return replayService.replayForTenant(tenantId, intentId)
                .map(entries -> {
                    if (entries.isEmpty()) {
                        return Response.ok(Map.of(
                                "intentId",   intentId.toString(),
                                "entryCount", 0,
                                "chainValid", true,
                                "status",     "PENDING_REVIEW",
                                "message",    "No ledger entries — intent may be pending human review"
                        )).build();
                    }
                    boolean chainValid = entries.stream().allMatch(e -> e.chainValid);
                    return Response.ok(Map.of(
                            "intentId",   intentId.toString(),
                            "entryCount", entries.size(),
                            "chainValid", chainValid,
                            "status",     chainValid ? "INTACT" : "COMPROMISED"
                    )).build();
                })
                .onFailure().recoverWithItem(ex ->
                        Response.serverError()
                                .entity(Map.of("message", "Verification failed: " + ex.getMessage()))
                                .build());
    }

    private UUID resolveTenantId() {
        UUID ctxTid = tenantContext.getTenantId();
        if (ctxTid != null) return ctxTid;
        String tid = jwt.getClaim("tenantId");
        if (tid == null || tid.isBlank())
            throw new ForbiddenException("Missing tenantId — onboarding not complete");
        try { return UUID.fromString(tid); }
        catch (IllegalArgumentException e) { throw new BadRequestException("Invalid tenantId format"); }
    }
}