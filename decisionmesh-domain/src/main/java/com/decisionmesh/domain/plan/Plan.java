package com.decisionmesh.domain.plan;

import com.decisionmesh.domain.intent.Intent;
import com.decisionmesh.domain.value.PlanVersion;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable domain object representing an execution plan for an Intent.
 *
 * Serialization: call toJson(mapper) / fromJson(json, mapper) — callers are
 * responsible for supplying a correctly configured ObjectMapper (JavaTimeModule
 * registered, WRITE_DATES_AS_TIMESTAMPS disabled). In Quarkus, inject the
 * CDI ObjectMapper bean.
 *
 * Transient fields (intent, steps) are never serialized.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class Plan {

    // ── Persisted fields ──────────────────────────────────────────────────────

    private final UUID          planId;
    private final UUID          intentId;
    private final PlanVersion   version;
    private final PlanStrategy  strategy;
    private final List<String>  orderedAdapters;
    private final double        predictedCost;
    private final long          predictedLatency;
    private final double        objectiveScore;
    private final Instant       createdAt;

    // ── Transient fields — never serialized, carried in memory only ───────────

    @JsonIgnore
    private Intent intent;          // attached by LlmExecutionEngine via withIntent()

    @JsonIgnore
    private List<PlanStep> steps;   // built lazily from orderedAdapters

    // ── Constructor ───────────────────────────────────────────────────────────

    @JsonCreator
    private Plan(
            @JsonProperty("planId")           UUID planId,
            @JsonProperty("intentId")         UUID intentId,
            @JsonProperty("version")          PlanVersion version,
            @JsonProperty("strategy")         PlanStrategy strategy,
            @JsonProperty("orderedAdapters")  List<String> orderedAdapters,
            @JsonProperty("predictedCost")    double predictedCost,
            @JsonProperty("predictedLatency") long predictedLatency,
            @JsonProperty("objectiveScore")   double objectiveScore,
            @JsonProperty("createdAt")        Instant createdAt) {

        this.planId           = planId;
        this.intentId         = intentId;
        this.version          = version;
        this.strategy         = strategy;
        // Guard: orderedAdapters may be null when deserializing older JSON
        this.orderedAdapters  = (orderedAdapters != null)
                ? List.copyOf(orderedAdapters)
                : List.of();
        this.predictedCost    = predictedCost;
        this.predictedLatency = predictedLatency;
        this.objectiveScore   = objectiveScore;
        this.createdAt        = createdAt;
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    public static Plan create(UUID intentId,
                              PlanVersion version,
                              PlanStrategy strategy,
                              List<String> adapters,
                              double predictedCost,
                              long predictedLatency,
                              double objectiveScore) {

        return new Plan(
                UUID.randomUUID(),
                intentId,
                version,
                strategy,
                adapters,
                predictedCost,
                predictedLatency,
                objectiveScore,
                Instant.now()
        );
    }

    // ── Transient attachment ──────────────────────────────────────────────────

    /**
     * Returns a copy of this Plan with the Intent attached in memory.
     * Called by LlmExecutionEngine before dispatching to an adapter.
     * The intent is never serialized.
     */
    public Plan withIntent(Intent intent) {
        Plan copy = new Plan(
                planId, intentId, version, strategy, orderedAdapters,
                predictedCost, predictedLatency, objectiveScore, createdAt
        );
        copy.intent = intent;
        return copy;
    }

    // ── Step accessors ────────────────────────────────────────────────────────

    // TODO: move getPrimaryStep() and getFallbackStep() to LlmExecutionEngine
    //       or a dedicated PlanStepFactory — step construction is orchestration
    //       logic and does not belong in the domain object.

    /**
     * The primary (first attempt) PlanStep.
     * If orderedAdapters is empty (cold-start), returns a step with no adapterId —
     * LlmExecutionEngine will use AdapterRegistry for dynamic selection.
     */
    public PlanStep getPrimaryStep() {
        if (orderedAdapters.isEmpty()) {
            return PlanStep.builder()
                    .planId(planId)
                    .intentId(intentId)
                    .stepOrder(0)
                    .stepType("PRIMARY")
                    .configSnapshot(Map.of())
                    .build();
        }

        String adapterId = orderedAdapters.get(0);
        return PlanStep.builder()
                .planId(planId)
                .intentId(intentId)
                .adapterId(toUuidOrNull(adapterId))
                .stepOrder(0)
                .stepType("PRIMARY")
                .conditional(false)
                .configSnapshot(Map.of("adapterId", adapterId))
                .build();
    }

    /**
     * The fallback PlanStep.
     * Returns null when strategy is SINGLE_ADAPTER or fewer than two adapters exist.
     * Triggers on TIMEOUT, ADAPTER_ERROR, or RATE_LIMITED.
     */
    public PlanStep getFallbackStep() {
        if (strategy == PlanStrategy.SINGLE_ADAPTER || orderedAdapters.size() < 2) {
            return null;
        }

        String adapterId = orderedAdapters.get(1);
        return PlanStep.builder()
                .planId(planId)
                .intentId(intentId)
                .adapterId(toUuidOrNull(adapterId))
                .stepOrder(1)
                .stepType("FALLBACK")
                .conditional(true)
                .conditionExpr(Map.of(
                        "trigger",       "PREVIOUS_STEP_FAILED",
                        "failure_types", List.of("TIMEOUT", "ADAPTER_ERROR", "RATE_LIMITED")
                ))
                .configSnapshot(Map.of("adapterId", adapterId))
                .build();
    }

    // ── JSON ──────────────────────────────────────────────────────────────────

    public String toJson(ObjectMapper mapper) {
        try {
            return mapper.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to serialise Plan id=" + planId + " to JSON", e);
        }
    }

    public static Plan fromJson(String json, ObjectMapper mapper) {
        try {
            return mapper.readValue(json, Plan.class);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to deserialise Plan from JSON", e);
        }
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public UUID         getPlanId()           { return planId; }
    public UUID         getIntentId()         { return intentId; }
    public PlanVersion  getVersion()          { return version; }
    public PlanStrategy getStrategy()         { return strategy; }
    public List<String> getOrderedAdapters()  { return orderedAdapters; }
    public double       getPredictedCost()    { return predictedCost; }
    public long         getPredictedLatency() { return predictedLatency; }
    public double       getObjectiveScore()   { return objectiveScore; }
    public Instant      getCreatedAt()        { return createdAt; }

    @JsonIgnore
    public Intent       getIntent()           { return intent; }

    // ── Helper ────────────────────────────────────────────────────────────────

    private static UUID toUuidOrNull(String id) {
        if (id == null || id.isBlank()) return null;
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}