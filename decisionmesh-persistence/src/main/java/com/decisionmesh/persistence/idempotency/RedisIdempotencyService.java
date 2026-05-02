package com.decisionmesh.persistence.idempotency;

import com.decisionmesh.application.idempotency.IdempotencyService;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.UUID;

@ApplicationScoped
public class RedisIdempotencyService implements IdempotencyService {

    private static final Duration TTL = Duration.ofMinutes(30);

    @Inject
    ReactiveRedisDataSource redis;

    @Override
    public Uni<Boolean> checkAndRegister(UUID tenantId, String idempotencyKey) {

        if (tenantId == null) {
            return Uni.createFrom().failure(
                    new IllegalArgumentException("TenantId must not be null"));
        }

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Uni.createFrom().failure(
                    new IllegalArgumentException("Invalid idempotency key"));
        }

        String key = buildKey(tenantId, idempotencyKey);

        ReactiveValueCommands<String, String> commands = redis.value(String.class);

        // SETNX atomically — returns true if key was created (first time), false if duplicate
        return commands.setnx(key, "1")
                .flatMap(created -> {
                    if (created) {
                        // Set TTL only if we created the key
                        return redis.key().expire(key, TTL)
                                .replaceWith(true);
                    }
                    return Uni.createFrom().item(false);
                });
    }

    private String buildKey(UUID tenantId, String key) {
        return "idempotency:" + tenantId + ":" + key;
    }
}