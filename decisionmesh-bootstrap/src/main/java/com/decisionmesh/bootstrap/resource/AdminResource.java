package com.decisionmesh.bootstrap.resource;

import com.decisionmesh.billing.model.CreditLedgerEntity;
import com.decisionmesh.bootstrap.service.DataRetentionService;
import com.decisionmesh.persistence.model.WebhookEventEntity;
import com.decisionmesh.billing.service.CreditLedgerService;
import com.decisionmesh.contracts.security.entity.UserEntity;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.quarkus.logging.Log;
import io.quarkus.security.Authenticated;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.constraints.*;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.eclipse.microprofile.openapi.annotations.Operation;
import com.decisionmesh.bootstrap.service.KafkaHealthService;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.lang.management.ManagementFactory;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * AdminResource — sys_admin-only management endpoints.
 *
 * All endpoints require the 'sys_admin' Zitadel role:
 *   urn:zitadel:iam:org:project:roles → { sys_admin: { orgId: domain } }
 *
 * ── User Management ──────────────────────────────────────────────────────────
 *   GET  /api/admin/users                    list all users (paginated, searchable)
 *   GET  /api/admin/users/{id}               user detail + credit balance
 *   POST /api/admin/users/{id}/suspend       set isActive = false
 *   POST /api/admin/users/{id}/activate      set isActive = true
 *   POST /api/admin/users/{id}/credits       grant or deduct credits (ADMIN_ADJUSTMENT)
 *
 * ── Credit Ledger ─────────────────────────────────────────────────────────────
 *   GET  /api/admin/credits                  all transactions (filtered, paginated)
 *   GET  /api/admin/credits/stats            aggregate stats across all orgs
 *
 * ── Webhook Event Log ─────────────────────────────────────────────────────────
 *   GET  /api/admin/webhooks                 all webhook events (filtered, paginated)
 *   POST /api/admin/webhooks/{id}/replay     replay a failed event
 *
 * ── System Health ────────────────────────────────────────────────────────────
 *   GET  /api/admin/health                   JVM + DB + queue depth metrics
 */
@Path("/api/admin")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
@Tag(name = "Admin")
public class AdminResource {

    @Inject
    CreditLedgerService creditLedgerService;

    @Inject
    KafkaHealthService kafkaHealthService;

    @Inject
    JsonWebToken jwt;

    @Inject DataRetentionService dataRetentionService;

    // ── Role check — mirrors frontend hasSysAdminRole() ───────────────────────
    // Zitadel puts roles as object keys under:
    //   urn:zitadel:iam:org:project:roles → { "sys_admin": { orgId: domain } }
    // Quarkus @RolesAllowed reads realm_access.roles (Keycloak) and misses this.
    private static final String ROLE         = "sys_admin";
    private static final String ZITADEL_CLAIM = "urn:zitadel:iam:org:project:roles";

    @GET
    @Path("/retention/dry-run")
    @RolesAllowed("admin")
    public Response retentionDryRun() {
        String result = dataRetentionService.dryRun();
        return Response.ok(result).build();
    }

    private boolean isSysAdmin() {
        // 1. Zitadel project roles (your setup)
        try {
            var zitadelRoles = jwt.getClaim(ZITADEL_CLAIM);
            if (zitadelRoles instanceof java.util.Map<?, ?> map && map.containsKey(ROLE)) return true;
        } catch (Exception ignored) {}

        // 2. Keycloak realm roles fallback
        try {
            var realmAccess = jwt.<java.util.Map<String, Object>>getClaim("realm_access");
            if (realmAccess != null) {
                var roles = realmAccess.get("roles");
                if (roles instanceof java.util.Collection<?> c && c.contains(ROLE)) return true;
            }
        } catch (Exception ignored) {}

        // 3. Top-level groups/roles array fallback
        try {
            var groups = jwt.<java.util.Collection<String>>claim("groups").orElse(null);
            if (groups != null && groups.contains(ROLE)) return true;
        } catch (Exception ignored) {}

        return false;
    }

