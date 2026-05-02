package com.decisionmesh.domain.plan;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A single step in a Plan — maps to one row in intent_plan_steps.
 *
 * Steps are immutable once persisted (fn_guard_immutable DB trigger).
 * withAdapter() returns a NEW transient instance — never mutates the original.
 *
 * The LlmExecutionEngine reads:
 *   getAdapterId()       — null means planner deferred to engine's dynamic selection
 *   getProvider()        — "OPENAI", "ANTHROPIC", "GEMINI", "DEEPSEEK", "AZURE_OPENAI"
 *   getModel()           — "gpt-4o", "claude-3-5-sonnet-20241022", etc.
 *   getRegion()          — for AdapterStats regional filtering
 *   getConfigSnapshot()  — Map: model, max_tokens, temperature, timeout_ms, endpoint
 *   isConditional()      — whether this step has an execution condition
 *   getConditionExpr()   — {trigger: "PREVIOUS_STEP_FAILED", failure_types: [...]}
 *   withAdapter(...)     — inject selected adapter into a copy of this step
 */
public final class PlanStep {

    private final UUID                stepId;
    private final UUID                planId;
    private final UUID                intentId;
    private final UUID                tenantId;
    private final UUID                adapterId;           // null = dynamic selection
    private final int                 stepOrder;
    private final String              stepType;            // PRIMARY | FALLBACK | PARALLEL | ENSEMBLE_MEMBER
    private final boolean             conditional;
    private final Map<String, Object> conditionExpr;       // condition_expr JSONB
    private final Map<String, Object> configSnapshot;      // model, timeout, max_tokens, endpoint, etc.
    private final String              provider;
    private final String              model;
    private final String              region;
    private final BigDecimal          estimatedCostUsd;
    private final Long                estimatedLatencyMs;

    // ── Constructor ───────────────────────────────────────────────────────────

    private PlanStep(Builder b) {
        this.stepId             = b.stepId;
        this.planId             = b.planId;
        this.intentId           = b.intentId;
        this.tenantId           = b.tenantId;
        this.adapterId          = b.adapterId;
        this.stepOrder          = b.stepOrder;
        this.stepType           = b.stepType;
        this.conditional        = b.conditional;
        this.conditionExpr      = b.conditionExpr;
        this.configSnapshot     = b.configSnapshot != null
                ? Map.copyOf(b.configSnapshot)
                : Map.of();
        this.estimatedCostUsd   = b.estimatedCostUsd;
        this.estimatedLatencyMs = b.estimatedLatencyMs;
        // provider/model/region: explicit builder value wins, then fall back to configSnapshot
        this.provider = b.provider != null ? b.provider
                : (String) this.configSnapshot.get("provider");
        this.model    = b.model    != null ? b.model
                : (String) this.configSnapshot.get("model");
        this.region   = b.region;
    }

    // ── Key method: inject selected adapter (dynamic selection path) ──────────

    /**
     * Returns a NEW transient PlanStep with the selected adapter injected.
     *
     * Called by LlmExecutionEngine after AdapterRegistry + LlmModelSelector choose
     * the best adapter. The returned step is used for this execution only —
     * it is not persisted (the original DB step stays intact).
     *
     * configSnapshot is updated with provider + model so each adapter's
     * execute() method reads the correct values from getConfigSnapshot().
     */
    public PlanStep withAdapter(UUID selectedAdapterId,
                                String selectedProvider,
                                String selectedModel) {
        // Mutable copy of configSnapshot with provider + model injected
        Map<String, Object> updatedConfig = new HashMap<>(configSnapshot);
        updatedConfig.put("provider", selectedProvider);
        updatedConfig.put("model",    selectedModel);

        return PlanStep.builder()
                .stepId(this.stepId)
                .planId(this.planId)
                .intentId(this.intentId)
                .tenantId(this.tenantId)
                .adapterId(selectedAdapterId)           // ← injected
                .stepOrder(this.stepOrder)
                .stepType(this.stepType)
                .conditional(this.conditional)
                .conditionExpr(this.conditionExpr)
                .configSnapshot(updatedConfig)          // ← updated with provider+model
                .provider(selectedProvider)             // ← injected
                .model(selectedModel)                   // ← injected
                .region(this.region)
                .estimatedCostUsd(this.estimatedCostUsd)
                .estimatedLatencyMs(this.estimatedLatencyMs)
                .build();
    }

    // ── Standard fallback condition builder ───────────────────────────────────

