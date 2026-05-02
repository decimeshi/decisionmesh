package com.decisionmesh.bootstrap.redis;

import com.decisionmesh.application.ratelimit.RateLimiter;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.keys.ReactiveKeyCommands;
import io.quarkus.redis.datasource.string.ReactiveStringCommands;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@ApplicationScoped
public class RedisRateLimiter implements RateLimiter {

    private static final String PREFIX = "ratelimit:";

    private final ReactiveStringCommands<String, String> stringCommands;
    private final ReactiveKeyCommands<String> keyCommands;

    @ConfigProperty(name = "controlplane.ratelimit.max-per-minute", defaultValue = "100")
    int maxRequestsPerMinute;

    @Inject
    public RedisRateLimiter(ReactiveRedisDataSource redis) {
        this.stringCommands = redis.string(String.class);
        this.keyCommands = redis.key();
    }

    @Override
    public Uni<Boolean> enforce(UUID tenantId, String intentType) {

        if (tenantId == null || intentType == null) {
            return Uni.createFrom().failure(
                    new IllegalArgumentException("TenantId and intentType required")
            );
        }

        String key = buildKey(tenantId, intentType);

        return stringCommands.incr(key)

                .flatMap(currentCount -> {

                    // Set TTL on first increment
                    if (currentCount == 1) {
                        return keyCommands
                                .expire(key, Duration.ofMinutes(1))
                                .replaceWith(currentCount);
                    }

                    return Uni.createFrom().item(currentCount);
                })

                .map(currentCount -> currentCount <= maxRequestsPerMinute);
    }

    private String buildKey(UUID tenantId, String intentType) {

        long epochMinute = Instant.now().getEpochSecond() / 60;

        return PREFIX +
                tenantId +
                ":" +
                intentType +
                ":" +
                epochMinute;
    }
}