    /** Emits null if admin, emits 403 Response if not — use with flatMap */
    private Uni<Response> guardAdmin() {
        if (isSysAdmin()) return Uni.createFrom().nullItem();
        Log.warnf("[Admin] Unauthorized access attempt — sub=%s", jwt.getSubject());
        return Uni.createFrom().item(
                Response.status(403)
                        .entity(new ErrorResponse("Access denied — sys_admin role required"))
                        .build()
        );
    }

    /** Helper: run admin-guarded logic, short-circuit with 403 if not admin */
    private Uni<Response> withAdmin(java.util.function.Supplier<Uni<Response>> action) {
        if (!isSysAdmin()) {
            Log.warnf("[Admin] Unauthorized — sub=%s", jwt.getSubject());
            return Uni.createFrom().item(
                    Response.status(403)
                            .entity(new ErrorResponse("Access denied — sys_admin role required"))
                            .build());
        }
        return action.get();
    }

    // =========================================================================
    // USER MANAGEMENT
    // =========================================================================

    /**
     * GET /api/admin/users
     * Lists all users with optional search and pagination.
     *
     * Query params:
     *   search  — partial match on email or name
     *   active  — true | false (omit for all)
     *   page    — 0-based page index (default 0)
     *   size    — page size (default 20, max 100)
     */
    @GET
    @Path("/users")
    @WithSession
    @Operation(summary = "List all users")
    public Uni<Response> listUsers(
            @QueryParam("search") String search,
            @QueryParam("active") Boolean active,
            @QueryParam("page")   @DefaultValue("0")  int page,
            @QueryParam("size")   @DefaultValue("20") int size) {

        return withAdmin(() -> {
            int safeSize = Math.min(Math.max(1, size), 100);
            int safePage = Math.max(0, page);
            StringBuilder query  = new StringBuilder("1=1");
            Map<String, Object> params = new HashMap<>();
            if (search != null && !search.isBlank()) {
                query.append(" and (lower(email) like :search or lower(name) like :search)");
                params.put("search", "%" + search.toLowerCase().strip() + "%");
            }
            if (active != null) {
                query.append(" and isActive = :active");
                params.put("active", active);
            }
            return UserEntity.<UserEntity>find(query + " order by createdAt desc", params)
                    .page(safePage, safeSize)
                    .list()
                    .flatMap(rawList -> {
                        List<UserEntity> users = rawList.stream()
                                .map(e -> (UserEntity) e)
                                .toList();
                        if (users.isEmpty()) return Uni.createFrom().item(Response.ok(List.of()).build());

                        // Sequential balance lookups — Hibernate Reactive does not
                        // allow concurrent queries on the same session (causes
                        // "Illegal pop() with non-matching JdbcValuesSourceProcessingState")
                        Uni<List<Map<String, Object>>> chain =
                                Uni.createFrom().item(new java.util.ArrayList<>());
                        for (UserEntity u : users) {
                            chain = chain.flatMap(acc ->
                                    creditLedgerService
                                            .getBalance(u.tenantId != null ? u.tenantId : u.userId)
                                            .map(balance -> {
                                                acc.add(userToMap(u, balance));
                                                return acc;
                                            })
                            );
                        }
                        return chain.map(list -> Response.ok(list).build());
                    })
                    .onFailure().invoke(ex -> Log.errorf(ex, "[Admin] listUsers failed"))
                    .onFailure().recoverWithItem(
                            Response.serverError().entity(new ErrorResponse("Failed to load users")).build());
        });
    }

