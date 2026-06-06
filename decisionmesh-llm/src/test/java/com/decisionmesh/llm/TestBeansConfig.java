package com.decisionmesh.llm;

import com.decisionmesh.application.lock.LockManager;
import com.decisionmesh.application.lock.LockToken;
import com.decisionmesh.application.port.*;
import com.decisionmesh.application.ratelimit.RateLimiter;
import com.decisionmesh.application.reconciliation.ReconciliationService;
import com.decisionmesh.application.telemetry.IntentTelemetryEvent;
import com.decisionmesh.application.telemetry.TelemetryPublisher;
import com.decisionmesh.application.telemetry.TelemetrySink;
import com.decisionmesh.domain.execution.ExecutionRecord;
import com.decisionmesh.domain.intent.Intent;
import com.decisionmesh.domain.intent.IntentPhase;
import com.decisionmesh.domain.plan.Plan;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Test-scope CDI producers satisfying all unsatisfied dependencies for
 * OpenAILlmAdapterTest. Signatures match the actual project interfaces.
 *
 * All stubs are no-ops or return safe defaults.
 * No blocking ORM calls — flush() returns Uni.voidItem() directly
 * since the reactive stack manages flushing inside Panache.withTransaction().
 */
@ApplicationScoped
public class TestBeansConfig {

    // ── ExecutionRepositoryPort ───────────────────────────────────────────────

    @Produces @Alternative @Priority(2) @ApplicationScoped
    public ExecutionRepositoryPort executionRepositoryPort() {
        return new ExecutionRepositoryPort() {
            @Override
            public Uni<Void> append(ExecutionRecord r) {
                return Uni.createFrom().voidItem();
            }

            @Override
            public Uni<List<ExecutionRecord>> findByIntentId(UUID id) {
                return Uni.createFrom().item(List.of());
            }
        };
    }

    // ── IntentRepositoryPort ──────────────────────────────────────────────────

    @Produces @Alternative @Priority(2) @ApplicationScoped
    public IntentRepositoryPort intentRepositoryPort() {
        return new IntentRepositoryPort() {
            @Override
            public Uni<Void> save(Intent intent) {
                return Uni.createFrom().voidItem();
            }

            @Override
            public Uni<Intent> findById(UUID tenantId, UUID intentId) {
                return Uni.createFrom().failure(
                        new UnsupportedOperationException("test stub"));
            }

            @Override
            public Uni<Void> flush() {
                // Reactive stack: Panache.withTransaction() handles flushing.
                // No EntityManager available or needed in test context.
                return Uni.createFrom().voidItem();
            }
        };
    }

    // ── IntentEventRepositoryPort ─────────────────────────────────────────────

    @Produces @Alternative @Priority(2) @ApplicationScoped
    public IntentEventRepositoryPort intentEventRepositoryPort() {
        return events -> Uni.createFrom().voidItem();
    }

    // ── PlanRepositoryPort ────────────────────────────────────────────────────

    @Produces @Alternative @Priority(2) @ApplicationScoped
    public PlanRepositoryPort planRepositoryPort() {
        return new PlanRepositoryPort() {
            @Override
            public Uni<Void> save(Plan plan) {
                return Uni.createFrom().voidItem();
            }

            @Override
            public Uni<List<Plan>> findAllByIntentId(UUID intentId) {
                return Uni.createFrom().item(List.of());
            }

            @Override
            public Uni<Plan> findLatestByIntentId(UUID intentId) {
                return Uni.createFrom().failure(
                        new UnsupportedOperationException("test stub"));
            }
        };
    }

    // ── AdapterLearningPort ───────────────────────────────────────────────────

    @Produces @Alternative @Priority(2) @ApplicationScoped
    public AdapterLearningPort adapterLearningPort() {
        return new AdapterLearningPort() {
            @Override
            public Uni<Map<String, AdapterStats>> getStats(UUID tenantId, List<String> ids) {
                return Uni.createFrom().item(Map.of());
            }

            @Override
            public Uni<Map<String, AdapterStats>> getStatsForIntentType(UUID tenantId, String type) {
                return Uni.createFrom().item(Map.of());
            }
        };
    }

