package com.decisionmesh.llm;

import com.decisionmesh.application.port.ExecutionEngine;
import com.decisionmesh.domain.execution.ExecutionRecord;
import com.decisionmesh.domain.intent.Intent;
import com.decisionmesh.domain.plan.Plan;
import com.decisionmesh.domain.plan.PlanStep;
import com.decisionmesh.domain.value.Budget;
import com.decisionmesh.application.exception.LlmAdapterException;
import com.decisionmesh.persistence.repository.AdapterPerformanceRepository;
import com.decisionmesh.llm.persistence.ExecutionRecordRepository;
import com.decisionmesh.llm.registry.AdapterRegistry;
import com.decisionmesh.llm.selector.AdapterStats;
import com.decisionmesh.llm.selector.LlmModelSelector;
import com.decisionmesh.llm.selector.SelectedAdapter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.quarkus.logging.Log;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * LlmExecutionEngine — orchestrates LLM adapter selection, execution,
 * fallback chains, and performance profiling.
 *
 * CHANGES vs original:
 *   1. Cache metrics recorded per execution:
 *      - llm.cache.hit        (counter, tagged by provider)
 *      - llm.cache.write      (counter, tagged by provider)
 *      - llm.cache.miss       (counter, tagged by provider)
 *      - llm.cache.savings    (counter, cumulative USD saved)
 *   2. Cache-aware logging: logs CACHE HIT / CACHE WRITE / NO CACHE per step
 *   3. CacheableAdapter detection: only cache-capable adapters emit cache metrics
 *
 * No changes to core selection, fallback, or persistence logic.
 */
@Alternative
@Priority(1)
@ApplicationScoped
public class LlmExecutionEngine implements ExecutionEngine {

    private static final double BUDGET_LOW_WARNING_PCT = 0.10;

    @ConfigProperty(name = "llm.registry.fail-fast-on-empty", defaultValue = "false")
    boolean failFastOnEmptyRegistry;

    private static final String RESPONSE_CACHE_PREFIX = "llm:resp:";
    private static final Duration RESPONSE_CACHE_TTL  = Duration.ofHours(1);

    private static final String RESP_CACHE_PREFIX = "llm:resp:";
    private static final Duration RESP_CACHE_TTL  = Duration.ofHours(1);

    private final ReactiveValueCommands<String, String> responseCache;
    private final Map<String, LlmAdapter>        adaptersByProvider;
    private final AdapterRegistry                adapterRegistry;
    private final LlmModelSelector               modelSelector;
    private final AdapterPerformanceRepository   profileRepository;
    private final ExecutionRecordRepository      executionRecordRepository;
    private final MeterRegistry                  meterRegistry;

    @Inject
    public LlmExecutionEngine(Instance<LlmAdapter> adapters,
                              AdapterRegistry adapterRegistry,
                              LlmModelSelector modelSelector,
                              AdapterPerformanceRepository profileRepository,
                              ExecutionRecordRepository executionRecordRepository,
                              MeterRegistry meterRegistry,
                              ReactiveRedisDataSource redis) {
        this.adaptersByProvider    = StreamSupport
                .stream(adapters.spliterator(), false)
                .collect(Collectors.toMap(a -> a.provider().toUpperCase(), Function.identity()));
        this.adapterRegistry       = adapterRegistry;
        this.modelSelector         = modelSelector;
        this.profileRepository     = profileRepository;
        this.executionRecordRepository = executionRecordRepository;
        this.meterRegistry         = meterRegistry;
        this.responseCache         = redis.value(String.class);

        // Log which adapters support prompt caching on startup
        adaptersByProvider.forEach((provider, adapter) -> {
            boolean cacheable = adapter instanceof CacheableAdapter ca && ca.isCacheEnabled();
            Log.infof("LlmExecutionEngine: provider=%s, cacheSupported=%s", provider, cacheable);
        });
    }

    // ── Public API ────────────────────────────────────────────────────────────

