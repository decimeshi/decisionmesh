package com.decisionmesh.application.service;

import com.decisionmesh.application.port.ExecutionRepositoryPort;
import com.decisionmesh.application.port.LearningEngine;
import com.decisionmesh.domain.execution.ExecutionRecord;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Redis-backed learning engine that maintains per-adapter EMA performance profiles.
 *
 * Storage strategy:
 *   Redis key:  learning:{intentId}:{adapterId}
 *   Value:      JSON blob with EMA metrics
 *   TTL:        configurable (default 24h), refreshed on every write
 *
 * Note on DB profile updates:
 *   AdapterPerformanceProfileRepository (full DB EMA update) requires tenantId,
 *   provider, model, and region — none of which are present on ExecutionRecord.
 *   Those DB writes happen in LlmExecutionEngine which has full adapter context.
 *   This class owns the Redis hot-cache layer only.
 *
 * EMA formula: new = α × sample + (1 - α) × current   (α = 0.1)
 *
 * Entry points:
 *   update(record)            — called per execution record (hot path)
 *   updateProfiles(intentId)  — called at intent completion for full reconciliation
 */
@Alternative
@Priority(0)
@ApplicationScoped
public class RedisLearningEngine implements LearningEngine {

    private static final String KEY_PREFIX = "learning:";
    private static final double EMA_ALPHA  = 0.1;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<>() {};

    @ConfigProperty(name = "learning.cache.ttl-hours", defaultValue = "24")
    int cacheTtlHours;

    // ── Dependencies ──────────────────────────────────────────────────────────

    private final ReactiveValueCommands<String, String> valueCommands;

    @Inject
    ExecutionRepositoryPort executionRepository;

    @Inject
    public RedisLearningEngine(ReactiveRedisDataSource redis) {
        this.valueCommands = redis.value(String.class);
    }

    // ── LearningEngine port ───────────────────────────────────────────────────

    /**
     * Update adapter EMA profile from a single execution record.
     *
     * Uses fields actually present on ExecutionRecord:
     *   intentId     → UUID  (for Redis key scoping)
     *   adapterId    → String (raw adapter ID)
     *   latencyMs    → long
     *   cost         → double (primitive — NOT getCostUsd())
     *   isSuccess()  → boolean (failureType == null)
     */
    @Override
    public Uni<Void> update(ExecutionRecord record) {
        if (record == null) {
            return Uni.createFrom().voidItem();
        }

        String key = buildKey(record.getIntentId(), record.getAdapterId());

        Log.debugf("Learning update: key=%s, success=%b, latency=%dms, cost=%.6f",
                key, record.isSuccess(), record.getLatencyMs(), record.getCost());

        return valueCommands.get(key)
                .onItem().transformToUni(existing -> {
                    Map<String, Object> current = existing != null ? parseJson(existing) : defaultProfile();

                    Map<String, Object> updated = applyEma(
                            current,
                            record.isSuccess(),
                            record.getLatencyMs(),
                            record.getCost().doubleValue()
                    );

                    return valueCommands.setex(
                            key,
                            Duration.ofHours(cacheTtlHours).getSeconds(),
                            toJsonString(updated)
                    ).replaceWithVoid();
                })
                .onFailure().invoke(ex ->
                        Log.warnf(ex, "Non-fatal: Redis learning update failed: key=%s", key))
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }

