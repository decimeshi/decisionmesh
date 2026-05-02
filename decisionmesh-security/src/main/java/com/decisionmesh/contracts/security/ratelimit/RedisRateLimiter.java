package com.decisionmesh.contracts.security.ratelimit;

import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.keys.KeyCommands;
import io.quarkus.redis.datasource.value.ValueCommands;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class RedisRateLimiter {

    @Inject
    RedisDataSource redis;

    public boolean allow(String key, int limit, int windowSeconds) {

        ValueCommands<String, String> values =
                redis.value(String.class);

        KeyCommands<String> keys =
                redis.key(String.class);

        Long current = values.incr(key);

        if (current == 1) {
            keys.expire(key, windowSeconds);
        }

        return current <= limit;
    }
}