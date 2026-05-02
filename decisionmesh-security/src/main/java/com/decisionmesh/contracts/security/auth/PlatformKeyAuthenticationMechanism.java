package com.decisionmesh.contracts.security.auth;

import com.decisionmesh.contracts.security.entity.ApiKeyEntity;
import com.decisionmesh.contracts.security.service.ApiKeyService;
import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.quarkus.vertx.http.runtime.security.HttpCredentialTransport;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.Principal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Custom HTTP authentication mechanism for API keys.
 * Fully reactive — validateAndGetKey returns Uni<ApiKeyEntity>.
 * TenantContext is set by TenantContextFilter AFTER authentication.
 */
@ApplicationScoped
public class PlatformKeyAuthenticationMechanism implements HttpAuthenticationMechanism {

    private static final Logger LOG = Logger.getLogger(PlatformKeyAuthenticationMechanism.class);
    private static final String AUTHORIZATION_HEADER  = "Authorization";
    private static final String BEARER_PREFIX         = "Bearer ";
    private static final String API_KEY_PREFIX_LIVE   = "sk_live_";
    private static final String API_KEY_PREFIX_TEST   = "sk_test_";

    @Inject
    ApiKeyService apiKeyService;

    @Override
    public Uni<SecurityIdentity> authenticate(RoutingContext context,
                                              IdentityProviderManager identityProviderManager) {

        if ("OPTIONS".equals(context.request().method().name())) {
            return Uni.createFrom().nullItem();
        }

        String path = context.normalizedPath();
        LOG.debugf("API Key Auth: %s %s", context.request().method(), path);

        if (isPublicEndpoint(path)) {
            LOG.debugf("Public endpoint - skipping: %s", path);
            return Uni.createFrom().nullItem();
        }

        String authHeader = context.request().getHeader(AUTHORIZATION_HEADER);
        if (authHeader == null || authHeader.isBlank()) {
            LOG.debugf("No Authorization header - skipping API key auth");
            return Uni.createFrom().nullItem();
        }

        String credential = extractCredential(authHeader);
        if (!isApiKey(credential)) {
            LOG.debugf("Not an API key - skipping");
            return Uni.createFrom().nullItem();
        }

        LOG.debugf("Validating API key: %s...",
                credential.substring(0, Math.min(15, credential.length())));

        // Fully reactive — no executeBlocking needed
        return apiKeyService.validateAndGetKey(credential)
                .onItem().transformToUni(apiKey -> {
                    if (apiKey == null) {
                        LOG.warnf("Invalid API key");
                        return Uni.createFrom().nullItem();
                    }

                    // scopes is now List<String> — pass directly
                    Set<String> roles = extractRolesFromScopes(apiKey.scopes);

                    LOG.infof("API Key authenticated: tenant=%s key=%s user=%s roles=%s",
                            apiKey.tenantId, apiKey.keyPrefix, apiKey.createdByUserId, roles);

                    QuarkusSecurityIdentity.Builder builder = QuarkusSecurityIdentity.builder();
                    builder.setPrincipal(new ApiKeyPrincipal(apiKey));
                    builder.addRoles(roles);
                    builder.addAttribute("api.key.entity",  apiKey);
                    builder.addAttribute("tenantId",        apiKey.tenantId);
                    builder.addAttribute("userId",          apiKey.createdByUserId);
                    builder.addAttribute("apiKeyId",        apiKey.keyId.toString());

                    SecurityIdentity identity = builder.build();

                    LOG.debugf("Built SecurityIdentity: principal=%s roles=%s anonymous=%s",
                            identity.getPrincipal().getName(),
                            identity.getRoles(),
                            identity.isAnonymous());

                    return Uni.createFrom().item(identity);
                });
    }

    @Override
    public Uni<ChallengeData> getChallenge(RoutingContext context) {
        return Uni.createFrom().item(new ChallengeData(401, "WWW-Authenticate", "Bearer"));
    }

    @Override
    public Set<Class<? extends AuthenticationRequest>> getCredentialTypes() {
        return Set.of();
    }

    @Override
    public Uni<HttpCredentialTransport> getCredentialTransport(RoutingContext context) {
        return Uni.createFrom().item(new HttpCredentialTransport(
                HttpCredentialTransport.Type.AUTHORIZATION, "bearer"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isApiKey(String credential) {
        return credential.startsWith(API_KEY_PREFIX_LIVE)
                || credential.startsWith(API_KEY_PREFIX_TEST);
    }

    private String extractCredential(String authHeader) {
        if (authHeader.startsWith(BEARER_PREFIX)) {
            return authHeader.substring(BEARER_PREFIX.length()).trim();
        }
        return authHeader.trim();
    }

    private boolean isPublicEndpoint(String path) {
        String p = path.startsWith("/") ? path.substring(1) : path;
        boolean isPublic = p.equals("signup")
                || p.startsWith("signup/")
                || p.equals("health")
                || p.startsWith("health/")
                || p.startsWith("q/")
                || p.startsWith("metrics")
                || p.startsWith("swagger-ui")
                || p.startsWith("openapi");
        if (isPublic) LOG.debugf("Public endpoint: %s", path);
        else          LOG.debugf("Protected endpoint: %s", path);
        return isPublic;
    }

    /**
     * Extract roles from the API key scopes list.
     * scopes is now List<String> — no JSON string parsing needed.
     *
     * null / empty = full access (wildcard).
     * "*" in list  = all permissions.
     */
    private Set<String> extractRolesFromScopes(List<String> scopes) {
        Set<String> roles = new HashSet<>();

        LOG.debugf("Extracting roles from scopes: %s", scopes);

        if (scopes == null || scopes.isEmpty()) {
            LOG.debugf("No scopes — granting full access");
            roles.add("decision:submit");
            roles.add("decision:read");
            roles.add("intent:submit");
            return roles;
        }

        for (String scope : scopes) {
            if (scope == null) continue;
            scope = scope.trim();
            if (scope.equals("*")) {
                LOG.debugf("Wildcard scope — granting all permissions");
                roles.add("decision:submit");
                roles.add("decision:read");
                roles.add("intent:submit");
                roles.add("apikey:manage");
            } else if (!scope.isEmpty()) {
                LOG.debugf("Adding role: %s", scope);
                roles.add(scope);
            }
        }

        LOG.debugf("Final roles: %s", roles);
        return roles;
    }

    // ── Principal ─────────────────────────────────────────────────────────────

    public record ApiKeyPrincipal(ApiKeyEntity apiKey) implements Principal {
        @Override
        public String getName() {
            return apiKey.createdByUserId.toString();
        }
    }
}