    /**
     * GET /api/admin/users/{id}
     * Returns full user detail including credit balance.
     */
    @GET
    @Path("/users/{id}")
    @WithSession
    @Operation(summary = "Get user detail")
    public Uni<Response> getUser(@PathParam("id") UUID userId) {
        return withAdmin(() ->
                UserEntity.<UserEntity>find("userId", userId).firstResult()
                        .flatMap(rawUser -> {
                            UserEntity user = (UserEntity) rawUser;
                            if (user == null) return Uni.createFrom().item(
                                    Response.status(404).entity(new ErrorResponse("User not found")).build());
                            UUID orgId = user.tenantId != null ? user.tenantId : user.userId;
                            return creditLedgerService.getBalance(orgId)
                                    .map(balance -> Response.ok(userToMap(user, balance)).build());
                        })
                        .onFailure().invoke(ex -> Log.errorf(ex, "[Admin] getUser failed userId=%s", userId))
                        .onFailure().recoverWithItem(
                                Response.serverError().entity(new ErrorResponse("Failed to load user")).build())
        );
    }

    /**
     * POST /api/admin/users/{id}/suspend
     * Sets isActive = false. User can no longer submit feedback or access protected endpoints.
     */
    @POST
    @Path("/users/{id}/suspend")
    @WithTransaction
    @Operation(summary = "Suspend a user account")
    public Uni<Response> suspendUser(@PathParam("id") UUID userId, @Context SecurityContext sec) {
        return withAdmin(() -> setUserActive(userId, false, sec.getUserPrincipal().getName()));
    }

    @POST
    @Path("/users/{id}/activate")
    @WithTransaction
    @Operation(summary = "Activate a suspended user account")
    public Uni<Response> activateUser(@PathParam("id") UUID userId, @Context SecurityContext sec) {
        return withAdmin(() -> setUserActive(userId, true, sec.getUserPrincipal().getName()));
    }

    /**
     * POST /api/admin/users/{id}/credits
     * Manually grant or deduct credits for a user's org.
     *
     * Body: { "amount": 500, "note": "Trial extension" }
     * Positive amount = grant, negative = deduct.
     */
    @POST
    @Path("/users/{id}/credits")
    @WithTransaction
    @Operation(summary = "Grant or deduct credits for a user")
    public Uni<Response> adjustCredits(@PathParam("id") UUID userId,
                                       CreditAdjustRequest req,
                                       @Context SecurityContext sec) {
        if (req == null || req.amount == 0)
            return Uni.createFrom().item(
                    Response.status(400).entity(new ErrorResponse("amount must be non-zero")).build());

        return withAdmin(() -> {
            String adminSub = sec.getUserPrincipal().getName();
            return UserEntity.<UserEntity>find("userId", userId).firstResult()
                    .flatMap(rawUser -> {
                        UserEntity user = (UserEntity) rawUser;
                        if (user == null) return Uni.createFrom().item(
                                Response.status(404).entity(new ErrorResponse("User not found")).build());
                        if (user.tenantId == null) return Uni.createFrom().item(
                                Response.status(422).entity(new ErrorResponse("User has no tenant — onboarding incomplete")).build());
                        return creditLedgerService
                                .adminAdjustment(user.tenantId, req.amount, req.note, adminSub)
                                .flatMap(v -> creditLedgerService.getBalance(user.tenantId))
                                .map(newBalance -> {
                                    Log.infof("[Admin] Credits adjusted: userId=%s amount=%d newBalance=%d admin=%s",
                                            userId, req.amount, newBalance, adminSub);
                                    return Response.ok(Map.of(
                                            "userId",     userId,
                                            "orgId",      user.tenantId,
                                            "adjustment", req.amount,
                                            "newBalance", newBalance,
                                            "note",       req.note != null ? req.note : ""
                                    )).build();
                                });
                    })
                    .onFailure().invoke(ex -> Log.errorf(ex, "[Admin] adjustCredits failed userId=%s", userId))
                    .onFailure().recoverWithItem(
                            Response.serverError().entity(new ErrorResponse("Failed to adjust credits")).build());
        });
    }

    // =========================================================================
    // CREDIT LEDGER
    // =========================================================================

