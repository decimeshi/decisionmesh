package com.decisionmesh.bootstrap.resource;

import com.decisionmesh.contracts.security.context.TenantContext;
import com.decisionmesh.persistence.entity.IntentEntity;
import com.decisionmesh.persistence.repository.IntentRepository;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.time.Instant;
import java.util.*;

/**
 * Human Review Queue — GET pending decisions, POST approve/reject.
 *
 * Intents appear here when their policy has requireHumanReview=true
 * and they are not yet terminal.
 *
 * Approve → phase=COMPLETED, satisfactionState=SATISFIED
 * Reject  → phase=COMPLETED, satisfactionState=VIOLATED
 */
@Path("/api/review-queue")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ReviewQueueResource {

    @Inject IntentRepository intentRepository;
    @Inject TenantContext    tenantContext;
    @Inject JsonWebToken     jwt;

    // ── GET /api/review-queue ─────────────────────────────────────────────────
    @GET
    public Uni<List<ReviewItem>> list(
            @QueryParam("page") @DefaultValue("0")  int page,
            @QueryParam("size") @DefaultValue("20") int size) {

        UUID tenantId = tenantId();
        Log.debugf("[ReviewQueue] list: tenant=%s page=%d size=%d", tenantId, page, size);

        return intentRepository.findPageByTenant(
                tenantId, null, "createdAt", "desc", page, size)
            .map(entities -> entities.stream()
                .filter(this::requiresReview)
                .map(this::toReviewItem)
                .toList());
    }

    // ── POST /api/review-queue/{id}/approve ──────────────────────────────────
    @POST
    @Path("/{intentId}/approve")
    public Uni<ReviewResult> approve(@PathParam("intentId") UUID intentId,
                                     ReviewAction body) {
        UUID   tenantId = tenantId();
        String reviewer = reviewerId();
        String note     = body != null && body.note != null ? body.note : "";

        Log.infof("[ReviewQueue] APPROVE: intent=%s tenant=%s reviewer=%s",
                intentId, tenantId, reviewer);

        return intentRepository.findByIdAndTenant(intentId, tenantId)
            .onItem().ifNull().failWith(() ->
                new NotFoundException("Intent not found: " + intentId))
            .flatMap(entity -> {
                entity.phase             = "COMPLETED";
                entity.satisfactionState = "SATISFIED";
                entity.terminal          = true;
                return intentRepository.persist(entity);
            })
            .map(v -> new ReviewResult(
                intentId.toString(), "APPROVED", reviewer, note,
                Instant.now().toString(), "Decision approved — audit trail updated"));
    }

    // ── POST /api/review-queue/{id}/reject ───────────────────────────────────
    @POST
    @Path("/{intentId}/reject")
    public Uni<ReviewResult> reject(@PathParam("intentId") UUID intentId,
                                    ReviewAction body) {
        UUID   tenantId = tenantId();
        String reviewer = reviewerId();
        String note     = body != null && body.note != null ? body.note : "";

        if (note.isBlank())
            throw new BadRequestException("Rejection reason is required");

        Log.infof("[ReviewQueue] REJECT: intent=%s tenant=%s reviewer=%s note=%s",
                intentId, tenantId, reviewer, note);

        return intentRepository.findByIdAndTenant(intentId, tenantId)
            .onItem().ifNull().failWith(() ->
                new NotFoundException("Intent not found: " + intentId))
            .flatMap(entity -> {
                entity.phase             = "COMPLETED";
                entity.satisfactionState = "VIOLATED";
                entity.terminal          = true;
                return intentRepository.persist(entity);
            })
            .map(v -> new ReviewResult(
                intentId.toString(), "REJECTED", reviewer, note,
                Instant.now().toString(), "Decision rejected — " + note));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean requiresReview(IntentEntity e) {
        if (e == null || e.terminal) return false;
        // Check payload JSON for requireHumanReview flag
        if (e.payload != null && e.payload.contains("\"requireHumanReview\":true")) {
            return true;
        }
        // Also show PENDING_REVIEW satisfaction state
        return "PENDING_REVIEW".equals(e.satisfactionState)
            || "REQUIRES_REVIEW".equals(e.satisfactionState);
    }

    private ReviewItem toReviewItem(IntentEntity e) {
        // Extract AI recommendation from payload if stored
        String rec       = extractFromPayload(e.payload, "\"recommendation\":\"", "\"");
        String reasoning = extractFromPayload(e.payload, "\"reasoning\":\"", "\"");
        String riskStr   = extractFromPayload(e.payload, "\"riskScore\":", ",");
        Double risk      = null;
        if (riskStr != null) {
            try { risk = Double.parseDouble(riskStr.trim()); } catch (Exception ignored) {}
        }

        return new ReviewItem(
            e.id != null ? e.id.toString() : null,
            e.id != null ? e.id.toString() : null,
            e.intentType,
            e.phase,
            e.satisfactionState,
            "PENDING",
            risk,
            rec,
            reasoning,
            e.createdAt != null ? e.createdAt.toString() : null,
            null
        );
    }

    /** Simple string extraction from JSON payload — avoids ObjectMapper dependency. */
    private String extractFromPayload(String payload, String startToken, String endToken) {
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
