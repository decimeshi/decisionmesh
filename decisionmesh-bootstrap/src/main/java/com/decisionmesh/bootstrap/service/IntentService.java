package com.decisionmesh.bootstrap.service;

import com.decisionmesh.bootstrap.dto.ExecutionRecordResponse;
import com.decisionmesh.bootstrap.dto.IntentDetailResponse;
import com.decisionmesh.bootstrap.dto.IntentEventResponse;
import com.decisionmesh.bootstrap.dto.IntentResponse;
import com.decisionmesh.llm.persistence.ExecutionRecordRepository;
import com.decisionmesh.persistence.entity.IntentEntity;
import com.decisionmesh.persistence.entity.IntentEventEntity;
import com.decisionmesh.persistence.repository.IntentRepository;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class IntentService {

    @Inject
    IntentRepository intentRepository;

    @Inject
    ExecutionRecordRepository executionRecordRepository;

    // ─────────────────────────────────────────────────────────────
    // Get single intent — Budget, Constraints, Objective cards
    //
    // Returns IntentDetailResponse which includes:
    //   - phase, satisfactionState, retryCount, driftScore (status card)
    //   - budget { ceilingUsd, spentUsd, currency, exceeded }  (budget card)
    //   - constraints { maxRetries, timeoutSeconds,
    //                   maxLatencyMs, maxDriftThreshold }      (constraints card)
    //   - objective { description, ... }                       (objective card)
    //
    // All fields are nullable-safe: if the intent was submitted without a
    // budget or constraints block the DTO fields will be null and the UI
    // renders the empty-state card instead of crashing.
    // ─────────────────────────────────────────────────────────────

    public Uni<IntentDetailResponse> getIntent(UUID tenantId, UUID intentId) {
        return intentRepository
                .findByIdAndTenant(intentId, tenantId)
                .map(IntentDetailResponse::from);
    }

    // ─────────────────────────────────────────────────────────────
    // Get raw intent entity (used by orchestrator / internal callers)
    // ─────────────────────────────────────────────────────────────

    public Uni<IntentEntity> getIntentEntity(UUID tenantId, UUID intentId) {
        return intentRepository.findByIdAndTenant(intentId, tenantId);
    }

    // ─────────────────────────────────────────────────────────────
    // Get execution records by intent — Decision Output card
    //
    // Called by GET /api/executions/by-intent/{intentId}
    // Returns all execution attempts for this intent, ordered by
    // attempt number ascending. The UI uses the first COMPLETED/SUCCESS
    // record to display:
    //   - responseText   — the raw adapter output
    //   - qualityScore   — 0.0–1.0 quality rating
    //   - hallucinationRisk / hallucinationDetected
    //   - qualityReasoning — the scorer's explanation
    //   - latencyMs / costUsd / token counts
    //
    // Security: tenantId is extracted from the JWT by the Resource and
    // passed here — execution records are never returned across tenants.
    // ─────────────────────────────────────────────────────────────

    public Uni<List<ExecutionRecordResponse>> getExecutionsByIntent(
            UUID tenantId, UUID intentId) {

        return executionRecordRepository
                .findByIntentId(tenantId, intentId)
                .map(rows -> rows.stream()
                        .map(ExecutionRecordResponse::from)
                        .toList());
    }

    // ─────────────────────────────────────────────────────────────
    // Get intent events — serves GET /api/intents/{id}/events
    //
    // Restored after being dropped when the service layer was introduced.
    // ExecutionTimeline.jsx polls this endpoint every 5 seconds and maps
    // each IntentEventDto.eventType to a phase badge on the timeline.
    // Without this method the endpoint returned 404 and the UI showed
    // the intent as permanently stuck at CREATED.
    // ─────────────────────────────────────────────────────────────

    public Uni<List<IntentEventResponse>> getIntentEvents(
            UUID tenantId, UUID intentId) {

        return IntentEventEntity
                .findByTenantAndIntent(tenantId, intentId)
                .map(events -> events.stream()
                        .map(IntentEventResponse::from)
                        .toList());
    }

    // ─────────────────────────────────────────────────────────────
    // Paginated list
    // ─────────────────────────────────────────────────────────────

    public Uni<IntentResponse> getIntents(
            UUID tenantId,
            String phase,
            String sortField,
            String sortDir,
            int pageIndex,
            int pageSize) {

        Uni<List<IntentEntity>> dataUni =
                intentRepository.findPageByTenant(
                        tenantId, phase, sortField, sortDir, pageIndex, pageSize);

        Uni<Long> countUni =
                (phase != null && !phase.isBlank())
                        ? intentRepository.countByTenantAndPhase(tenantId, phase)
                        : intentRepository.countByTenant(tenantId);

        return Uni.combine().all().unis(dataUni, countUni)
                .asTuple()
                .map(tuple -> new IntentResponse(
                        tuple.getItem1(),
                        tuple.getItem2(),
                        pageIndex,
                        pageSize
                ));
    }

    // ─────────────────────────────────────────────────────────────
    // Delete
    // ─────────────────────────────────────────────────────────────

    public Uni<Boolean> deleteIntent(UUID tenantId, UUID intentId) {
        return Panache.withTransaction(() ->
                intentRepository.findByIdAndTenant(intentId, tenantId)
                        .onItem().ifNull().failWith(() ->
                                new RuntimeException("Intent not found"))
                        .flatMap(entity -> entity.delete())
                        .replaceWith(true)
        );
    }
}
