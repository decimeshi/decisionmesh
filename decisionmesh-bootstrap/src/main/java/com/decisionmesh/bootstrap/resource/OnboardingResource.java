package com.decisionmesh.bootstrap.resource;

import com.decisionmesh.bootstrap.service.OnboardingService;
import io.quarkus.logging.Log;
import io.quarkus.security.Authenticated;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.HashMap;
import java.util.Map;

@Path("/api/onboard")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class OnboardingResource {

    @Inject JsonWebToken jwt;
    @Inject OnboardingService onboardingService;

    // =========================================================
    // Helpers — JWT claim extraction
    // =========================================================

    private String getSub()   { return jwt.getSubject(); }
    private String getEmail() { return jwt.getClaim("email"); }
    private String getName()  { return jwt.getClaim("name"); }

    private boolean isInvalidUser(String sub) {
        return sub == null || sub.isBlank();
    }

    // =========================================================
    // GET /api/onboard/me
    //
    // Called on app load to check onboarding status.
    // Upserts the user in the DB (idempotent).
    // Returns { externalId, email, name, tenantId, onboarded }.
    // =========================================================

    @GET
    @Path("/me")
    public Uni<Response> me() {

        String sub = getSub();

        if (isInvalidUser(sub)) {
            return Uni.createFrom().item(
                    Response.status(Response.Status.UNAUTHORIZED).build());
        }

        return onboardingService.provisionUser(sub, getEmail(), getName())
                .map(tenantId -> {
                    // HashMap is required — Map.of() rejects null values
                    Map<String, Object> body = new HashMap<>();
                    body.put("externalId", sub);
                    body.put("email",      getEmail() != null ? getEmail() : "");
                    body.put("name",       getName()  != null ? getName()  : "");
                    body.put("tenantId",   tenantId   != null ? tenantId.toString() : null);
                    body.put("onboarded",  tenantId   != null);
                    return Response.ok(body).build();
                })
                .onFailure().recoverWithItem(e -> {
                    Log.errorf("[OnboardingResource] me() failed for %s: %s",
                            sub, e.getMessage());
                    return Response.serverError().build();
                });
    }

    // =========================================================
    // POST /api/onboard/ensure
    //
    // DB guard — called once after every OIDC callback.
    // Guarantees the user row exists even if the Zitadel webhook
    // was not delivered (local dev, tunnel down, network error).
    //
    // WHY a request body for email:
    //   Zitadel does not include the email claim in the access token
    //   by default (only in the ID token / userinfo endpoint).
    //   Quarkus OIDC reads claims from the access token, so
    //   jwt.getClaim("email") returns null. The frontend reads email
    //   from auth.user.profile (userinfo) and passes it in the body
    //   so we always have it for DB inserts.
    //
    // Response:
    //   { userId, tenantId | null, onboarded, email }
    //   onboarded = false  → frontend redirects to /onboarding
    //   onboarded = true   → frontend proceeds to /dashboard
    // =========================================================

    public static class EnsureRequest {
        public String email;
        public String name;
    }

    @POST
    @Path("/ensure")
    public Uni<Response> ensure(EnsureRequest req) {

        String sub = getSub();

        if (isInvalidUser(sub)) {
            return Uni.createFrom().item(
                    Response.status(Response.Status.UNAUTHORIZED).build());
        }

        // Prefer body fields (from userinfo via frontend) over JWT claims,
        // since email is absent from Zitadel access tokens by default.
        String email = (req != null && req.email != null && !req.email.isBlank())
                ? req.email
                : getEmail();  // fallback to JWT claim (may be null)

        String name = (req != null && req.name != null && !req.name.isBlank())
                ? req.name
                : getName();

        return onboardingService.ensureUser(sub, email, name)
                .map(user -> {
                    Map<String, Object> body = new HashMap<>();
                    body.put("userId",    user.userId.toString());
                    body.put("tenantId",  user.tenantId != null ? user.tenantId.toString() : null);
                    body.put("onboarded", user.tenantId != null);
                    body.put("email",     user.email != null ? user.email : "");
                    return Response.ok(body).build();
                })
                .onFailure().recoverWithItem(e -> {
                    Log.errorf("[OnboardingResource] ensure() failed for %s: %s",
                            sub, e.getMessage());
                    return Response.serverError()
                            .entity(Map.of("message", "Failed to ensure user"))
                            .build();
                });
    }

    // =========================================================
    // POST /api/onboard/setup-tenant
    //
    // Called after the user answers the onboarding question
    // (Individual vs Organisation). Creates tenant, org, default
    // project, and assigns the tenant_user role in Zitadel.
    //
    // Response includes requiresTokenRefresh so the frontend
    // knows to call forceTokenRefresh() — the role was just
    // assigned and the existing token does not carry it yet.
    //
    // NOTE: if the Zitadel postCreation webhook ran correctly,
    // the role was already assigned before the first login and
    // requiresTokenRefresh will be false here. The flag is only
    // true when this endpoint is the FIRST time the role is set
    // (i.e. webhook was skipped — local dev without a tunnel).
    // =========================================================

    @POST
    @Path("/setup-tenant")
    public Uni<Response> setupTenant(SetupTenantRequest req) {

        String sub = getSub();

        if (isInvalidUser(sub)) {
            return Uni.createFrom().item(
                    Response.status(Response.Status.UNAUTHORIZED).build());
        }

        if (req == null || req.accountType == null) {
            return badRequest("accountType is required");
        }

        if (!req.accountType.equals("INDIVIDUAL") && !req.accountType.equals("ORGANIZATION")) {
            return badRequest("accountType must be INDIVIDUAL or ORGANIZATION");
        }

        if ("ORGANIZATION".equals(req.accountType)
                && (req.companyName == null || req.companyName.isBlank())) {
            return badRequest("companyName is required for ORGANIZATION");
        }

        return onboardingService.setupTenant(sub, getName(), req)
                .map(result -> Response.status(Response.Status.CREATED).entity(Map.of(
                        "message",              "Tenant setup complete",
                        "tenantId",             result.tenantId().toString(),
                        "accountType",          req.accountType,
                        // Frontend must call forceTokenRefresh() when this is true
                        "requiresTokenRefresh", result.requiresTokenRefresh()
                )).build())
                .onFailure().recoverWithItem(e -> handleSetupError(e, sub));
    }

    // =========================================================
    // POST /api/onboard/repair-attributes
    //
    // Recovers users whose Zitadel role or metadata is missing.
    // After this succeeds the frontend must call forceTokenRefresh()
    // to obtain a token that carries the newly assigned role.
    // =========================================================

    @POST
    @Path("/repair-attributes")
    public Uni<Response> repairAttributes() {

        String sub = getSub();

        if (isInvalidUser(sub)) {
            return Uni.createFrom().item(
                    Response.status(Response.Status.UNAUTHORIZED).build());
        }

        return onboardingService.repairZitadelMetadata(sub)
                .replaceWith(Response.ok(Map.of(
                        "message", "Attributes repaired successfully",
                        "sub",     sub
                )).build())
                .onFailure().recoverWithItem(e -> {
                    Log.errorf("[OnboardingResource] repair failed for %s: %s",
                            sub, e.getMessage());
                    return Response.serverError().entity(Map.of(
                            "message", e.getMessage() != null ? e.getMessage() : "Repair failed"
                    )).build();
                });
    }

    // =========================================================
    // Helpers
    // =========================================================

    private Uni<Response> badRequest(String msg) {
        return Uni.createFrom().item(
                Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("message", msg))
                        .build()
        );
    }

    private Response handleSetupError(Throwable e, String sub) {
        Log.errorf("[OnboardingResource] setupTenant failed for %s: %s",
                sub, e.getMessage());

        String msg = e.getMessage();

        if (msg != null && msg.contains("already")) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("message", msg))
                    .build();
        }

        return Response.serverError()
                .entity(Map.of("message", "Failed to set up tenant"))
                .build();
    }

    // =========================================================
    // DTO
    // =========================================================

    public static class SetupTenantRequest {
        public String accountType;   // INDIVIDUAL | ORGANIZATION
        public String companyName;
        public String companySize;
    }
}