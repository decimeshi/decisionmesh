package com.decisionmesh.bootstrap.resource;

import com.decisionmesh.persistence.entity.UserFeedback;
import com.decisionmesh.contracts.security.entity.UserEntity;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.quarkus.logging.Log;
import io.quarkus.security.Authenticated;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * FeedbackResource — user feedback ingestion and admin retrieval.
 *
 * POST /api/feedback   — authenticated users submit feedback (FeedbackWidget.jsx)
 * GET  /api/feedback   — sys_admin only — full feedback list with filters
 *
 * Path uses /api prefix to match Vite proxy:
 *   proxy: { '/api': { target: 'http://localhost:8080' } }
 *
 * Entity: UserFeedback (com.decisionmesh.bootstrap.entity) — extracted to
 * its own file for correct Quarkus build-time Hibernate scanning.
 */
@Path("/api/admin/feedback")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
@Tag(name = "Feedback")
public class FeedbackResource {

    private static final Set<String> VALID_CATEGORIES =
            Set.of("bug", "feature", "billing", "general");

    private static final String ZITADEL_CLAIM = "urn:zitadel:iam:org:project:roles";
    private static final String ROLE           = "sys_admin";

    @Inject
    JsonWebToken jwt;

    // Mirrors hasSysAdminRole() in SysAdminRoute.jsx — handles Zitadel JWT structure
    private boolean isSysAdmin() {
        try {
            var zitadel = jwt.getClaim(ZITADEL_CLAIM);
            if (zitadel instanceof java.util.Map<?, ?> m && m.containsKey(ROLE)) return true;
        } catch (Exception ignored) {}
        try {
            var realm = jwt.<java.util.Map<String, Object>>getClaim("realm_access");
            if (realm != null) {
                var roles = realm.get("roles");
                if (roles instanceof java.util.Collection<?> c && c.contains(ROLE)) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    // ── Request DTO ───────────────────────────────────────────────────────────

    public static class FeedbackRequest {

        @NotNull(message = "rating is required")
        @Min(value = 1, message = "rating must be at least 1")
        @Max(value = 5, message = "rating must be at most 5")
        public Integer rating;

        @NotBlank(message = "category is required")
        public String category;

        @Size(max = 1000, message = "comment must be under 1000 characters")
        public String comment;

        @Size(max = 255, message = "page must be under 255 characters")
        public String page;

        @Size(max = 512, message = "userAgent must be under 512 characters")
        public String userAgent;
    }

    // ── POST /api/feedback ────────────────────────────────────────────────────

    @POST
    @WithTransaction
    @Operation(summary = "Submit user feedback")
    public Uni<Response> submit(@Valid FeedbackRequest req,
                                @Context SecurityContext sec) {

        if (!VALID_CATEGORIES.contains(req.category)) {
            return Uni.createFrom().item(
                Response.status(400)
                    .entity(new ErrorResponse(
                        "Invalid category: " + req.category
                        + ". Allowed: bug, feature, billing, general"))
                    .build()
            );
        }

        String sub = sec.getUserPrincipal().getName();

        return UserEntity.findByKeycloakSub(sub)
                .flatMap(user -> {

                    if (user == null) {
                        Log.warnf("[Feedback] No UserEntity found for sub=%s", sub);
                        return Uni.createFrom().item(
                            Response.status(401)
                                .entity(new ErrorResponse("User not found"))
                                .build()
                        );
                    }

                    if (!user.isActive) {
                        Log.warnf("[Feedback] Suspended user attempted feedback: userId=%s",
                                user.userId);
                        return Uni.createFrom().item(
                            Response.status(403)
                                .entity(new ErrorResponse("Account is suspended"))
                                .build()
                        );
                    }

                    UserFeedback feedback = new UserFeedback();
                    feedback.id        = UUID.randomUUID();
                    feedback.userId    = user.userId;
                    feedback.rating    = req.rating;
                    feedback.category  = req.category;
                    feedback.comment   = req.comment != null ? req.comment.strip() : null;
                    feedback.page      = req.page;
                    feedback.userAgent = req.userAgent;

                    return feedback.<UserFeedback>persist()
                            .map(saved -> {
                                Log.infof("[Feedback] Saved id=%s userId=%s rating=%d category=%s page=%s",
                                        saved.id, user.userId, req.rating, req.category, req.page);
                                return Response.status(201)
                                        .entity(new SuccessResponse("Feedback received — thank you!"))
                                        .build();
                            });
                })
                .onFailure().invoke(ex ->
                        Log.errorf(ex, "[Feedback] Failed to persist feedback for sub=%s", sub))
                .onFailure().recoverWithItem(
                        Response.serverError()
                                .entity(new ErrorResponse("Failed to save feedback — please try again"))
                                .build()
                );
    }

    // ── GET /api/feedback (sys_admin only) ────────────────────────────────────

    @GET
    @WithSession
    @Operation(summary = "List all feedback (sys_admin only)")
    public Uni<Response> list(
            @QueryParam("category")  String  category,
            @QueryParam("minRating") Integer minRating,
            @QueryParam("limit")     @DefaultValue("50") int limit) {

        // Manual Zitadel role check — @RolesAllowed reads Keycloak realm_access
        // which doesn't exist in your Zitadel JWT
        if (!isSysAdmin()) {
            Log.warnf("[Feedback] Unauthorized GET attempt — sub=%s", jwt.getSubject());
            return Uni.createFrom().item(
                Response.status(403)
                    .entity(new ErrorResponse("Access denied — sys_admin role required"))
                    .build());
        }

        StringBuilder query = new StringBuilder("1=1");
        Map<String, Object> params = new HashMap<>();

        if (category != null && VALID_CATEGORIES.contains(category)) {
            query.append(" and category = :category");
            params.put("category", category);
        }
        if (minRating != null && minRating >= 1 && minRating <= 5) {
            query.append(" and rating >= :minRating");
            params.put("minRating", minRating);
        }

        int safeLimit = Math.min(Math.max(1, limit), 200);

        return UserFeedback
                .find(query + " order by createdAt desc", params)
                .page(0, safeLimit)
                .list()
                .map(results -> Response.ok(results).build())
                .onFailure().invoke(ex ->
                        Log.errorf(ex, "[Feedback] Failed to query feedback list"))
                .onFailure().recoverWithItem(
                        Response.serverError()
                                .entity(new ErrorResponse("Failed to load feedback"))
                                .build()
                );
    }

    // ── Response types ────────────────────────────────────────────────────────

    public record SuccessResponse(String message) {}
    public record ErrorResponse(String error) {}
}
