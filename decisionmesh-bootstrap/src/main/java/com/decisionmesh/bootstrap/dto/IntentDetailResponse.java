package com.decisionmesh.bootstrap.dto;

import com.decisionmesh.persistence.entity.IntentEntity;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Full intent detail response — used by GET /api/intents/{id}
 *
 * Maps every card shown in IntentDetail.jsx:
 *
 *   Status card      — id, intentType, version, retryCount, maxRetries,
 *                      terminal, driftScore, phase, satisfactionState
 *   Budget card      — budget.ceilingUsd/spentUsd/currency/exceeded
 *   Constraints card — constraints.maxRetries/timeoutSeconds/maxLatencyMs/maxDriftThreshold
 *   Objective card   — objective (raw JSONB map)
 *   PolicyOutcomeCard— violationReason (specific why, not just that it violated)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IntentDetailResponse(

        UUID    id,
        String  intentType,
        int     version,
        int     retryCount,
        int     maxRetries,
        boolean terminal,
        double  driftScore,
        String  phase,
        String  satisfactionState,
        Instant createdAt,
        Instant updatedAt,

        BudgetDto           budget,
        ConstraintsDto      constraints,
        Map<String, Object> objective,

        // Null on SATISFIED intents.
        // Set by ViolationHandler via intent.setViolationReason() before
        // the domain transition, serialised into payload JSONB.
        // Frontend shows this as the specific reason in PolicyOutcomeCard.
        // Examples:
        //   "Retry budget exhausted — maxRetries=0 retryCount=0"
        //   "Spending ceiling hit — actual cost exceeded budget.ceilingUsd"
        String violationReason

) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BudgetDto(
            BigDecimal ceilingUsd,
            BigDecimal spentUsd,
            String     currency,
            boolean    exceeded
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ConstraintsDto(
            Integer maxRetries,
            Integer timeoutSeconds,
            Integer maxLatencyMs,
            Double  maxDriftThreshold
    ) {}

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @SuppressWarnings("unchecked")
    public static IntentDetailResponse from(IntentEntity e) {
        if (e == null) return null;

        JsonNode root = null;
        if (e.payload != null && !e.payload.isBlank()) {
            try { root = MAPPER.readTree(e.payload); }
            catch (Exception ex) {
                Log.warnf("IntentDetailResponse: failed to parse payload for intent %s: %s",
                        e.id, ex.getMessage());
            }
        }

        // ── Budget ─────────────────────────────────────────────────────────
        BudgetDto budget = null;
        JsonNode budgetNode = root != null ? root.get("budget") : null;
        if (budgetNode != null && !budgetNode.isNull()) {
            BigDecimal ceiling = bigDecimalOrNull(budgetNode.get("ceilingUsd"));
            if (ceiling != null) {
                budget = new BudgetDto(
                        ceiling,
                        bigDecimalOrDefault(budgetNode.get("spentUsd"), BigDecimal.ZERO),
                        textOrDefault(budgetNode.get("currency"), "USD"),
                        booleanOrFalse(budgetNode.get("exceeded"))
                );
            }
        }

        // ── Constraints ────────────────────────────────────────────────────
        ConstraintsDto constraints = null;
        JsonNode conNode = root != null ? root.get("constraints") : null;
        if (conNode != null && !conNode.isNull()) {
            Integer maxRetries     = intOrNull(conNode.get("maxRetries"));
            Integer timeoutSeconds = intOrNull(conNode.get("timeoutSeconds"));
            // DB stores as "maxLatency" (domain field name); payload sends as "maxLatencyMs".
            // Handle both — prefer maxLatencyMs, fall back to maxLatency.
            // Treat 0 as null (not set).
            Integer maxLatencyMs   = intOrNullNonZero(
                    conNode.has("maxLatencyMs") ? conNode.get("maxLatencyMs") : conNode.get("maxLatency")
            );
            Double  maxDrift       = doubleOrNull(conNode.get("maxDriftThreshold"));
            if (maxRetries != null || timeoutSeconds != null
                    || maxLatencyMs != null || maxDrift != null) {
                constraints = new ConstraintsDto(maxRetries, timeoutSeconds, maxLatencyMs, maxDrift);
            }
        }

        // ── Objective ──────────────────────────────────────────────────────
        Map<String, Object> objective = null;
        JsonNode objNode = root != null ? root.get("objective") : null;
        if (objNode != null && !objNode.isNull() && objNode.isObject()) {
            try {
                objective = MAPPER.convertValue(objNode,
                        MAPPER.getTypeFactory().constructMapType(
                                LinkedHashMap.class, String.class, Object.class));
            } catch (Exception ex) {
                Log.warnf("IntentDetailResponse: failed to convert objective: %s", ex.getMessage());
            }
        }

        // ── Drift score ────────────────────────────────────────────────────
        double driftScore = 0.0;
        if (root != null) {
            JsonNode ds = root.get("driftScore");
            if (ds != null && !ds.isNull() && ds.isNumber()) driftScore = ds.doubleValue();
        }

        // ── Violation reason ───────────────────────────────────────────────
        // Stored in payload.violationReason by ViolationHandler via
        // intent.setViolationReason() before the domain transition fires.
        // Null on SATISFIED intents.
        String violationReason = null;
        if (root != null) {
            JsonNode vr = root.get("violationReason");
            if (vr != null && !vr.isNull() && !vr.asText("").isBlank()) {
                violationReason = vr.asText();
            }
        }

        return new IntentDetailResponse(
                e.id,
                e.intentType,
                (int) e.version,
                e.retryCount,
                e.maxRetries,
                e.terminal,
                driftScore,
                e.phase,
                e.satisfactionState,
                e.createdAt  != null ? e.createdAt.toInstant()  : null,
                e.updatedAt  != null ? e.updatedAt.toInstant()  : null,
                budget,
                constraints,
                objective,
                violationReason
        );
    }

    private static Integer intOrNull(JsonNode n) {
        if (n == null || n.isNull()) return null;
        if (n.isNumber()) return n.intValue();
        try { return Integer.parseInt(n.asText()); } catch (Exception ex) { return null; }
    }

    /** Same as intOrNull but treats 0 as null — used for latency/window fields
     *  where 0 means "not configured" rather than a real constraint. */
    private static Integer intOrNullNonZero(JsonNode n) {
        Integer v = intOrNull(n);
        return (v != null && v == 0) ? null : v;
    }

    private static Double doubleOrNull(JsonNode n) {
        if (n == null || n.isNull()) return null;
        if (n.isNumber()) return n.doubleValue();
        try { return Double.parseDouble(n.asText()); } catch (Exception ex) { return null; }
    }

    private static BigDecimal bigDecimalOrNull(JsonNode n) {
        if (n == null || n.isNull()) return null;
        try { return new BigDecimal(n.asText()); } catch (Exception ex) { return null; }
    }

    private static BigDecimal bigDecimalOrDefault(JsonNode n, BigDecimal def) {
        BigDecimal v = bigDecimalOrNull(n);
        return v != null ? v : def;
    }

    private static String textOrDefault(JsonNode n, String def) {
        if (n == null || n.isNull()) return def;
        String t = n.asText(null);
        return (t != null && !t.isBlank()) ? t : def;
    }

    private static boolean booleanOrFalse(JsonNode n) {
        if (n == null || n.isNull()) return false;
        return n.asBoolean(false);
    }
}