    /**
     * GET /api/admin/credits
     * Full credit ledger across all orgs with optional filters.
     *
     * Query params:
     *   orgId   — filter by specific org UUID
     *   reason  — REGISTRATION_GIFT | SUBSCRIPTION | PURCHASE | REFERRAL |
     *             INTENT_EXECUTION | RETRY | REFUND | ADMIN_ADJUSTMENT
     *   page    — 0-based (default 0)
     *   size    — page size (default 50, max 200)
     */
    @GET
    @Path("/credits")
    @WithSession
    @Operation(summary = "List all credit transactions")
    public Uni<Response> listCredits(
            @QueryParam("orgId")  UUID   orgId,
            @QueryParam("reason") String reason,
            @QueryParam("page")   @DefaultValue("0")  int page,
            @QueryParam("size")   @DefaultValue("50") int size) {

        return withAdmin(() -> {
            int safeSize = Math.min(Math.max(1, size), 200);
            int safePage = Math.max(0, page);
            StringBuilder query  = new StringBuilder("1=1");
            Map<String, Object> params = new HashMap<>();
            if (orgId != null) { query.append(" and orgId = :orgId"); params.put("orgId", orgId); }
            if (reason != null && !reason.isBlank()) {
                query.append(" and reason = :reason");
                params.put("reason", reason.toUpperCase().strip());
            }
            return CreditLedgerEntity.<CreditLedgerEntity>find(query + " order by createdAt desc", params)
                    .page(safePage, safeSize)
                    .list()
                    .map(results -> Response.ok(results).build())
                    .onFailure().invoke(ex -> Log.errorf(ex, "[Admin] listCredits failed"))
                    .onFailure().recoverWithItem(
                            Response.serverError().entity(new ErrorResponse("Failed to load credit ledger")).build());
        });
    }

    /**
     * GET /api/admin/credits/stats
     * Aggregate credit stats across all orgs.
     * Returns total granted, total debited, net, breakdown by reason.
     */
    @GET
    @Path("/credits/stats")
    @WithSession
    @Operation(summary = "Credit ledger aggregate stats")
    public Uni<Response> creditStats() {
        return withAdmin(() ->
                CreditLedgerEntity.<CreditLedgerEntity>listAll()
                        .map(all -> {
                            long totalGranted = all.stream().filter(e -> e.amount > 0).mapToLong(e -> e.amount).sum();
                            long totalDebited = all.stream().filter(e -> e.amount < 0).mapToLong(e -> Math.abs(e.amount)).sum();
                            Map<String, Long> byReason = new LinkedHashMap<>();
                            all.forEach(e -> byReason.merge(e.reason, (long) e.amount, Long::sum));
                            return Response.ok(Map.of(
                                    "totalTransactions", all.size(),
                                    "totalGranted",      totalGranted,
                                    "totalDebited",      totalDebited,
                                    "netCredits",        totalGranted - totalDebited,
                                    "byReason",          byReason
                            )).build();
                        })
                        .onFailure().invoke(ex -> Log.errorf(ex, "[Admin] creditStats failed"))
                        .onFailure().recoverWithItem(
                                Response.serverError().entity(new ErrorResponse("Failed to compute stats")).build())
        );
    }

    // =========================================================================
    // WEBHOOK EVENT LOG
    // =========================================================================

