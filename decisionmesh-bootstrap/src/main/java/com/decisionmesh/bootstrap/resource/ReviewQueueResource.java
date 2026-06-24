package com.decisionmesh.bootstrap.resource;

import com.decisionmesh.bootstrap.service.AuditService;
import com.decisionmesh.contracts.security.context.TenantContext;
import com.decisionmesh.persistence.entity.IntentEntity;
import com.decisionmesh.persistence.repository.IntentRepository;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.time.Instant;
import java.util.*;

@Path("/api/review-queue")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ReviewQueueResource {

    public static final String ACTION_INTENT_APPROVED = "INTENT_APPROVED";
    public static final String ACTION_INTENT_REJECTED = "INTENT_REJECTED";

    @Inject IntentRepository intentRepository;
    @Inject AuditService     auditService;
    @Inject TenantContext    tenantContext;
    @Inject JsonWebToken     jwt;

    // ── GET /api/review-queue ─────────────────────────────────────────────────
    @GET
    @WithSession
    public Uni<List<ReviewItem>> list(
            @QueryParam("page") @DefaultValue("0")  int page,
            @QueryParam("size") @DefaultValue("50") int size) {

        UUID tenantId = tenantId();
        Log.debugf("[ReviewQueue] list: tenant=%s", tenantId);

        return intentRepository.findPageByTenant(
                        tenantId, null, "createdAt", "desc", page, size)
                .flatMap(entities -> {
                    List<IntentEntity> pending = entities.stream()
                            .filter(this::hasReviewFlag)
                            .toList();

                    if (pending.isEmpty()) return Uni.createFrom().item(List.of());

                    // Fetch responseText for each pending intent via reactive query
                    List<UUID> ids = pending.stream().map(e -> e.id).toList();

                    return fetchLatestResponseTexts(tenantId, ids)
                            .map(execMap -> pending.stream()
                                    .map(e -> toReviewItem(e, execMap.get(e.id)))
                                    .toList());
                });
    }

    // ── POST /api/review-queue/{id}/approve ──────────────────────────────────
    @POST
    @Path("/{intentId}/approve")
    @WithTransaction
    public Uni<ReviewResult> approve(@PathParam("intentId") UUID intentId,
                                     ReviewAction body) {
        UUID   tenantId = tenantId();
        String reviewer = reviewerId();
        String note     = body != null && body.note != null ? body.note : "";

        Log.infof("[ReviewQueue] APPROVE: intent=%s reviewer=%s", intentId, reviewer);

        return intentRepository.findByIdAndTenant(intentId, tenantId)
                .onItem().ifNull().failWith(() ->
                        new NotFoundException("Intent not found: " + intentId))
                .flatMap(entity -> {
                    entity.satisfactionState = "SATISFIED";
                    entity.phase             = "COMPLETED";
                    entity.terminal          = true;
                    return intentRepository.persist(entity);
                })
                .flatMap(v -> auditService.log(
                        tenantId, reviewer,
                        ACTION_INTENT_APPROVED,
                        "INTENT", intentId,
                        "INTENT", intentId.toString(),
                        "SUCCESS",
                        note != null && !note.isBlank()
                                ? "Human approved. Note: " + note
                                : "Human approved via Review Queue"
                ))
                .map(v -> new ReviewResult(
                        intentId.toString(), "APPROVED", reviewer, note,
                        Instant.now().toString(), "Decision approved — audit trail updated"));
    }

    // ── POST /api/review-queue/{id}/reject ───────────────────────────────────
    @POST
    @Path("/{intentId}/reject")
    @WithTransaction
    public Uni<ReviewResult> reject(@PathParam("intentId") UUID intentId,
                                    ReviewAction body) {
        UUID   tenantId = tenantId();
        String reviewer = reviewerId();
        String note     = body != null && body.note != null ? body.note : "";

        if (note.isBlank())
            throw new BadRequestException("Rejection reason is required");

        Log.infof("[ReviewQueue] REJECT: intent=%s reviewer=%s note=%s",
                intentId, reviewer, note);

        return intentRepository.findByIdAndTenant(intentId, tenantId)
                .onItem().ifNull().failWith(() ->
                        new NotFoundException("Intent not found: " + intentId))
                .flatMap(entity -> {
                    entity.satisfactionState = "VIOLATED";
                    entity.phase             = "COMPLETED";
                    entity.terminal          = true;
                    return intentRepository.persist(entity);
                })
                .flatMap(v -> auditService.log(
                        tenantId, reviewer,
                        ACTION_INTENT_REJECTED,
                        "INTENT", intentId,
                        "INTENT", intentId.toString(),
                        "SUCCESS",
                        "Human rejected. Reason: " + note
                ))
                .map(v -> new ReviewResult(
                        intentId.toString(), "REJECTED", reviewer, note,
                        Instant.now().toString(), "Decision rejected — " + note));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean hasReviewFlag(IntentEntity e) {
        if (e == null || e.payload == null) return false;
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(e.payload);
            com.fasterxml.jackson.databind.JsonNode constraints = root.path("constraints");
            if (constraints.isMissingNode()) return false;
            if (!constraints.path("requireHumanReview").asBoolean(false)) return false;
        } catch (Exception ex) {
            return false;
        }
        if (e.terminal && ("SATISFIED".equals(e.satisfactionState)
                || "VIOLATED".equals(e.satisfactionState))) {
            return false;
        }
        return true;
    }

    private String reviewStatus(IntentEntity e) {
        if (!e.terminal) return "PENDING";
        return switch (e.satisfactionState != null ? e.satisfactionState : "") {
            case "SATISFIED" -> "APPROVED";
            case "VIOLATED"  -> "REJECTED";
            default          -> "PENDING";
        };
    }

    /**
     * Reactive query — no blocking JDBC.
     * Fetches latest response_text per intent_id using DISTINCT ON.
     */
    private Uni<Map<UUID, String>> fetchLatestResponseTexts(UUID tenantId, List<UUID> intentIds) {
        if (intentIds == null || intentIds.isEmpty())
            return Uni.createFrom().item(Map.of());

        String sql = """
                SELECT DISTINCT ON (er.intent_id) er.intent_id, er.response_text
                FROM execution_records er
                WHERE er.tenant_id = :tenantId
                  AND er.intent_id IN :intentIds
                ORDER BY er.intent_id, er.executed_at DESC
                """;

        return io.quarkus.hibernate.reactive.panache.Panache.getSession()
                .flatMap(session -> session
                        .createNativeQuery(sql)
                        .setParameter("tenantId", tenantId)
                        .setParameter("intentIds", intentIds)
                        .getResultList()
                )
                .map(rows -> {
                    Map<UUID, String> map = new HashMap<>();
                    for (Object row : rows) {
                        Object[] cols = (Object[]) row;
                        if (cols[0] != null) {
                            map.put(UUID.fromString(cols[0].toString()),
                                    cols[1] != null ? cols[1].toString() : null);
                        }
                    }
                    return map;
                })
                .onFailure().recoverWithItem(ex -> {
                    Log.warnf(ex, "[ReviewQueue] fetchLatestResponseTexts failed");
                    return Map.of();
                });
    }

    private ReviewItem toReviewItem(IntentEntity e, String responseText) {
        String rec       = extractJson(responseText, "\"recommendation\":\"", "\"");
        String reasoning = extractJson(responseText, "\"reasoning\":\"", "\"");
        Double risk      = null;
        String riskStr   = extractJson(responseText, "\"riskScore\":", ",");
        if (riskStr == null) riskStr = extractJson(responseText, "\"riskScore\":", "}");
        if (riskStr != null) {
            try { risk = Double.parseDouble(riskStr.trim()); } catch (Exception ignored) {}
        }
        return new ReviewItem(
                e.id != null ? e.id.toString() : null,
                e.id != null ? e.id.toString() : null,
                e.intentType,
                e.phase,
                e.satisfactionState,
                reviewStatus(e),
                risk,
                rec,
                reasoning,
                responseText,
                e.createdAt != null ? e.createdAt.toString() : null,
                null
        );
    }

    private String extractJson(String payload, String startToken, String endToken) {
        if (payload == null) return null;
        int s = payload.indexOf(startToken);
        if (s < 0) return null;
        s += startToken.length();
        int end = payload.indexOf(endToken, s);
        if (end < 0) return null;
        return payload.substring(s, end);
    }

    private UUID tenantId() {
        UUID ctxTid = tenantContext.getTenantId();
        if (ctxTid != null) return ctxTid;
        String tid = jwt.getClaim("tenantId");
        if (tid == null || tid.isBlank())
            throw new ForbiddenException("Missing tenantId — onboarding not complete");
        try { return UUID.fromString(tid); }
        catch (IllegalArgumentException e) {
            throw new BadRequestException("Invalid tenantId format");
        }
    }

    private String reviewerId() {
        String sub   = jwt.getSubject();
        String email = jwt.getClaim("email");
        return sub != null ? sub : (email != null ? email : "unknown");
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    public record ReviewItem(
            String id,
            String intentId,
            String intentType,
            String phase,
            String satisfactionState,
            String reviewStatus,
            Double riskScore,
            String aiRecommendation,
            String reasoning,
            String responseText,
            String createdAt,
            String reviewedAt
    ) {}

    public record ReviewAction(String note) {}

    public record ReviewResult(
            String intentId,
            String action,
            String reviewedBy,
            String note,
            String reviewedAt,
            String message
    ) {}
}