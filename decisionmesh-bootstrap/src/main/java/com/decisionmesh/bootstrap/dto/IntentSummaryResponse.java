package com.decisionmesh.bootstrap.dto;


import com.decisionmesh.persistence.entity.IntentEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Lightweight projection of IntentEntity for list responses.
 *
 * budget.spentUsd is extracted from the payload JSONB field since
 * spent amount is part of the domain aggregate, not a separate column.
 *
 * Used by GET /api/intents  (IntentsTable page, Dashboard recent list).
 */
public record IntentSummaryResponse(
        UUID            id,
        String          intentType,
        String          phase,
        String          satisfactionState,
        int             retryCount,
        int             maxRetries,
        boolean         terminal,
        long            version,
        BudgetSummary   budget,
        OffsetDateTime  createdAt,
        OffsetDateTime  updatedAt
) {

    public record BudgetSummary(
            Double  ceilingUsd,
            Double  spentUsd,
            String  currency,
            boolean exceeded
    ) {}

    // ── Page wrapper ──────────────────────────────────────────────────────────



    // ── Mapper ────────────────────────────────────────────────────────────────

    public static IntentSummaryResponse from(IntentEntity entity, ObjectMapper mapper) {
        BudgetSummary budget = extractBudget(entity.payload, mapper);

        return new IntentSummaryResponse(
                entity.id,
                entity.intentType,
                entity.phase,
                entity.satisfactionState,
                entity.retryCount,
                entity.maxRetries,
                entity.terminal,
                entity.version,
                budget,
                entity.createdAt,
                entity.updatedAt
        );
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Parses budget fields from the payload JSON column.
     *
     * The full Intent aggregate is stored as JSONB in IntentEntity.payload.
     * Budget is a nested object: { "ceilingUsd": 10.0, "spentUsd": 0.0024, ... }
     * Returns null if payload is null or budget node is missing.
     */
    private static BudgetSummary extractBudget(String payload, ObjectMapper mapper) {
        if (payload == null || payload.isBlank()) return null;
        try {
            JsonNode root   = mapper.readTree(payload);
            JsonNode budget = root.get("budget");
            if (budget == null || budget.isNull()) return null;

            return new BudgetSummary(
                    budget.hasNonNull("ceilingUsd") ? budget.get("ceilingUsd").asDouble() : null,
                    budget.hasNonNull("spentUsd")   ? budget.get("spentUsd").asDouble()   : 0.0,
                    budget.hasNonNull("currency")   ? budget.get("currency").asText()      : "USD",
                    budget.hasNonNull("exceeded")   && budget.get("exceeded").asBoolean()
            );
        } catch (Exception e) {
            Log.warnf("Failed to extract budget from intent payload: %s", e.getMessage());
            return null;
        }
    }
}

