package com.decisionmesh.application.port;

import com.decisionmesh.domain.execution.ExecutionRecord;
import com.decisionmesh.domain.intent.Intent;
import io.smallrye.mutiny.Uni;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Port for execution record persistence and queries.
 *
 * Lives in:    decisionmesh-application (application layer)
 * Implemented: decisionmesh-infrastructure (ExecutionRecordRepository)
 *
 * Write methods (save, updateQualityScore) are called by the orchestrator
 * to persist execution results to execution_records table.
 *
 * Read methods (findByIntentId, listByTenant, etc.) are called by
 * ExecutionResource and DriftResource.
 */
public interface ExecutionRecordQueryPort {

    // =========================================================================
    // Write — called by ControlPlaneOrchestrator
    // =========================================================================

    /**
     * Synchronous blocking persist — called directly via executor, not via Mutiny.
     * Use this from ControlPlaneOrchestrator via Infrastructure.getDefaultWorkerPool().execute()
     * to avoid HR000068 thread violations when mixing JDBC with Hibernate Reactive.
     */
    void persistBlocking(ExecutionRecord record, Intent intent);

    /**
     * Persists an execution record to the execution_records table.
     *
     * Called after quality scoring completes so the persisted row contains
     * the full record including responseText, qualityScore, hallucinationRisk,
     * hallucinationDetected, and qualityReasoning — all fields shown in the
     * Decision Output card in IntentDetail.jsx.
     *
     * Must run on a worker thread (blocking JDBC) — the implementation
     * uses runSubscriptionOn(Infrastructure.getDefaultWorkerPool()).
     *
     * @param record the execution record returned by ExecutionEngine.execute()
     *               and optionally enriched by OutputQualityScorerService.withQuality()
     * @param intent the parent intent — provides tenantId and budget ceiling
     *               for the spend_records INSERT
     */
    Uni<Void> save(ExecutionRecord record, Intent intent);

    /**
     * Updates quality scoring fields on an existing execution record.
     * Called when quality scoring runs asynchronously after the initial save.
     * Populates the Decision Output card quality metrics in IntentDetail.jsx.
     */
    Uni<Void> updateQualityScore(UUID executionId, UUID tenantId,
                                 BigDecimal qualityScore,
                                 BigDecimal hallucinationRisk,
                                 boolean hallucinationDetected,
                                 String qualityReasoning);

    // =========================================================================
    // Drift evaluation — used by DriftEvaluatorService
    // =========================================================================

    Uni<List<DriftRow>> findRecentByAdapter(String adapterId, Instant since);

    // =========================================================================
    // UI queries — used by ExecutionResource and DriftResource
    // =========================================================================

    Uni<List<ExecutionRow>> listByTenant(UUID tenantId, int limit,
                                         String phase, String adapterId);

    Uni<List<ExecutionRow>> findByIntentId(UUID tenantId, UUID intentId);

    Uni<List<AdapterDriftSummary>> getDriftSummary(UUID tenantId, int days);

    Uni<List<DriftTrendPoint>> getDriftTrend(UUID tenantId, int days);

    // =========================================================================
    // Records (data carriers)
    // =========================================================================

    /**
     * Full execution row for the UI — all columns the Decision Output card needs.
     *
     * Field order (19 fields) must match ExecutionRecordRepository constructor calls.
     */
    record ExecutionRow(
            UUID       id,
            UUID       intentId,
            UUID       adapterId,
            String     adapterName,
            int        attemptNumber,
            String     status,
            BigDecimal costUsd,
            long       latencyMs,
            int        promptTokens,
            int        completionTokens,
            int        totalTokens,
            BigDecimal riskScore,
            BigDecimal qualityScore,
            BigDecimal hallucinationRisk,
            boolean    hallucinationDetected,
            String     failureReason,
            String     responseText,
            String     qualityReasoning,
            Instant    executedAt
    ) {}

    record AdapterDriftSummary(
            String  adapterId,
            String  adapterName,
            double  avgDriftScore,
            double  avgCostUsd,
            double  avgLatencyMs,
            double  avgQualityScore,
            double  failureRate,
            long    executionCount,
            Instant lastExecutedAt
    ) {}

    record DriftTrendPoint(
            Instant date,
            double  avgDrift,
            double  avgCost,
            double  avgLatency,
            long    executionCount
    ) {}

    record DriftRow(
            UUID       id,
            String     status,
            BigDecimal costUsd,
            long       latencyMs,
            BigDecimal qualityScore,
            Instant    timestamp,
            String responseText) {
        public boolean isFailed() {
            return !"SUCCESS".equalsIgnoreCase(status);
        }
    }
}