    @Override
    public Uni<ExecutionRecord> execute(Plan plan, int attemptNumber) {
        Intent   intent      = plan.getIntent();
        PlanStep primaryStep = plan.getPrimaryStep();

        if (primaryStep.getAdapterId() != null) {
            return executeWithFallback(intent, plan, attemptNumber);
        }

        return adapterRegistry.loadCandidates(intent)
                .flatMap(candidates -> {
                    if (candidates.isEmpty()) {
                        Log.errorf("No adapter candidates: tenant=%s, type=%s",
                                intent.getTenantId(), intent.getIntentType());
                        meterRegistry.counter("llm.registry.empty",
                                "tenant", safeId(intent.getTenantId())).increment();
                        if (failFastOnEmptyRegistry) {
                            return Uni.createFrom().failure(new IllegalStateException(
                                    "No active LLM adapters for tenant=" + intent.getTenantId()));
                        }
                        return executeWithFallback(intent, plan, attemptNumber);
                    }
                    return selectAndExecute(intent, plan, candidates, attemptNumber);
                });
    }

    // ── Dynamic selection ─────────────────────────────────────────────────────

    private String buildRespCacheKey(Intent intent) {
        try {
            String raw = intent.getTenantId() + ":" + intent.getIntentType() + ":"
                    + (intent.getObjective() != null ? intent.getObjective().getDescription() : "")
                    + ":" + (intent.getConstraints() != null ? intent.getConstraints().toString() : "");
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return RESP_CACHE_PREFIX + sb;
        } catch (Exception e) {
            return RESP_CACHE_PREFIX + intent.getId();
        }
    }

    private Uni<ExecutionRecord> selectAndExecute(Intent intent, Plan plan,
                                                  List<AdapterStats> candidates,
                                                  int attemptNumber) {
        String cacheKey = buildRespCacheKey(intent);
        if (attemptNumber == 1) {
            return responseCache.get(cacheKey)
                    .flatMap(cached -> {
                        if (cached != null) {
                            Log.infof("[RespCache] HIT intent=%s", intent.getId());
                            meterRegistry.counter("llm.response_cache.hit",
                                    "tenant", safeId(intent.getTenantId())).increment();
                            return Uni.createFrom().item(ExecutionRecord.of(
                                    intent.getId(), attemptNumber, "cache",
                                    0L, BigDecimal.ZERO, null, null
                            ).withResponseText(cached));
                        }
                        meterRegistry.counter("llm.response_cache.miss",
                                "tenant", safeId(intent.getTenantId())).increment();
                        return doSelect(intent, plan, candidates, attemptNumber, cacheKey);
                    })
                    .onFailure().recoverWithUni(ex ->
                            doSelect(intent, plan, candidates, attemptNumber, cacheKey));
        }
        return doSelect(intent, plan, candidates, attemptNumber, cacheKey);
    }

    private Uni<ExecutionRecord> doSelect(Intent intent, Plan plan,
                                          List<AdapterStats> candidates,
                                          int attemptNumber, String cacheKey) {
        List<SelectedAdapter> ranked = modelSelector.select(intent, candidates);
        if (ranked.isEmpty()) {
            return Uni.createFrom().failure(new IllegalStateException(
                    "No adapters after selection filtering: intent=" + intent.getId()));
        }

        SelectedAdapter primary      = ranked.get(0);
        PlanStep        enrichedStep = plan.getPrimaryStep()
                .withAdapter(primary.adapterId(), primary.provider(), primary.model());

        Log.infof("Adapter selected: provider=%s, model=%s, score=%.3f, intent=%s",
                primary.provider(), primary.model(), primary.compositeScore(), intent.getId());

        return executeStep(intent, enrichedStep, attemptNumber)
                .call(record -> recordCacheMetrics(record, enrichedStep))
                .invoke(record -> {
                    if (record.isSuccess() && record.hasResponseText()) {
                        responseCache.setex(cacheKey, RESP_CACHE_TTL.getSeconds(),
                                        record.getResponseText())
                                .subscribe().with(
                                        v -> Log.debugf("[RespCache] WRITE intent=%s", intent.getId()),
                                        ex -> Log.warnf("[RespCache] Write failed: %s", ex.getMessage()));
                    }
                })
                .call(record -> persistResult(record, intent))
                .call(record -> updateProfile(intent, primary, record))
                .onFailure().recoverWithUni(failure -> {
                    fireAndForgetFailureProfile(intent, primary, failure);
                    return tryFallbacks(intent, plan, ranked, 1, attemptNumber, failure);
                });
    }

