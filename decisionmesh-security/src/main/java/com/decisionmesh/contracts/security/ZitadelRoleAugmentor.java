package com.decisionmesh.contracts.security;

import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

import java.util.HashSet;
import java.util.Set;

/**
 * Extracts roles from Zitadel's nested JWT claim into Quarkus SecurityIdentity.
 *
 * Zitadel JWT roles claim format:
 * "urn:zitadel:iam:org:project:roles": {
 *   "sys_admin":   { "orgId": "domain" },
 *   "tenant_user": { "orgId": "domain" }
 * }
 *
 * Quarkus @RolesAllowed needs flat role strings — this augmentor extracts
 * the keys ("sys_admin", "tenant_user") and adds them to SecurityIdentity.
 *
 * Without this, @RolesAllowed({"tenant_user"}) always returns 403 because
 * Quarkus cannot natively parse Zitadel's nested roles object format.
 *
 * Place in: decisionmesh-security/src/main/java/
 *   com/decisionmesh/contracts/security/ZitadelRoleAugmentor.java
 */
@ApplicationScoped
public class ZitadelRoleAugmentor implements SecurityIdentityAugmentor {

    private static final Logger LOG = Logger.getLogger(ZitadelRoleAugmentor.class);

    private static final String ZITADEL_ROLES_CLAIM =
            "urn:zitadel:iam:org:project:roles";

    @Override
    public Uni<SecurityIdentity> augment(SecurityIdentity identity,
                                         AuthenticationRequestContext context) {
        // Skip anonymous requests
        if (identity.isAnonymous()) {
            return Uni.createFrom().item(identity);
        }

        // Only process JWT tokens
        if (!(identity.getPrincipal() instanceof JsonWebToken jwt)) {
            return Uni.createFrom().item(identity);
        }

        try {
            Set<String> roles = new HashSet<>(identity.getRoles());

            // ── Extract from nested Zitadel roles object ──────────────────────
            JsonObject rolesObj = jwt.getClaim(ZITADEL_ROLES_CLAIM);
            if (rolesObj != null && !rolesObj.isEmpty()) {
                for (String role : rolesObj.keySet()) {
                    roles.add(role);
                    LOG.debugf("[ZitadelRoles] Added role: %s for sub=%s",
                            role, jwt.getSubject());
                }
            } else {
                LOG.warnf("[ZitadelRoles] No roles found in token for sub=%s — " +
                                "check Project settings → Assert Roles on Authentication",
                        jwt.getSubject());
            }

            return Uni.createFrom().item(
                    QuarkusSecurityIdentity.builder(identity)
                            .addRoles(roles)
                            .build()
            );

        } catch (Exception e) {
            LOG.errorf("[ZitadelRoles] Failed to augment roles: %s", e.getMessage());
            return Uni.createFrom().item(identity);
        }
    }
}