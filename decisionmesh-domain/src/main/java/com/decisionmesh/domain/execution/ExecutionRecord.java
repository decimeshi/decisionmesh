package com.decisionmesh.domain.execution;

import com.decisionmesh.domain.value.PlanVersion;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Immutable record of a single adapter execution attempt.
 *
 * Serialization: pass the Quarkus-managed ObjectMapper (CDI bean) to
 * toJson() / fromJson() — it has JavaTimeModule registered and
 * WRITE_DATES_AS_TIMESTAMPS disabled.
 *
 * Field rename history:
 *   costUsd → cost  (@JsonAlias handles old Redis records)
 *   id      → executionId  (@JsonAlias("id") on executionId parameter)
 *
 * Quality fields (V5 — nullable):
 *   responseText, qualityScore, hallucinationRisk, hallucinationDetected, qualityReasoning
 *
 * Cache fields (V6 — defaults to 0, backward compatible):
 *   cacheReadTokens  — tokens read from Anthropic prompt cache
 *   cacheWriteTokens — tokens written to Anthropic prompt cache
 *
 * FIXES vs previous version:
 *   1. Cache fields are now final (immutability restored)
 *   2. Cache fields added to @JsonCreator (survives Redis JSON round-trip)
 *   3. Cache fields added to ALL copy factories (withResponseText, withQuality)
 *   4. withCacheUsage() copy factory added — used by AnthropicAdapter
 *   5. Setters removed — was breaking immutability contract
 *   6. isCacheHit() and isCacheWrite() are @JsonIgnore derived methods
 *      (removed stored cacheHit field — caused inconsistency across copy factories)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class ExecutionRecord {

    private final UUID        executionId;
    private final UUID        intentId;
    private final int         attemptNumber;
    private final String      adapterId;
    private final long        latencyMs;
    private final BigDecimal  cost;
    private final FailureType failureType;
    private final PlanVersion planVersion;
    private final Instant     timestamp;

    // ── Cache fields (V6) — FIXED: final, in @JsonCreator, in all copy factories ──
    private final int cacheReadTokens;   // cache_read_input_tokens from Anthropic response
    private final int cacheWriteTokens;  // cache_creation_input_tokens from Anthropic response

    // ── Quality / evaluation fields (V5 — nullable) ───────────────────────────
    private final String     responseText;
    private final BigDecimal qualityScore;
    private final BigDecimal hallucinationRisk;
    private final Boolean    hallucinationDetected;
    private final String     qualityReasoning;

    // ── Constructor ───────────────────────────────────────────────────────────

    @JsonCreator
    public ExecutionRecord(
            @JsonProperty("executionId")           @JsonAlias("id")      UUID        executionId,
            @JsonProperty("intentId")                                     UUID        intentId,
            @JsonProperty("attemptNumber")                                int         attemptNumber,
            @JsonProperty("adapterId")                                    String      adapterId,
            @JsonProperty("latencyMs")                                    long        latencyMs,
            @JsonProperty("cost")                  @JsonAlias("costUsd") BigDecimal  cost,
            @JsonProperty("failureType")                                  FailureType failureType,
            @JsonProperty("planVersion")                                  PlanVersion planVersion,
            @JsonProperty("timestamp")                                    Instant     timestamp,
            @JsonProperty("cacheReadTokens")                              int         cacheReadTokens,
            @JsonProperty("cacheWriteTokens")                             int         cacheWriteTokens,
            @JsonProperty("responseText")                                 String      responseText,
            @JsonProperty("qualityScore")                                 BigDecimal  qualityScore,
            @JsonProperty("hallucinationRisk")                            BigDecimal  hallucinationRisk,
            @JsonProperty("hallucinationDetected")                        Boolean     hallucinationDetected,
            @JsonProperty("qualityReasoning")                             String      qualityReasoning) {

        if (executionId == null) throw new IllegalArgumentException("executionId must not be null");
        if (intentId    == null) throw new IllegalArgumentException("intentId must not be null");
        if (timestamp   == null) throw new IllegalArgumentException("timestamp must not be null");

        this.executionId           = executionId;
        this.intentId              = intentId;
        this.attemptNumber         = attemptNumber;
        this.adapterId             = adapterId;
        this.latencyMs             = latencyMs;
        this.cost                  = cost != null ? cost : BigDecimal.ZERO;
        this.failureType           = failureType;
        this.planVersion           = planVersion;
        this.timestamp             = timestamp;
        this.cacheReadTokens       = Math.max(0, cacheReadTokens);
        this.cacheWriteTokens      = Math.max(0, cacheWriteTokens);
        this.responseText          = responseText;
        this.qualityScore          = qualityScore;
        this.hallucinationRisk     = hallucinationRisk;
        this.hallucinationDetected = hallucinationDetected;
        this.qualityReasoning      = qualityReasoning;
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    public static ExecutionRecord of(UUID        intentId,
                                     int         attemptNumber,
                                     String      adapterId,
                                     long        latencyMs,
                                     BigDecimal  cost,
                                     FailureType failureType,
                                     PlanVersion planVersion) {
        return new ExecutionRecord(
                UUID.randomUUID(), intentId, attemptNumber, adapterId,
                latencyMs, cost, failureType, planVersion, Instant.now(),
                0, 0,      // cacheReadTokens, cacheWriteTokens
                null, null, null, null, null
        );
    }

    // ── Copy factory — attach cache usage (V6 NEW) ────────────────────────────

    /**
     * Returns a new ExecutionRecord with Anthropic prompt cache usage attached.
     * Called by AnthropicAdapter after parsing response usage block.
     * All other fields preserved unchanged.
     */
    public ExecutionRecord withCacheUsage(int cacheReadTokens, int cacheWriteTokens) {
        return new ExecutionRecord(
                this.executionId, this.intentId, this.attemptNumber, this.adapterId,
                this.latencyMs, this.cost, this.failureType, this.planVersion, this.timestamp,
                cacheReadTokens, cacheWriteTokens,
                this.responseText, this.qualityScore,
                this.hallucinationRisk, this.hallucinationDetected, this.qualityReasoning
        );
    }

    // ── Copy factory — capture response text ──────────────────────────────────

    /**
     * FIX: now passes through cacheReadTokens + cacheWriteTokens.
     * Previous version zeroed out cache data on every call.
     */
    public ExecutionRecord withResponseText(String responseText) {
        return new ExecutionRecord(
                this.executionId, this.intentId, this.attemptNumber, this.adapterId,
                this.latencyMs, this.cost, this.failureType, this.planVersion, this.timestamp,
                this.cacheReadTokens, this.cacheWriteTokens,   // ← preserved
                responseText, this.qualityScore,
                this.hallucinationRisk, this.hallucinationDetected, this.qualityReasoning
        );
    }

    // ── Copy factory — apply quality scores ───────────────────────────────────

    /**
     * FIX: now passes through cacheReadTokens + cacheWriteTokens.
     * Previous version zeroed out cache data on every call.
     */
    public ExecutionRecord withQuality(BigDecimal qualityScore,
                                       BigDecimal hallucinationRisk,
                                       boolean    hallucinationDetected,
                                       String     qualityReasoning) {
        return new ExecutionRecord(
                this.executionId, this.intentId, this.attemptNumber, this.adapterId,
                this.latencyMs, this.cost, this.failureType, this.planVersion, this.timestamp,
                this.cacheReadTokens, this.cacheWriteTokens,   // ← preserved
                this.responseText, qualityScore,
                hallucinationRisk, hallucinationDetected, qualityReasoning
        );
    }

    // ── JSON ──────────────────────────────────────────────────────────────────

    public String toJson(ObjectMapper mapper) {
        try {
            return mapper.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize ExecutionRecord id=" + executionId, e);
        }
    }

    public static ExecutionRecord fromJson(String json, ObjectMapper mapper) {
        try {
            return mapper.readValue(json, ExecutionRecord.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize ExecutionRecord", e);
        }
    }

    // ── Semantic helpers ──────────────────────────────────────────────────────

    @JsonIgnore public boolean isSuccess()             { return failureType == null; }
    @JsonIgnore public String  getFailureReason()      { return failureType != null ? failureType.name() : null; }
    @JsonIgnore public boolean hasResponseText()       { return responseText != null && !responseText.isBlank(); }
    @JsonIgnore public boolean isQualityScored()       { return qualityScore != null; }
    @JsonIgnore public boolean isHallucinationFlagged(){ return Boolean.TRUE.equals(hallucinationDetected); }

    /** True when system prompt was served from Anthropic cache — ~90% cost saving. */
    @JsonIgnore public boolean isCacheHit()   { return cacheReadTokens  > 0; }

    /** True when system prompt was written to Anthropic cache for the first time. */
    @JsonIgnore public boolean isCacheWrite() { return cacheWriteTokens > 0; }

    // ── Getters ───────────────────────────────────────────────────────────────

    public UUID        getExecutionId()           { return executionId; }
    public UUID        getIntentId()              { return intentId; }
    public int         getAttemptNumber()         { return attemptNumber; }
    public String      getAdapterId()             { return adapterId; }
    public long        getLatencyMs()             { return latencyMs; }
    public BigDecimal  getCost()                  { return cost; }
    public FailureType getFailureType()           { return failureType; }
    public PlanVersion getPlanVersion()           { return planVersion; }
    public Instant     getTimestamp()             { return timestamp; }
    public int         getCacheReadTokens()       { return cacheReadTokens; }
    public int         getCacheWriteTokens()      { return cacheWriteTokens; }
    public String      getResponseText()          { return responseText; }
    public BigDecimal  getQualityScore()          { return qualityScore; }
    public BigDecimal  getHallucinationRisk()     { return hallucinationRisk; }
    public Boolean     getHallucinationDetected() { return hallucinationDetected; }
    public String      getQualityReasoning()      { return qualityReasoning; }
}