    /**
     * Create a standard FALLBACK step that triggers on TIMEOUT or ADAPTER_ERROR.
     * Used by IntentCentricPlanner when building the fallback step.
     */
    public static PlanStep fallbackStep(UUID planId, UUID intentId, UUID tenantId,
                                        UUID adapterId, String provider, String model,
                                        String region, Map<String, Object> config) {
        return PlanStep.builder()
                .planId(planId)
                .intentId(intentId)
                .tenantId(tenantId)
                .adapterId(adapterId)
                .stepOrder(1)
                .stepType("FALLBACK")
                .conditional(true)
                .conditionExpr(Map.of(
                        "trigger",       "PREVIOUS_STEP_FAILED",
                        "failure_types", List.of("TIMEOUT", "ADAPTER_ERROR", "RATE_LIMITED")
                ))
                .configSnapshot(config)
                .provider(provider)
                .model(model)
                .region(region)
                .build();
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public UUID                getStepId()             { return stepId; }
    public UUID                getPlanId()             { return planId; }
    public UUID                getIntentId()           { return intentId; }
    public UUID                getTenantId()           { return tenantId; }
    public UUID                getAdapterId()          { return adapterId; }
    public int                 getStepOrder()          { return stepOrder; }
    public String              getStepType()           { return stepType; }
    public boolean             isConditional()         { return conditional; }
    public Map<String, Object> getConditionExpr()      { return conditionExpr; }
    public Map<String, Object> getConfigSnapshot()     { return configSnapshot; }
    public String              getProvider()           { return provider; }
    public String              getModel()              { return model; }
    public String              getRegion()             { return region; }
    public BigDecimal          getEstimatedCostUsd()   { return estimatedCostUsd; }
    public Long                getEstimatedLatencyMs() { return estimatedLatencyMs; }

    // ── Convenience accessors (replaces JsonObject.getString / getInteger) ────

    /** @return config value as String, or defaultValue if absent or null */
    public String getConfigString(String key, String defaultValue) {
        Object v = configSnapshot.get(key);
        return v instanceof String s ? s : defaultValue;
    }

    /** @return config value as Integer, or defaultValue if absent or null */
    public Integer getConfigInt(String key, Integer defaultValue) {
        Object v = configSnapshot.get(key);
        if (v instanceof Integer i)  return i;
        if (v instanceof Number  n)  return n.intValue();
        return defaultValue;
    }

    /** @return config value as Long, or defaultValue if absent or null */
    public Long getConfigLong(String key, Long defaultValue) {
        Object v = configSnapshot.get(key);
        if (v instanceof Long    l) return l;
        if (v instanceof Number  n) return n.longValue();
        return defaultValue;
    }

    /** @return config value as Double, or defaultValue if absent or null */
    public Double getConfigDouble(String key, Double defaultValue) {
        Object v = configSnapshot.get(key);
        if (v instanceof Double  d) return d;
        if (v instanceof Number  n) return n.doubleValue();
        return defaultValue;
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private UUID                stepId             = UUID.randomUUID();
        private UUID                planId;
        private UUID                intentId;
        private UUID                tenantId;
        private UUID                adapterId;
        private int                 stepOrder          = 0;
        private String              stepType           = "PRIMARY";
        private boolean             conditional        = false;
        private Map<String, Object> conditionExpr;
        private Map<String, Object> configSnapshot;
        private String              provider;
        private String              model;
        private String              region;
        private BigDecimal          estimatedCostUsd;
        private Long                estimatedLatencyMs;

        public Builder stepId(UUID v)                        { stepId = v;             return this; }
        public Builder planId(UUID v)                        { planId = v;             return this; }
        public Builder intentId(UUID v)                      { intentId = v;           return this; }
        public Builder tenantId(UUID v)                      { tenantId = v;           return this; }
        public Builder adapterId(UUID v)                     { adapterId = v;          return this; }
        public Builder stepOrder(int v)                      { stepOrder = v;          return this; }
        public Builder stepType(String v)                    { stepType = v;           return this; }
        public Builder conditional(boolean v)                { conditional = v;        return this; }
        public Builder conditionExpr(Map<String, Object> v)  { conditionExpr = v;      return this; }
        public Builder configSnapshot(Map<String, Object> v) { configSnapshot = v;     return this; }
        public Builder provider(String v)                    { provider = v;           return this; }
        public Builder model(String v)                       { model = v;              return this; }
        public Builder region(String v)                      { region = v;             return this; }
        public Builder estimatedCostUsd(BigDecimal v)        { estimatedCostUsd = v;   return this; }
        public Builder estimatedLatencyMs(Long v)            { estimatedLatencyMs = v; return this; }

        public PlanStep build() { return new PlanStep(this); }
    }
}