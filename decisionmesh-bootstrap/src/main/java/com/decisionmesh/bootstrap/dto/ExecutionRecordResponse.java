package com.decisionmesh.bootstrap.dto;

import com.decisionmesh.application.port.ExecutionRecordQueryPort;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * DTO for a single execution attempt — used by the Decision Output card
 * on IntentDetail.jsx.
 *
 * Served by GET /api/executions/by-intent/{intentId}
 * Returns a list ordered by attemptNumber ascending.
 * The UI picks the first record with status COMPLETED or SUCCESS.
 *
 * Fields mapped to UI:
 *
 *   Decision Output card:
 *     status              — shown as badge on the card header
 *     qualityScore        — 0.0–1.0 → shown as percentage
 *     hallucinationRisk   — 0.0–1.0 → shown as percentage
 *     hallucinationDetected — triggers red warning banner
 *     qualityReasoning    — shown in purple reasoning box
 *     latencyMs           — shown in metrics row
 *     costUsd             — shown in metrics row
 *     promptTokens/completionTokens/totalTokens — token count row
 *     responseText        — collapsible adapter response panel
 *
 *   Adapter card (supplementary):
 *     adapterId           — resolved to adapter name via listAdapters()
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExecutionRecordResponse(

        UUID       id,
        UUID       intentId,
        UUID       adapterId,
        String     adapterName,         // joined from adapters table
        int        attemptNumber,
        String     status,              // COMPLETED | FAILED | TIMEOUT

        // ── Quality scoring ───────────────────────────────────────────────────
        BigDecimal qualityScore,        // null until EVALUATING phase completes
        BigDecimal hallucinationRisk,   // null until EVALUATING phase completes
        boolean    hallucinationDetected,
        String     qualityReasoning,    // null until EVALUATING phase completes

        // ── Execution metrics ─────────────────────────────────────────────────
        long       latencyMs,
        BigDecimal costUsd,
        Integer    promptTokens,        // null for adapters that don't report tokens
        Integer    completionTokens,
        Integer    totalTokens,
        BigDecimal riskScore,
        String     failureReason,       // null on success

        // ── AI response ───────────────────────────────────────────────────────
        // The raw text returned by the adapter.
        // null when the adapter is a mock or did not store responseText.
        // UI shows "Response text not available" in this case.
        String     responseText,

        Instant    executedAt

) {

    // ── Factory from ExecutionRow ─────────────────────────────────────────────

    /**
     * Maps an ExecutionRow (from ExecutionRecordRepository.findByIntentId)
     * to the response DTO.
     *
     * ExecutionRow is the lightweight read-side record used by the repository.
     * It contains all fields the UI needs for the Decision Output card.
     */
    public static ExecutionRecordResponse from(ExecutionRecordQueryPort.ExecutionRow row) {
        return new ExecutionRecordResponse(
                row.id(),
                row.intentId(),
                row.adapterId(),
                row.adapterName(),
                row.attemptNumber(),
                row.status(),
                row.qualityScore(),
                row.hallucinationRisk(),
                Boolean.TRUE.equals(row.hallucinationDetected()),
                row.qualityReasoning(),
                row.latencyMs(),
                row.costUsd(),
                row.promptTokens(),
                row.completionTokens(),
                row.totalTokens(),
                row.riskScore(),
                row.failureReason(),
                row.responseText(),
                row.executedAt()
        );
    }
}