    /**
     * GET /api/admin/webhooks
     * Lists webhook events with optional filters.
     *
     * Query params:
     *   gateway  — 'stripe' | 'razorpay'
     *   status   — 'received' | 'processed' | 'failed'
     *   page     — 0-based (default 0)
     *   size     — page size (default 50, max 200)
     *
     * NOTE: Requires BillingResource webhook handlers to call
     *   WebhookEventEntity.log(gateway, eventType, payload, orgId)
     *   on each incoming webhook. See WebhookEventEntity.java for details.
     */
    @GET
    @Path("/webhooks")
    @WithSession
    @Operation(summary = "List webhook events")
    public Uni<Response> listWebhooks(
            @QueryParam("gateway") String gateway,
            @QueryParam("status")  String status,
            @QueryParam("page")    @DefaultValue("0")  int page,
            @QueryParam("size")    @DefaultValue("50") int size) {

        return withAdmin(() -> {
            int safeSize = Math.min(Math.max(1, size), 200);
            int safePage = Math.max(0, page);
            StringBuilder query  = new StringBuilder("1=1");
            Map<String, Object> params = new HashMap<>();
            if (gateway != null && !gateway.isBlank()) {
                query.append(" and gateway = :gateway");
                params.put("gateway", gateway.toLowerCase().strip());
            }
            if (status != null && !status.isBlank()) {
                query.append(" and status = :status");
                params.put("status", status.toLowerCase().strip());
            }
            return WebhookEventEntity.<WebhookEventEntity>find(query + " order by receivedAt desc", params)
                    .page(safePage, safeSize)
                    .list()
                    .map(results -> Response.ok(results).build())
                    .onFailure().invoke(ex -> Log.errorf(ex, "[Admin] listWebhooks failed"))
                    .onFailure().recoverWithItem(
                            Response.serverError().entity(new ErrorResponse("Failed to load webhooks")).build());
        });
    }

    /**
     * POST /api/admin/webhooks/{id}/replay
     * Marks a failed webhook as received so it gets reprocessed.
     * Manual replay — the actual reprocessing logic depends on the event type
     * and must be implemented in BillingResource or a WebhookReplayService.
     */
    @POST
    @Path("/webhooks/{id}/replay")
    @WithTransaction
    @Operation(summary = "Replay a failed webhook event")
    public Uni<Response> replayWebhook(@PathParam("id") UUID eventId, @Context SecurityContext sec) {
        return withAdmin(() ->
                WebhookEventEntity.<WebhookEventEntity>findById(eventId)
                        .flatMap(event -> {
                            if (event == null) return Uni.createFrom().item(
                                    Response.status(404).entity(new ErrorResponse("Webhook event not found")).build());
                            if (!"failed".equals(event.status)) return Uni.createFrom().item(
                                    Response.status(422).entity(new ErrorResponse(
                                            "Only failed events can be replayed — status is: " + event.status)).build());
                            event.status      = "received";
                            event.error       = null;
                            event.processedAt = null;
                            return event.<WebhookEventEntity>persist()
                                    .map(saved -> {
                                        Log.infof("[Admin] Webhook replay: id=%s gateway=%s type=%s admin=%s",
                                                eventId, saved.gateway, saved.eventType, sec.getUserPrincipal().getName());
                                        return Response.ok(Map.of(
                                                "id",        saved.id,
                                                "gateway",   saved.gateway,
                                                "eventType", saved.eventType,
                                                "status",    saved.status,
                                                "message",   "Event reset to 'received' — will be reprocessed"
                                        )).build();
                                    });
                        })
                        .onFailure().invoke(ex -> Log.errorf(ex, "[Admin] replayWebhook failed id=%s", eventId))
                        .onFailure().recoverWithItem(
                                Response.serverError().entity(new ErrorResponse("Failed to replay webhook")).build())
        );
    }