    // ── LearningEngine ────────────────────────────────────────────────────────

    @Produces @Alternative @Priority(2) @ApplicationScoped
    public LearningEngine learningEngine() {
        return new LearningEngine() {
            @Override
            public Uni<Void> update(ExecutionRecord r) {
                return Uni.createFrom().voidItem();
            }

            @Override
            public Uni<Void> updateProfiles(UUID id) {
                return Uni.createFrom().voidItem();
            }
        };
    }

    // ── ExecutionEngine ───────────────────────────────────────────────────────

    @Produces @Alternative @Priority(2) @ApplicationScoped
    public ExecutionEngine executionEngine() {
        return (plan, attempt) -> Uni.createFrom().failure(
                new UnsupportedOperationException("test stub"));
    }

    // ── TelemetrySink ─────────────────────────────────────────────────────────

    @Produces @Alternative @Priority(2) @ApplicationScoped
    public TelemetrySink telemetrySink() {
        return new TelemetrySink() {
            @Override
            public Uni<Void> send(IntentTelemetryEvent event) {
                return Uni.createFrom().voidItem();
            }

            @Override
            public Uni<Void> publishDriftAlert(UUID tenantId, UUID intentId, String adapterId, BigDecimal driftScore) {
                return Uni.createFrom().voidItem();
            }
        };
    }

    // ── TelemetryPublisher ────────────────────────────────────────────────────

    @Produces @Alternative @Priority(2) @ApplicationScoped
    public TelemetryPublisher telemetryPublisher() {
        return new TelemetryPublisher() {
            @Override
            public Uni<Void> publish(IntentPhase phase, UUID tenantId,
                                     UUID intentId, long version) {
                return Uni.createFrom().voidItem();
            }

            @Override
            public Uni<Void> publishDriftAlert(UUID tenantId, UUID intentId,
                                               String adapterId, BigDecimal driftScore) {
                return Uni.createFrom().voidItem();
            }
        };
    }

    // ── RateLimiter ───────────────────────────────────────────────────────────

    @Produces @Alternative @Priority(2) @ApplicationScoped
    public RateLimiter rateLimiter() {
        return new RateLimiter() {
            @Override
            public Uni<Boolean> enforce(UUID tenantId, String intentType) {
                return Uni.createFrom().item(true);
            }
        };
    }

    // ── LockManager ───────────────────────────────────────────────────────────

    @Produces @Alternative @Priority(2) @ApplicationScoped
    public LockManager lockManager() {
        return new LockManager() {
            @Override
            public Uni<LockToken> acquireWithRetry(String key, Duration ttl,
                                                   int retries, Duration backoff) {
                return Uni.createFrom().failure(
                        new UnsupportedOperationException("test stub"));
            }

            @Override
            public Uni<LockToken> acquire(String partitionKey, Duration ttl) {
                return Uni.createFrom().failure(
                        new UnsupportedOperationException("test stub"));
            }

            @Override
            public Uni<Boolean> release(LockToken token) {
                return Uni.createFrom().item(true);
            }

            @Override
            public Uni<Boolean> extend(LockToken token, Duration d) {
                return Uni.createFrom().item(true);
            }

            @Override
            public Uni<Boolean> exists(String key) {
                return Uni.createFrom().item(false);
            }

            @Override
            public Uni<Boolean> forceRelease(String partitionKey) {
                return Uni.createFrom().item(true);
            }

            @Override
            public Uni<Boolean> isLocked(String partitionKey) {
                return Uni.createFrom().item(false);
            }
        };
    }

    // ── ReconciliationService ─────────────────────────────────────────────────

    @Produces @Alternative @Priority(2) @ApplicationScoped
    public ReconciliationService reconciliationService() {
        return new ReconciliationService() {
            @Override
            public Uni<Void> reconcile(UUID tenantId, UUID intentId) {
                return Uni.createFrom().voidItem();
            }
        };
    }
}