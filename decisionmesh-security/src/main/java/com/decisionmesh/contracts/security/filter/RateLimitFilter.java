package com.decisionmesh.contracts.security.filter;

import com.decisionmesh.contracts.security.context.TenantContext;
import com.decisionmesh.contracts.security.ratelimit.RateLimitPolicy;
import com.decisionmesh.contracts.security.ratelimit.RedisRateLimiter;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import java.util.UUID;

/**
 * Rate limiting filter using RESTEasy Reactive's @ServerRequestFilter.
 */
@ApplicationScoped
public class RateLimitFilter {

    @Inject
    TenantContext tenantContext;

    @Inject
    RedisRateLimiter rateLimiter;

    @Inject
    RateLimitPolicy policy;

    @Inject
    Vertx vertx;

    @ServerRequestFilter(priority = 5020)
    public Uni<Response> filter(ContainerRequestContext requestContext) {

        // 1. Skip CORS preflight
        if ("OPTIONS".equalsIgnoreCase(requestContext.getMethod())) {
            return Uni.createFrom().nullItem();
        }

        String method = requestContext.getMethod();
        String path   = requestContext.getUriInfo().getPath();

        // 2. Offload Redis calls to worker pool to avoid blocking the Event Loop
        return vertx.executeBlocking(
                Uni.createFrom().item(() -> doRateLimit(method, path))
        );
    }

    private Response doRateLimit(String method, String path) {
        UUID tenantId;
        UUID userId;

        try {
            // FIXED: Use public getter methods instead of private fields
            tenantId = tenantContext.getTenantId();
            userId   = tenantContext.getUserId();
        } catch (WebApplicationException e) {
            Log.debugf("No tenant context for rate limiting on path: %s — skipping", path);
            return null;
        }

        int windowSeconds = policy.windowSeconds();

        // -------------------------
        // API key rate limiting
        // -------------------------
        // FIXED: Use public isApiKey() method
        if (tenantContext.isApiKey()) {
            String apiKeyKey = "ratelimit:apikey:" + userId;
            if (!rateLimiter.allow(apiKeyKey, policy.apiKeyLimit(), windowSeconds)) {
                Log.warnf("API key rate limit exceeded for user: %s", userId);
                return rateLimitResponse(windowSeconds);
            }
        }

        // -------------------------
        // Tenant-wide rate limiting
        // -------------------------
        String tenantKey = "ratelimit:tenant:" + tenantId;
        if (!rateLimiter.allow(tenantKey, policy.tenantLimit(tenantId), windowSeconds)) {
            Log.warnf("Tenant rate limit exceeded for tenant: %s", tenantId);
            return rateLimitResponse(windowSeconds);
        }

        // -------------------------
        // Intent submission limits
        // -------------------------
        if ("POST".equalsIgnoreCase(method) && path.startsWith("/api/intents")) {
            String intentKey = "ratelimit:tenant:" + tenantId + ":intent";
            if (!rateLimiter.allow(intentKey, policy.intentSubmitLimit(), windowSeconds)) {
                Log.warnf("Intent submission rate limit exceeded for tenant: %s", tenantId);
                return rateLimitResponse(windowSeconds);
            }
        }

        return null; // Continue request
    }

    private Response rateLimitResponse(int windowSeconds) {
        return Response.status(Response.Status.TOO_MANY_REQUESTS)
                .header("Retry-After", windowSeconds)
                .entity("Too Many Requests")
                .build();
    }
}