    @GET
    @Path("/health")
    @WithSession
    @Operation(summary = "System health metrics")
    public Uni<Response> health() {
        return withAdmin(() -> {
            Runtime rt       = Runtime.getRuntime();
            long usedMb      = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
            long totalMb     = rt.totalMemory() / (1024 * 1024);
            long maxMb       = rt.maxMemory()   / (1024 * 1024);
            long uptimeMs    = ManagementFactory.getRuntimeMXBean().getUptime();
            Map<String, Object> jvm = Map.of(
                    "usedMemoryMb",  usedMb,  "totalMemoryMb", totalMb,
                    "maxMemoryMb",   maxMb,   "memoryPct",     totalMb > 0 ? (usedMb * 100 / totalMb) : 0,
                    "uptimeMs",      uptimeMs, "uptimeMinutes", uptimeMs / 60_000,
                    "processors",    rt.availableProcessors()
            );
            // Kafka health is isolated with its own recovery — a Kafka failure
            // (broker down, topics missing, timeout) must NEVER wipe out the DB
            // data that was already collected. The DB chain runs first; Kafka is
            // chained last so its failure recovery fires before the outer onFailure.
            Uni<Map<String, Object>> kafkaUni = kafkaHealthService.getHealth()
                    .onFailure().recoverWithItem(ex -> {
                        Log.warnf("[Admin] Kafka health probe failed: %s", ex.getMessage());
                        return new LinkedHashMap<>(Map.of(
                                "status", "down",
                                "error",  ex.getMessage() != null ? ex.getMessage() : "unknown error"
                        ));
                    });

            return UserEntity.count()
                    .flatMap(userCount -> WebhookEventEntity.count("status", "failed")
                            .flatMap(failedWebhooks -> CreditLedgerEntity.count()
                                    .flatMap(ledgerCount -> kafkaUni
                                            .map(kafkaHealth -> {
                                                Map<String, Object> health = new LinkedHashMap<>();
                                                health.put("status",    "up");
                                                health.put("timestamp", OffsetDateTime.now().toString());
                                                health.put("jvm",       jvm);
                                                health.put("database",  Map.of(
                                                        "status", "up", "totalUsers", userCount,
                                                        "totalLedgerRows", ledgerCount, "failedWebhooks", failedWebhooks));
                                                health.put("kafka",     kafkaHealth);
                                                return (Response) Response.ok(health).build();
                                            }))))
                    .onFailure().invoke(ex -> Log.errorf(ex, "[Admin] health check failed"))
                    .onFailure().recoverWithItem(() ->
                            (Response) Response.status(503).entity(Map.of(
                                    "status",    "degraded",
                                    "timestamp", OffsetDateTime.now().toString(),
                                    "jvm",        jvm,
                                    "database",   Map.of("status", "down", "note", "DB query failed")
                            )).build()
                    );
        });
    }

    // =========================================================================
    // PRIVATE HELPERS
    // =========================================================================

    private Uni<Response> setUserActive(UUID userId, boolean active, String adminSub) {
        return UserEntity.<UserEntity>find("userId", userId).firstResult()
                .flatMap(rawUser -> {
                    UserEntity user = (UserEntity) rawUser;
                    if (user == null) {
                        return Uni.createFrom().item(
                                Response.status(404).entity(new ErrorResponse("User not found")).build());
                    }
                    if (user.isActive == active) {
                        String state = active ? "active" : "suspended";
                        return Uni.createFrom().item(
                                Response.status(422)
                                        .entity(new ErrorResponse("User is already " + state))
                                        .build());
                    }
                    user.isActive = active;
                    return user.<UserEntity>persist()
                            .map(saved -> {
                                Log.infof("[Admin] User %s: userId=%s admin=%s",
                                        active ? "activated" : "suspended", userId, adminSub);
                                return Response.ok(Map.of(
                                        "userId",   userId,
                                        "isActive", saved.isActive,
                                        "email",    saved.email != null ? saved.email : ""
                                )).build();
                            });
                })
                .onFailure().invoke(ex ->
                        Log.errorf(ex, "[Admin] setUserActive(%s) failed userId=%s", active, userId))
                .onFailure().recoverWithItem(
                        Response.serverError().entity(new ErrorResponse("Failed to update user")).build());
    }

    private Map<String, Object> userToMap(UserEntity u, Long balance) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("userId",       u.userId);
        map.put("tenantId",     u.tenantId);
        map.put("email",        u.email);
        map.put("name",         u.name);
        map.put("isActive",     u.isActive);
        map.put("creditBalance",balance != null ? balance : 0L);
        map.put("createdAt",    u.createdAt);
        map.put("updatedAt",    u.updatedAt);
        return map;
    }

    // =========================================================================
    // DTOs
    // =========================================================================

    public static class CreditAdjustRequest {
        /** Positive = grant, negative = deduct. Must be non-zero. */
        public int    amount;
        /** Optional reason shown in ledger referenceId */
        public String note;
    }

    public record ErrorResponse(String error) {}
}