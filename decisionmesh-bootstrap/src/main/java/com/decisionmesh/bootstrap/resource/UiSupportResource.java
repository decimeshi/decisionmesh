package com.decisionmesh.bootstrap.resource;

import com.decisionmesh.billing.service.CreditLedgerService;
import com.decisionmesh.contracts.security.context.TenantContext;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class UiSupportResource {

    // ── GET /api/credits/balance  ─────────────────────────────────────────────
    // ── GET /api/credits/ledger   ─────────────────────────────────────────────

    @Path("/api/credits")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"sys_admin", "tenant_admin", "tenant_user"})
    public static class CreditsResource {

        @Inject TenantContext          tenantContext;
        @Inject CreditLedgerService    creditLedgerService;

        @GET
        @Path("/balance")
        @WithSession
        public io.smallrye.mutiny.Uni<Map<String, Object>> balance() {
            UUID orgId = tenantContext.getTenantId();
            if (orgId == null) {
                return io.smallrye.mutiny.Uni.createFrom().item(Map.of(
                        "balance",           0,
                        "monthlyAllocation", 500,
                        "used",              0,
                        "plan",              "free"
                ));
            }
            return creditLedgerService.getBalance(orgId)
                    .map(bal -> {
                        long balance = bal == null ? 0L : bal;
                        long alloc   = 500L;
                        long used    = Math.max(0L, alloc - balance);
                        java.util.Map<String, Object> body = new java.util.HashMap<>();
                        body.put("balance",           balance);
                        body.put("monthlyAllocation", alloc);
                        body.put("used",              used);
                        body.put("plan",              "free");
                        return (Map<String, Object>) body;
                    });
        }

        @GET
        @Path("/ledger")
        public Map<String, Object> ledger(
                @QueryParam("page") @DefaultValue("0")  int page,
                @QueryParam("size") @DefaultValue("20") int size) {
            // TODO: wire to creditLedgerRepository.findByOrgId() once method is confirmed
            return Map.of(
                    "content",       List.of(),
                    "totalElements", 0L,
                    "totalPages",    0,
                    "number",        page,
                    "size",          size
            );
        }
    }

    // ── GET /api/org ──────────────────────────────────────────────────────────

    @Path("/api/org")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"sys_admin", "tenant_admin", "tenant_user"})
    public static class OrgResource {

        @Inject JsonWebToken  jwt;
        @Inject TenantContext tenantContext;

        @GET
        public Map<String, Object> getOrg() {
            // Prefer TenantContext (DB fallback) over JWT claim
            String tid = tenantContext.getTenantId() != null
                    ? tenantContext.getTenantId().toString()
                    : jwt.getClaim("tenantId");

            String email   = jwt.getClaim("email");
            String name    = (email != null && email.contains("@"))
                    ? email.split("@")[0] : "My Organisation";
            String orgName = Character.toUpperCase(name.charAt(0)) + name.substring(1);

            return Map.of(
                    "id",          tid != null ? tid : "",
                    "name",        orgName,
                    "plan",        "free",
                    "logoInitial", orgName.substring(0, 1).toUpperCase()
            );
        }
    }

    // ── GET /api/projects ─────────────────────────────────────────────────────

    @Path("/api/projects")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed({"sys_admin", "tenant_admin", "tenant_user"})
    public static class ProjectsResource {

        @Inject JsonWebToken  jwt;
        @Inject TenantContext tenantContext;

        @GET
        public List<Map<String, Object>> list() {
            // Prefer TenantContext (DB fallback) over JWT claim
            String tid = tenantContext.getTenantId() != null
                    ? tenantContext.getTenantId().toString()
                    : jwt.getClaim("tenantId");

            return List.of(Map.of(
                    "id",          tid != null ? tid : "",
                    "name",        "Default Project",
                    "environment", "Production",
                    "description", "Default project",
                    "isDefault",   true
            ));
        }

        @POST
        public Map<String, Object> create(Map<String, Object> body) {
            return Map.of(
                    "id",          UUID.randomUUID().toString(),
                    "name",        body.getOrDefault("name", "New Project"),
                    "environment", body.getOrDefault("environment", "Production"),
                    "description", body.getOrDefault("description", ""),
                    "isDefault",   false
            );
        }
    }
}