    // ── Pre-selected plan path ────────────────────────────────────────────────

    private Uni<ExecutionRecord> executeWithFallback(Intent intent, Plan plan, int attemptNumber) {
        PlanStep primaryStep = plan.getPrimaryStep();

        return executeStep(intent, primaryStep, attemptNumber)
                .call(record -> recordCacheMetrics(record, primaryStep))    // ← NEW
                .call(record -> persistResult(record, intent))
                .call(record -> persistProfileFromStep(intent, primaryStep, record))
                .onFailure().recoverWithUni(failure -> {
                    fireAndForgetFailureFromStep(intent, primaryStep, failure);
                    PlanStep fallback = plan.getFallbackStep();
                    if (fallback == null || !isFallbackTriggered(fallback, failure)) {
                        return Uni.createFrom().failure(failure);
                    }
                    meterRegistry.counter("llm.fallback.triggered",
                            "tenant", safeId(intent.getTenantId())).increment();
                    return executeStep(intent, fallback, attemptNumber)
                            .call(record -> recordCacheMetrics(record, fallback))  // ← NEW
                            .call(record -> persistResult(record, intent))
                            .call(record -> persistProfileFromStep(intent, fallback, record))
                            .onFailure().invoke(ex -> fireAndForgetFailureFromStep(intent, fallback, ex));
                });
    }

    // ── Ranked fallback chain ─────────────────────────────────────────────────

    private Uni<ExecutionRecord> tryFallbacks(Intent intent, Plan plan,
                                              List<SelectedAdapter> ranked, int idx,
                                              int attemptNumber, Throwable lastFailure) {
        if (idx >= ranked.size()) {
            meterRegistry.counter("llm.adapters.exhausted",
                    "tenant", safeId(intent.getTenantId())).increment();
            return Uni.createFrom().failure(lastFailure);
        }
        SelectedAdapter fallback     = ranked.get(idx);
        PlanStep        fallbackStep = plan.getPrimaryStep()
                .withAdapter(fallback.adapterId(), fallback.provider(), fallback.model());

        return executeStep(intent, fallbackStep, attemptNumber)
                .call(record -> recordCacheMetrics(record, fallbackStep))   // ← NEW
                .call(record -> persistResult(record, intent))
                .call(record -> updateProfile(intent, fallback, record))
                .onFailure().recoverWithUni(ex -> {
                    fireAndForgetFailureProfile(intent, fallback, ex);
                    return tryFallbacks(intent, plan, ranked, idx + 1, attemptNumber, ex);
                });
    }

    // ── Step execution ────────────────────────────────────────────────────────

    private Uni<ExecutionRecord> executeStep(Intent intent, PlanStep step, int attempt) {
        String     provider  = step.getProvider() != null ? step.getProvider().toUpperCase() : "";
        Instant    startedAt = Instant.now();
        LlmAdapter adapter   = adaptersByProvider.get(provider);

        if (adapter == null) {
            return Uni.createFrom().failure(new IllegalStateException(
                    "No adapter for provider: " + provider + ". Available: " + adaptersByProvider.keySet()));
        }

        return checkBudget(intent)
                .flatMap(v -> adapter.execute(intent, step, attempt))
                .onFailure(this::isRetryable)
                .retry()
                .withBackOff(Duration.ofMillis(200), Duration.ofSeconds(2))
                .atMost(2)
                .invoke(record -> recordStepMetric(provider, "SUCCESS", startedAt))
                .onFailure().invoke(ex -> recordStepMetric(provider, classifyFailure(ex), startedAt));
    }

    // ── Cache metrics (NEW) ───────────────────────────────────────────────────