    /**
     * Reconcile all execution records for a completed intent.
     *
     * Applies EMA sequentially for each record.
     * Sequential (not parallel) to avoid racing writes to the same key.
     */
    @Override
    public Uni<Void> updateProfiles(UUID intentId) {
        Log.debugf("Reconciling learning profiles: intentId=%s", intentId);

        return executionRepository.findByIntentId(intentId)
                .flatMap(records -> {
                    if (records == null || records.isEmpty()) {
                        Log.debugf("No execution records for intentId=%s — nothing to reconcile",
                                intentId);
                        return Uni.createFrom().voidItem();
                    }

                    Log.debugf("Reconciling %d execution records for intentId=%s",
                            records.size(), intentId);

                    return applySequentially(records);
                })
                .onFailure().invoke(ex ->
                        Log.warnf(ex, "Non-fatal: profile reconciliation failed: intentId=%s",
                                intentId))
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /**
     * Apply EMA updates sequentially — one per execution record.
     * Recursive Uni chaining stays non-blocking on the event loop.
     */
    private Uni<Void> applySequentially(List<ExecutionRecord> records) {
        Uni<Void> chain = Uni.createFrom().voidItem();
        for (ExecutionRecord record : records) {
            chain = chain.flatMap(v -> update(record));
        }
        return chain;
    }

    /**
     * Apply EMA to a profile Map and return the updated Map.
     *
     * Fields updated:
     *   emaSuccessRate  — 1.0 for success, 0.0 for failure
     *   emaLatencyMs    — wall-clock latency
     *   emaCost         — cost in USD (double primitive from record)
     *   failureRate     — derived: 1.0 - emaSuccessRate
     *   compositeScore  — 55/25/20 weighted score
     *   executionCount  — incremented
     *   coldStart       — cleared after 10 executions
     *   isDegraded      — true when emaSuccessRate < 0.60
     *   lastUpdated     — current timestamp
     */
    private Map<String, Object> applyEma(Map<String, Object> current,
                                         boolean success,
                                         long latencyMs,
                                         double cost) {

        double prevSuccessRate = getDouble(current, "emaSuccessRate", 0.8);
        double prevLatency     = getDouble(current, "emaLatencyMs",   2000.0);
        double prevCost        = getDouble(current, "emaCost",        0.01);
        long   execCount       = getLong(current,   "executionCount", 0L);

        double newSuccessRate  = ema(prevSuccessRate, success ? 1.0 : 0.0);
        double newLatency      = ema(prevLatency,     latencyMs);
        double newCost         = ema(prevCost,        cost);
        long   newExecCount    = execCount + 1;
        boolean nowDegraded    = newSuccessRate < 0.60;
        boolean nowColdStart   = newExecCount  < 10;

        // compositeScore: risk weight (15%) folded into success rate → 55%
        double latencyScore   = 1.0 - Math.min(newLatency / 10_000.0, 1.0);
        double costScore      = 1.0 - Math.min(newCost    / 0.10,     1.0);
        double compositeScore = 0.55 * newSuccessRate
                + 0.25 * latencyScore
                + 0.20 * costScore;

        Map<String, Object> updated = new HashMap<>();
        updated.put("emaSuccessRate", newSuccessRate);
        updated.put("emaLatencyMs",   newLatency);
        updated.put("emaCost",        newCost);
        updated.put("failureRate",    1.0 - newSuccessRate);
        updated.put("compositeScore", compositeScore);
        updated.put("executionCount", newExecCount);
        updated.put("coldStart",      nowColdStart);
        updated.put("isDegraded",     nowDegraded);
        updated.put("lastUpdated",    Instant.now().toString());
        return updated;
    }

    private Map<String, Object> defaultProfile() {
        Map<String, Object> profile = new HashMap<>();
        profile.put("emaSuccessRate", 0.8);
        profile.put("emaLatencyMs",   2000.0);
        profile.put("emaCost",        0.01);
        profile.put("failureRate",    0.2);
        profile.put("compositeScore", 0.5);
        profile.put("executionCount", 0L);
        profile.put("coldStart",      true);
        profile.put("isDegraded",     false);
        return profile;
    }

    // ── JSON helpers (replaces JsonObject.getDouble / getLong / encode) ───────

    private Map<String, Object> parseJson(String json) {
        try {
            return MAPPER.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            Log.warnf("Failed to parse Redis profile JSON — using default: %s", e.getMessage());
            return defaultProfile();
        }
    }

    private String toJsonString(Map<String, Object> map) {
        try {
            return MAPPER.writeValueAsString(map);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialise EMA profile to JSON", e);
        }
    }

    private double getDouble(Map<String, Object> map, String key, double defaultValue) {
        Object v = map.get(key);
        if (v instanceof Number n) return n.doubleValue();
        return defaultValue;
    }

    private long getLong(Map<String, Object> map, String key, long defaultValue) {
        Object v = map.get(key);
        if (v instanceof Number n) return n.longValue();
        return defaultValue;
    }

    // ── Key + EMA ─────────────────────────────────────────────────────────────

    /**
     * Redis key: learning:{intentId}:{adapterId}
     * Scoped to intent+adapter because ExecutionRecord has no tenantId.
     */
    private String buildKey(UUID intentId, String adapterId) {
        return KEY_PREFIX + intentId + ":" + adapterId;
    }

    private double ema(double current, double newValue) {
        return EMA_ALPHA * newValue + (1.0 - EMA_ALPHA) * current;
    }
}