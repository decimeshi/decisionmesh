/*
package com.decisionmesh.persistence.intent;

import com.decisionmesh.application.port.IntentRepositoryPort;
import com.decisionmesh.domain.intent.Intent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import jakarta.persistence.OptimisticLockException;

import java.util.Objects;
import java.util.UUID;

*/
/**
 * Redis-backed Intent repository.
 *
 * Key format:  intent:{tenantId}:{intentId}
 * Concurrency: optimistic version check on save — rejects writes where the
 *              stored version is not strictly less than the incoming version.
 *              The distributed lock in ControlPlaneOrchestrator eliminates the
 *              TOCTOU window for the same intent partition; the version check
 *              is a second line of defence for misconfigured or bypassed callers.
 *
 * flush() is a no-op — Redis writes are immediately durable per-command.
 *//*

@Alternative
@Priority(1)          // must be ≥ 1 to override the default bean
@ApplicationScoped
public class RedisIntentRepository implements IntentRepositoryPort {

    private static final String KEY_PREFIX = "intent:";

    private final ReactiveValueCommands<String, String> valueCommands;
    private final ObjectMapper                          mapper;

    @Inject
    public RedisIntentRepository(ReactiveRedisDataSource redis, ObjectMapper mapper) {
        this.valueCommands = redis.value(String.class);
        this.mapper        = mapper;
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    @Override
    public Uni<Void> save(Intent intent) {
        Log.infof(">>> RedisIntentRepository.save called: key=%s", buildKey(intent.getTenantId(), intent.getId()));
        Objects.requireNonNull(intent, "intent must not be null");

        String key = buildKey(intent.getTenantId(), intent.getId());

        return Uni.createFrom().item(() -> intent.toJson(mapper))
                .flatMap(json ->
                        valueCommands.get(key)
                                .flatMap(existingJson -> {
                                    if (existingJson != null) {
                                        Intent existing = Intent.fromJson(existingJson, mapper);
                                        // Accept only if stored version is strictly less than
                                        // the incoming version — rejects no-op rewrites and regressions
                                        if (existing.getVersion() >= intent.getVersion()) {
                                            return Uni.createFrom().failure(
                                                    new OptimisticLockException(
                                                            "Version conflict: intent=" + intent.getId()
                                                                    + " stored=" + existing.getVersion()
                                                                    + " incoming=" + intent.getVersion()));
                                        }
                                    }
                                    return valueCommands.set(key, json).replaceWithVoid();
                                })
                );
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    */
/**
     * Returns null item (empty Uni) when not found.
     * Callers in the application layer are responsible for mapping null to an
     * appropriate response — not the persistence layer's concern.
     *//*

    @Override
    public Uni<Intent> findById(UUID tenantId, UUID intentId) {
        Objects.requireNonNull(tenantId,  "tenantId must not be null");
        Objects.requireNonNull(intentId, "intentId must not be null");

        return valueCommands
                .get(buildKey(tenantId, intentId))
                .onItem().ifNotNull()
                .transform(json -> Intent.fromJson(json, mapper));
    }

    */
/**
     * No-op — Redis writes are immediately durable.
     * No Hibernate session or JPA transaction to flush.
     *//*

    @Override
    public Uni<Void> flush() {
        return Uni.createFrom().voidItem();
    }

    // ── Key builder ───────────────────────────────────────────────────────────

    private String buildKey(UUID tenantId, UUID intentId) {
        return KEY_PREFIX + tenantId + ":" + intentId;
    }

    private String buildKey(Intent intent) {
        return buildKey(intent.getTenantId(), intent.getId());
    }
}*/
