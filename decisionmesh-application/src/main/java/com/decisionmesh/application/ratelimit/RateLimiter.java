package com.decisionmesh.application.ratelimit;

import io.smallrye.mutiny.Uni;

import java.util.UUID;

public interface RateLimiter {
    Uni<Boolean> enforce(UUID tenantId, String intentType);
}