    /**
     * Records Prometheus cache metrics after each successful execution.
     *
     * Metrics emitted:
     *   llm.cache.hit{provider}     — cache read occurred (savings!)
     *   llm.cache.write{provider}   — cache was written for the first time
     *   llm.cache.miss{provider}    — no cache activity
     *   llm.cache.tokens.read       — cumulative cache_read_input_tokens
     *   llm.cache.tokens.written    — cumulative cache_creation_input_tokens
     *
     * Only emitted for adapters that implement CacheableAdapter.
     * OpenAI / Google / Custom adapters are silently skipped.
     */
    private Uni<Void> recordCacheMetrics(ExecutionRecord record, PlanStep step) {
        String provider = step.getProvider() != null ? step.getProvider().toLowerCase() : "unknown";
        LlmAdapter adapter = adaptersByProvider.get(provider.toUpperCase());

        // Only track cache metrics for cache-capable adapters
        if (!(adapter instanceof CacheableAdapter cacheable) || !cacheable.isCacheEnabled()) {
            return Uni.createFrom().voidItem();
        }

        return Uni.createFrom().item(() -> {
                    int cacheReadTokens  = record.getCacheReadTokens();
                    int cacheWriteTokens = record.getCacheWriteTokens();

                    if (cacheReadTokens > 0) {
                        // Cache HIT — reads from cache, 90% cheaper
                        meterRegistry.counter("llm.cache.hit",
                                "provider", provider).increment();
                        meterRegistry.counter("llm.cache.tokens.read",
                                "provider", provider).increment(cacheReadTokens);

                        // Track cumulative savings (90% of standard input price per read token)
                        // Haiku 4.5: $1.00/1M standard → $0.10/1M cached → $0.90/1M savings
                        double savedUsd = (cacheReadTokens / 1_000_000.0) * 0.90;
                        meterRegistry.counter("llm.cache.savings.usd",
                                "provider", provider).increment(savedUsd);

                        Log.debugf("[Cache] HIT: provider=%s, readTokens=%d, savedUsd=%.6f",
                                provider, cacheReadTokens, savedUsd);

                    } else if (cacheWriteTokens > 0) {
                        // Cache WRITE — first time, 25% more expensive but enables future hits
                        meterRegistry.counter("llm.cache.write",
                                "provider", provider).increment();
                        meterRegistry.counter("llm.cache.tokens.written",
                                "provider", provider).increment(cacheWriteTokens);

                        Log.debugf("[Cache] WRITE: provider=%s, writtenTokens=%d", provider, cacheWriteTokens);

                    } else {
                        // Cache MISS — caching was enabled but neither hit nor write
                        meterRegistry.counter("llm.cache.miss",
                                "provider", provider).increment();

                        Log.debugf("[Cache] MISS: provider=%s (cache enabled but no cache activity)", provider);
                    }

                    return null;
                }).replaceWithVoid()
                .onFailure().invoke(ex ->
                        Log.warnf(ex, "Non-fatal: cache metrics failed for provider=%s", provider))
                .onFailure().recoverWithNull().replaceWithVoid();
    }

    // ── Budget check ──────────────────────────────────────────────────────────

    private Uni<Void> checkBudget(Intent intent) {
        if (intent.getBudget() == null) return Uni.createFrom().voidItem();

        Budget budget = intent.getBudget();
        if (!budget.isConstrained()) return Uni.createFrom().voidItem();

        if (budget.isExceeded()) {
            meterRegistry.counter("llm.budget.exhausted",
                    "tenant", safeId(intent.getTenantId())).increment();
            return Uni.createFrom().failure(
                    new IllegalStateException("Budget exhausted: intent=" + intent.getId()
                            + ", ceiling=" + budget.getCeilingUsd()
                            + ", spent=" + budget.getSpentUsd()));
        }

        double remaining = budget.remaining();
        if (remaining / budget.getCeilingUsd() < BUDGET_LOW_WARNING_PCT) {
            Log.warnf("Budget low: intent=%s, remaining=%.6f (<%.0f%%)",
                    intent.getId(), remaining, BUDGET_LOW_WARNING_PCT * 100);
            meterRegistry.counter("llm.budget.low_warning",
                    "tenant", safeId(intent.getTenantId())).increment();
        }

        return Uni.createFrom().voidItem();
    }

    // ── Observability ─────────────────────────────────────────────────────────

