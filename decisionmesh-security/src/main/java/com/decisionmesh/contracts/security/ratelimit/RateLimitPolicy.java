package com.decisionmesh.contracts.security.ratelimit;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.UUID;

@ApplicationScoped
public class RateLimitPolicy {

    public int windowSeconds() {
        return 60; // 1 minute window
    }

    public int tenantLimit(UUID tenantId) {
        // TODO: load plan from DB/cache
        return 1000;
    }

    public int apiKeyLimit() {
        return 500;
    }

    public int intentSubmitLimit() {
        return 50;
    }
}