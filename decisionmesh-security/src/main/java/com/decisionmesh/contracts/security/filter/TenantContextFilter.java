package com.decisionmesh.contracts.security.filter;

import com.decisionmesh.contracts.security.context.TenantContext;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

/**
 * Resolves tenantId and userId from the incoming request and sets TenantContext.
 *
 * Resolution order:
 *   1. API Key attributes  (set by PlatformKeyAuthenticationMechanism)
 *   2. JWT tenantId claim  (set by Zitadel Action after onboarding)
 *   3. JDBC DB fallback    (looks up user by sub using blocking JDBC)
 *                          ← Panache won't work here — no reactive session
 *                            in JAX-RS filter thread context
 */
@Provider
@Priority(Priorities.AUTHENTICATION + 10)
public class TenantContextFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(TenantContextFilter.class);

    private static final String SQL_FIND_TENANT =
            "SELECT tenant_id FROM users WHERE user_id = ?";

    @Inject SecurityIdentity securityIdentity;
    @Inject TenantContext    tenantContext;
    @Inject JsonWebToken     jwt;
    @Inject DataSource       dataSource;   // ← blocking JDBC, works in any thread

    @Override
    public void filter(ContainerRequestContext requestContext) {

        String path   = requestContext.getUriInfo().getPath();
        String method = requestContext.getMethod();

        // Skip OPTIONS, metrics, health
        if ("OPTIONS".equalsIgnoreCase(method)
                || path.contains("metrics")
                || path.contains("health")) {
            return;
        }

        // Skip anonymous
        if (securityIdentity.isAnonymous()) {
            return;
        }

        UUID tenantId = null;
        UUID userId   = null;

        // ── Path 1: API key ───────────────────────────────────────────────────
        Object tidAttr = securityIdentity.getAttribute("tenantId");
        Object uidAttr = securityIdentity.getAttribute("userId");
        if (tidAttr instanceof UUID tid && uidAttr instanceof UUID uid) {
            tenantId = tid;
            userId   = uid;
        }

        // ── Path 2: JWT tenantId claim ────────────────────────────────────────
        String subClaim = jwt.getSubject();
        if (tenantId == null) {
            String tidClaim = jwt.getClaim("tenantId");
            if (tidClaim != null && !tidClaim.isBlank()) {
                try {
                    tenantId = UUID.fromString(tidClaim);
                } catch (IllegalArgumentException e) {
                    LOG.warnf("Malformed tenantId claim for sub=%s", subClaim);
                }
            }
        }

        // ── Convert sub → userId (handles Zitadel numeric IDs) ───────────────
        if (subClaim != null && !subClaim.isBlank()) {
            userId = toUserId(subClaim);
        }

        // ── Path 3: JDBC DB fallback ──────────────────────────────────────────
        // Used when Zitadel Action hasn't injected tenantId into JWT yet.
        // Uses blocking JDBC — safe in JAX-RS filter threads.
        // Skip for onboarding paths — they work without tenantId.
        if (tenantId == null && userId != null && !path.contains("/onboard")) {
            tenantId = lookupTenantJdbc(userId);
            if (tenantId != null) {
                LOG.debugf("tenantId resolved from DB: tenant=%s user=%s path=%s",
                        tenantId, userId, path);
            }
        }

        // ── No tenantId resolved ──────────────────────────────────────────────
        if (tenantId == null) {
            LOG.warnf("No tenantId resolved — user: %s path: %s", subClaim, path);
            return;
        }

        // ── Set TenantContext ─────────────────────────────────────────────────
        if (tenantContext.getTenantId() == null) {
            try {
                String role = securityIdentity.getAttribute("role");
                tenantContext.setUserContext(tenantId, userId, role);
                LOG.debugf("Context set: tenant=%s user=%s path=%s",
                        tenantId, userId, path);
            } catch (IllegalStateException e) {
                LOG.warnf("TenantContext collision ignored for path: %s", path);
            }
        }
    }

    // ── JDBC lookup — works in any thread, no reactive session needed ─────────
    private UUID lookupTenantJdbc(UUID userId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_FIND_TENANT)) {

            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Object tid = rs.getObject("tenant_id");
                    if (tid != null) {
                        return tid instanceof UUID
                                ? (UUID) tid
                                : UUID.fromString(tid.toString());
                    }
                }
            }
        } catch (Exception e) {
            LOG.debugf("JDBC tenant lookup failed for userId=%s: %s",
                    userId, e.getMessage());
        }
        return null;
    }

    // ── Converts any string sub to UUID ──────────────────────────────────────
    private UUID toUserId(String sub) {
        try {
            return UUID.fromString(sub);
        } catch (IllegalArgumentException e) {
            return UUID.nameUUIDFromBytes(sub.getBytes(StandardCharsets.UTF_8));
        }
    }
}