    private void recordStepMetric(String provider, String outcome, Instant startedAt) {
        Duration elapsed = Duration.between(startedAt, Instant.now());
        Timer.builder("llm.step.latency")
                .tag("provider", provider)
                .tag("outcome", outcome)
                .register(meterRegistry)
                .record(elapsed);
        meterRegistry.counter("llm.step.outcome",
                "provider", provider, "outcome", outcome).increment();
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private Uni<Void> persistResult(ExecutionRecord record, Intent intent) {
        return executionRecordRepository.save(record, intent)
                .replaceWithVoid()
                .onFailure().invoke(ex ->
                        Log.warnf(ex, "Non-fatal: persist failed: intent=%s", intent.getId()))
                .onFailure().recoverWithNull().replaceWithVoid();
    }

    private Uni<Void> updateProfile(Intent intent, SelectedAdapter adapter, ExecutionRecord record) {
        boolean success = record.isSuccess();
        long    latency = record.getLatencyMs();
        double  cost    = record.getCost() != null ? record.getCost().doubleValue() : 0;

        return profileUni(success, intent.getTenantId(), adapter.adapterId(),
                adapter.provider(), adapter.model(), adapter.region(),
                latency, cost, 0.0, record.getExecutionId(), intent.getId());
    }

    private Uni<Void> persistProfileFromStep(Intent intent, PlanStep step, ExecutionRecord record) {
        boolean success = record.isSuccess();
        long    latency = record.getLatencyMs();
        double  cost    = record.getCost() != null ? record.getCost().doubleValue() : 0;

        return profileUni(success, intent.getTenantId(), step.getAdapterId(),
                step.getProvider(), step.getModel(), step.getRegion(),
                latency, cost, 0.0, record.getExecutionId(), intent.getId());
    }

    private Uni<Void> profileUni(boolean success, UUID tenantId, UUID adapterId,
                                 String provider, String model, String region,
                                 long latency, double cost, double risk,
                                 UUID executionId, UUID intentId) {
        Uni<Void> u = success
                ? profileRepository.recordSuccess(tenantId, adapterId, provider, model, region,
                latency, cost, risk, executionId, intentId)
                : profileRepository.recordFailure(tenantId, adapterId, provider, model, region,
                latency, cost, executionId, intentId);
        return u.onFailure().recoverWithNull().replaceWithVoid();
    }

    private void fireAndForgetFailureProfile(Intent intent, SelectedAdapter adapter, Throwable ex) {
        long latency = ex instanceof LlmAdapterException lae ? lae.getLatencyMs() : 0L;
        profileRepository.recordFailure(intent.getTenantId(), adapter.adapterId(),
                        adapter.provider(), adapter.model(), adapter.region(),
                        latency, 0.0, null, intent.getId())
                .subscribe().with(v -> {}, err -> {});
    }

    private void fireAndForgetFailureFromStep(Intent intent, PlanStep step, Throwable ex) {
        long latency = ex instanceof LlmAdapterException lae ? lae.getLatencyMs() : 0L;
        profileRepository.recordFailure(intent.getTenantId(), step.getAdapterId(),
                        step.getProvider(), step.getModel(), step.getRegion(),
                        latency, 0.0, null, intent.getId())
                .subscribe().with(v -> {}, err -> {});
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isRetryable(Throwable ex) {
        if (!(ex instanceof LlmAdapterException lae)) return false;
        return "RATE_LIMITED".equals(lae.getFailureType()) || "ADAPTER_ERROR".equals(lae.getFailureType());
    }

    private boolean isFallbackTriggered(PlanStep fallback, Throwable failure) {
        if (!fallback.isConditional()) return true;
        Map<String, Object> cond = fallback.getConditionExpr();
        if (cond == null) return true;
        if (!"PREVIOUS_STEP_FAILED".equals(cond.get("trigger"))) return false;
        Object types = cond.get("failure_types");
        if (!(types instanceof Iterable<?> list)) return true;
        String code = failure instanceof LlmAdapterException lae ? lae.getFailureType() : "ADAPTER_ERROR";
        for (Object t : list) { if (code.equals(String.valueOf(t))) return true; }
        return false;
    }

    private String classifyFailure(Throwable ex) {
        if (ex instanceof LlmAdapterException lae) return lae.getFailureType();
        String name = ex.getClass().getSimpleName();
        if (name.contains("Timeout")) return "TIMEOUT";
        return "ADAPTER_ERROR";
    }

    private String safeId(UUID id) { return id != null ? id.toString() : "unknown"; }
}