package com.decisionmesh.llm.learning;

import com.decisionmesh.application.port.ExecutionRepositoryPort;
import com.decisionmesh.application.port.LearningEngine;
import com.decisionmesh.domain.execution.ExecutionRecord;
import io.quarkus.logging.Log;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
@ApplicationScoped
public class RedisLearningEngine implements LearningEngine {

    private static final String KEY_PREFIX = "learning:";
    private static final double EMA_ALPHA  = 0.1;

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
                    JsonObject current = existing != null
                            ? new JsonObject(existing)
                            : defaultProfile();

                    JsonObject updated = applyEma(
                            current,
                            record.isSuccess(),
                            record.getLatencyMs(),
                            record.getCost().doubleValue()      // double primitive — correct field
                    );

                    return valueCommands.setex(
                            key,
                            Duration.ofHours(cacheTtlHours).getSeconds(),
                            updated.encode()
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
     *
     * This is the safety net — per-execution update() calls may have failed
     * transiently under load. This ensures the final profile is consistent.
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

    // ── Internal ─────────────────────────────────────────────────────────────

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
     * Apply EMA to a JSON profile blob and return the updated blob.
     *
     * Fields updated:
     *   emaSuccessRate  — 1.0 for success, 0.0 for failure
     *   emaLatencyMs    — wall-clock latency
     *   emaCost         — cost in USD (double primitive from record)
     *   failureRate     — derived: 1.0 - emaSuccessRate
     *   compositeScore  — 40/25/20/15 weighted score (no risk — not in ExecutionRecord)
     *   executionCount  — incremented
     *   coldStart       — cleared after 10 executions
     *   isDegraded      — true when emaSuccessRate < 0.60
     *   lastUpdated     — current timestamp
     */
    private JsonObject applyEma(JsonObject current, boolean success,
                                  long latencyMs, double cost) {

        double prevSuccessRate = current.getDouble("emaSuccessRate", 0.8);
        double prevLatency     = current.getDouble("emaLatencyMs",   2000.0);
        double prevCost        = current.getDouble("emaCost",        0.01);
        long   execCount       = current.getLong("executionCount",   0L);

        double newSuccessRate  = ema(prevSuccessRate, success ? 1.0 : 0.0);
        double newLatency      = ema(prevLatency,     latencyMs);
        double newCost         = ema(prevCost,        cost);
        long   newExecCount    = execCount + 1;
        boolean nowDegraded    = newSuccessRate < 0.60;
        boolean nowColdStart   = newExecCount < 10;

        // compositeScore without riskScore (not available in ExecutionRecord)
        // risk weight (15%) folded into success rate weight → 55% success
        double latencyScore    = 1.0 - Math.min(newLatency / 10_000.0, 1.0);
        double costScore       = 1.0 - Math.min(newCost    / 0.10,     1.0);
        double compositeScore  = 0.55 * newSuccessRate
                               + 0.25 * latencyScore
                               + 0.20 * costScore;

        return new JsonObject()
                .put("emaSuccessRate", newSuccessRate)
                .put("emaLatencyMs",   newLatency)
                .put("emaCost",        newCost)
                .put("failureRate",    1.0 - newSuccessRate)
                .put("compositeScore", compositeScore)
                .put("executionCount", newExecCount)
                .put("coldStart",      nowColdStart)
                .put("isDegraded",     nowDegraded)
                .put("lastUpdated",    Instant.now().toString());
    }

    private JsonObject defaultProfile() {
        return new JsonObject()
                .put("emaSuccessRate", 0.8)
                .put("emaLatencyMs",   2000.0)
                .put("emaCost",        0.01)
                .put("failureRate",    0.2)
                .put("compositeScore", 0.5)
                .put("executionCount", 0L)
                .put("coldStart",      true)
                .put("isDegraded",     false);
    }

    /**
     * Redis key: learning:{intentId}:{adapterId}
     *
     * Scoped to intent+adapter because ExecutionRecord has no tenantId.
     * The AdapterLearningPortImpl reads from adapter_performance_profiles (DB),
     * not from Redis — so this key is for hot-cache purposes only.
     */
    private String buildKey(UUID intentId, String adapterId) {
        return KEY_PREFIX + intentId + ":" + adapterId;
    }

    private double ema(double current, double newValue) {
        return EMA_ALPHA * newValue + (1.0 - EMA_ALPHA) * current;
    }
}
