package com.decisionmesh.persistence.intent;

import com.decisionmesh.application.port.IntentRepositoryPort;
import com.decisionmesh.domain.intent.Intent;
import com.decisionmesh.persistence.entity.IntentEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.quarkus.logging.Log;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

@Alternative
@Priority(1)
@ApplicationScoped
public class CachedIntentRepository implements IntentRepositoryPort {

    private static final String   KEY_PREFIX   = "intent:";
    private static final Duration CACHE_TTL    = Duration.ofMinutes(30);
    private static final Duration TERMINAL_TTL = Duration.ofMinutes(5);

    private final ReactiveValueCommands<String, String> valueCommands;
    private final ObjectMapper                          mapper;

    @Inject
    public CachedIntentRepository(ReactiveRedisDataSource redis, ObjectMapper mapper) {
        this.valueCommands = redis.value(String.class);
        this.mapper        = mapper;
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    @Override
    public Uni<Void> save(Intent intent) {
        Objects.requireNonNull(intent, "intent must not be null");

        // Capture the Vert.x context before any thread switch.
        // The orchestrator pipeline moves to a worker thread after executionEngine.execute()
        // (which uses runSubscriptionOn internally). When save() is called from that worker
        // thread, persistToPostgres() (@WithTransaction → Hibernate Reactive) would fail with
        // HR000068 (not on event loop) or HR000069 (session closed on wrong thread).
        //
        // Fix: emitOn(eventLoopExecutor) switches the ENTIRE downstream to the event loop.
        // persistToPostgres() subscribes on the event loop, opens the session there, runs the
        // transaction there, and closes the session there — all on the same thread.
        // The old runOnContext()-inside-emitter approach caused HR000069 because the
        // @WithTransaction session-close callback propagated back to the original worker thread.
        final Context vertxCtx = Vertx.currentContext();
        final java.util.concurrent.Executor eventLoopExec = runnable -> {
            if (vertxCtx != null) {
                vertxCtx.runOnContext(v -> runnable.run());
            } else {
                runnable.run(); // no context — attempt directly, HR000068 may follow
            }
        };

        return Uni.createFrom().item(() -> intent.toJson(mapper))
                .emitOn(eventLoopExec)   // ← switch to event loop before Hibernate Reactive
                .flatMap(json ->
                        persistToPostgres(intent, json)
                                .flatMap(v -> updateCache(intent, json)
                                        .onFailure().invoke(ex ->
                                                Log.warnf(ex,
                                                        "Cache update failed for intent=%s (non-fatal)",
                                                        intent.getId()))
                                        .onFailure().recoverWithNull()
                                )
                )
                .replaceWithVoid();
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    @WithSession
    @Override
    public Uni<Intent> findById(UUID tenantId, UUID intentId) {
        Objects.requireNonNull(tenantId,  "tenantId must not be null");
        Objects.requireNonNull(intentId, "intentId must not be null");

        String key = buildKey(tenantId, intentId);

        return valueCommands.get(key)
                .flatMap(cached -> {
                    if (cached != null) {
                        Log.debugf("Cache hit: intent=%s", intentId);
                        return Uni.createFrom().item(Intent.fromJson(cached, mapper));
                    }

                    Log.debugf("Cache miss: intent=%s — loading from PostgreSQL", intentId);

                    return IntentEntity.<IntentEntity>findByTenantAndId(tenantId, intentId)
                            .flatMap(entity -> {
                                if (entity == null) return Uni.createFrom().nullItem();
                                Intent intent = Intent.fromJson(entity.payload, mapper);
                                // Fallback: payload may predate modelTier field.
                                // The scalar column is always current — use it when payload lacks the value.
                                if (intent.getModelTier() == null && entity.modelTier != null) {
                                    intent.setModelTier(entity.modelTier);
                                }
                                return updateCache(intent, entity.payload)
                                        .onFailure().invoke(ex ->
                                                Log.warnf(ex,
                                                        "Cache population failed for intent=%s (non-fatal)",
                                                        intentId))
                                        .onFailure().recoverWithNull()
                                        .replaceWith(intent);
                            });
                });
    }

    @Override
    public Uni<Void> flush() {
        return Uni.createFrom().voidItem();
    }

    // ── PostgreSQL write ──────────────────────────────────────────────────────

    @WithTransaction
    Uni<Void> persistToPostgres(Intent intent, String json) {
        return IntentEntity.<IntentEntity>findById(intent.getId())
                .flatMap(existing -> {
                    IntentEntity entity = existing != null
                            ? existing
                            : newEntity(intent);

                    entity.phase             = intent.getPhase().name();
                    entity.satisfactionState = intent.getSatisfactionState().name();
                    entity.retryCount        = intent.getRetryCount();
                    entity.terminal          = intent.isTerminal();
                    entity.version           = intent.getVersion();
                    entity.userId            = intent.getUserId();
                    entity.modelTier         = intent.getModelTier();
                    entity.payload           = json;
                    entity.updatedAt         = OffsetDateTime.now();

                    return entity.persistAndFlush().replaceWithVoid();
                });
    }

    private IntentEntity newEntity(Intent intent) {
        IntentEntity entity = new IntentEntity();
        entity.id           = intent.getId();
        entity.tenantId     = intent.getTenantId();
        entity.userId       = intent.getUserId();
        entity.intentType   = intent.getIntentType();
        entity.maxRetries   = intent.getMaxRetries();
        entity.modelTier    = intent.getModelTier();
        entity.createdAt    = OffsetDateTime.now();
        entity.updatedAt    = OffsetDateTime.now();
        return entity;
    }

    // ── Redis cache ───────────────────────────────────────────────────────────

    private Uni<Void> updateCache(Intent intent, String json) {
        String key = buildKey(intent.getTenantId(), intent.getId());
        Duration ttl = intent.isTerminal() ? TERMINAL_TTL : CACHE_TTL;
        return valueCommands.setex(key, ttl.getSeconds(), json)
                .replaceWithVoid();
    }

    // ── Key builder ───────────────────────────────────────────────────────────

    private String buildKey(UUID tenantId, UUID intentId) {
        return KEY_PREFIX + tenantId + ":" + intentId;
    }
}