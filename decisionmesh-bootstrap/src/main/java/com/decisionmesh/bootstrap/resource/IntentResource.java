package com.decisionmesh.bootstrap.resource;

import com.decisionmesh.application.service.ControlPlaneOrchestrator;
import com.decisionmesh.bootstrap.dto.IntentEventResponse;
import com.decisionmesh.bootstrap.service.IntentService;
import com.decisionmesh.bootstrap.dto.IntentResponse;
import com.decisionmesh.domain.intent.Intent;

import com.decisionmesh.llm.dto.PolicyEvaluationRepository;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import com.decisionmesh.contracts.security.context.TenantContext;
import io.quarkus.logging.Log;
import io.smallrye.common.annotation.NonBlocking;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.List;
import java.util.UUID;

@Path("/api/intents")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"sys_admin", "tenant_admin", "tenant_user"})
public class IntentResource {

    @Inject JsonWebToken             jwt;
    @Inject TenantContext            tenantContext;
    @Inject ControlPlaneOrchestrator orchestrator;
    @Inject IntentService            intentService;
    @Inject
    PolicyEvaluationRepository policyEvaluationRepository;

    // ── Submit ────────────────────────────────────────────────────────────────

    @POST
    @NonBlocking
    @WithSession
    public Uni<UUID> submit(Intent intent,
                            @HeaderParam("Idempotency-Key") String idempotencyKey) {
        if (intent == null)
            throw new BadRequestException("Intent body required");
        if (idempotencyKey == null || idempotencyKey.isBlank())
            throw new BadRequestException("Missing Idempotency-Key header");

        UUID tenantId = getTenantId();
        UUID userId   = getUserId();

        intent.setTenantId(tenantId);
        intent.setUserId(userId);

        Log.infof("Intent submission: id=%s tenant=%s type=%s",

                intent.getId(), tenantId, intent.getIntentType());

        return orchestrator.submit(intent, tenantId, idempotencyKey, intent.getIntentType());
    }
    // ── Get single ────────────────────────────────────────────────────────────

    @GET
    @Path("/{intentId}")
    @WithSession
    @NonBlocking
    public Uni<Response> get(@PathParam("intentId") UUID intentId) {
        return intentService.getIntent(getTenantId(), intentId)
                .onItem().ifNotNull().transform(dto -> Response.ok(dto).build())
                .onItem().ifNull().continueWith(() ->
                        Response.status(Response.Status.NOT_FOUND)
                                .entity("{\"error\":\"Intent not found: " + intentId + "\"}")
                                .build());
    }

    // ── Get events ────────────────────────────────────────────────────────────

    @GET
    @Path("/{intentId}/events")
    @WithSession
    @NonBlocking
    public Uni<List<IntentEventResponse>> getEvents(@PathParam("intentId") UUID intentId) {
        return intentService.getIntentEvents(getTenantId(), intentId);
    }

    // ── List ──────────────────────────────────────────────────────────────────

    @GET
    @WithSession
    public Uni<IntentResponse> list(
            @QueryParam("page")  @DefaultValue("0")              int    page,
            @QueryParam("size")  @DefaultValue("20")             int    size,
            @QueryParam("sort")  @DefaultValue("createdAt,desc") String sort,
            @QueryParam("phase")                                 String phase) {

        String[] parts    = sort.split(",", 2);
        String sortField  = parts[0].trim();
        String sortDir    = parts.length > 1 ? parts[1].trim() : "desc";
        int    clampedSize = Math.min(Math.max(size, 1), 100);
        String phaseFilter = (phase != null && !phase.isBlank()) ? phase.toUpperCase() : null;

        return intentService.getIntents(
                getTenantId(), phaseFilter, sortField, sortDir, page, clampedSize);
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    @DELETE
    @Path("/{intentId}")
    @WithTransaction
    @NonBlocking
    public Uni<Response> delete(@PathParam("intentId") UUID intentId) {
        return intentService.deleteIntent(getTenantId(), intentId)
                .map(deleted -> deleted
                        ? Response.noContent().build()
                        : Response.status(Response.Status.NOT_FOUND).build())
                .onFailure().recoverWithItem(ex -> Response.serverError().build());
    }
    // ── Get executions by intent — Decision Output card ───────────────────────
    // No @WithSession — uses plain JDBC via ExecutionRecordRepository.findByIntentId()
    // which runs on the worker pool. @WithSession would open a Hibernate Reactive
    // session on the event loop, then runSubscriptionOn moves to worker thread,
    // causing HR000069 when the session tries to close on the wrong thread.

    @GET
    @Path("/{intentId}/executions")
    @NonBlocking
    public Uni<Response> getExecutionsByIntent(@PathParam("intentId") UUID intentId) {
        return intentService.getExecutionsByIntent(getTenantId(), intentId)
                .map(list -> Response.ok(list).build());
    }

    // ── Get policy evaluations — PolicyOutcomeCard ────────────────────────────

    @GET
    @Path("/{intentId}/policy-evaluations")
    @NonBlocking
    public Uni<Response> getPolicyEvaluations(@PathParam("intentId") UUID intentId) {
        return policyEvaluationRepository.findByIntent(getTenantId(), intentId)
                .map(list -> Response.ok(list).build())
                .onFailure().recoverWithItem(ex ->
                        Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                                .entity("{\"error\":\"" + ex.getMessage() + "\"}")
                                .build());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private UUID getTenantId() {
        // ── 1. TenantContext (DB fallback set by TenantContextFilter) ─────────
        UUID ctxTid = tenantContext.getTenantId();
        if (ctxTid != null) return ctxTid;
        // ── 2. JWT claim (set by Zitadel Action) ─────────────────────────────
        String tid = jwt.getClaim("tenantId");
        if (tid == null || tid.isBlank()) throw new ForbiddenException("Missing tenantId — onboarding not complete");
        try { return UUID.fromString(tid); }
        catch (IllegalArgumentException e) { throw new BadRequestException("Invalid tenantId format"); }
    }

    // userId is written to Keycloak by OnboardingService.writeKeycloakTenantId()
    // and mapped via the 'userId' User Attribute mapper on control-plane-web-dedicated scope
    private UUID getUserId() {
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