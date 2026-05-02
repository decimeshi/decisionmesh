package com.decisionmesh.bootstrap.dto;


import com.decisionmesh.persistence.entity.IntentEventEntity;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only API projection of {@link IntentEventEntity}.
 *
 * <p>Returned as a list by {@code GET /intents/{intentId}/events}.
 * Every field that is nullable in the entity is excluded from the
 * serialised JSON when {@code null}, keeping the response lean for
 * simple phase-transition events while still carrying full context
 * for execution, policy, and observability events.
 *
 * <p>Fields are grouped in the same order they appear on the entity
 * to make audits of the mapping straightforward:
 * <ol>
 *   <li>Identity &amp; event-store metadata</li>
 *   <li>Phase transition</li>
 *   <li>Actor</li>
 *   <li>Execution context (plan, execution record, adapter)</li>
 *   <li>Policy context</li>
 *   <li>Metric snapshots captured at event time</li>
 *   <li>Distributed-tracing correlation</li>
 *   <li>Arbitrary payload</li>
 * </ol>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IntentEventResponse(

        // ── 1. Identity & event-store metadata ───────────────────────────

        /** Surrogate PK of the {@code intent_events} row. */
        UUID id,

        /**
         * Logical event ID from the event store — unique across all events
         * regardless of aggregate. Use this for idempotency checks on the
         * consumer side.
         */
        @JsonProperty("eventId")
        UUID eventId,

        /** Parent intent this event belongs to. */
        @JsonProperty("intentId")
        UUID intentId,

        /** Tenant that owns the parent intent. */
        @JsonProperty("tenantId")
        UUID tenantId,

        /**
         * Monotonically increasing version of the intent aggregate at the
         * time this event was appended. Version 1 = first event (CREATED).
         * Useful for optimistic-concurrency checks and ordered replay.
         */
        long version,

        /**
         * Fully-qualified domain event type.
         * Examples: {@code IntentCreated}, {@code PhaseTransitioned},
         * {@code ExecutionStarted}, {@code PolicyViolated}, {@code IntentCompleted}.
         */
        @JsonProperty("eventType")
        String eventType,

        /**
         * Aggregate root that owns this event.
         * Always {@code "Intent"} for this endpoint; included so consumers
         * that process mixed event streams can route without inspecting
         * {@code eventType}.
         */
        @JsonProperty("aggregateType")
        String aggregateType,

        /**
         * UTC timestamp at which the event was appended to the store.
         * Serialised as ISO-8601 with offset, e.g. {@code 2025-04-02T09:41:02Z}.
         */
        @JsonProperty("occurredAt")
        OffsetDateTime occurredAt,

        // ── 2. Phase transition ───────────────────────────────────────────

        /**
         * Lifecycle phase the intent was in before this event.
         * {@code null} on the initial {@code IntentCreated} event — there
         * is no prior phase when the intent is first created.
         */
        @JsonProperty("phaseFrom")
        String phaseFrom,

        /**
         * Lifecycle phase the intent entered as a result of this event.
         * One of: {@code CREATED}, {@code PLANNING}, {@code PLANNED},
         * {@code EXECUTING}, {@code EVALUATING}, {@code COMPLETED},
         * {@code RETRY_SCHEDULED}, {@code FAILED}, {@code BLOCKED}.
         * {@code null} for non-transition events (e.g. {@code PolicyEvaluated}).
         */
        @JsonProperty("phaseTo")
        String phaseTo,

        // ── 3. Actor ─────────────────────────────────────────────────────

        /**
         * UUID of the user or service principal that caused this event.
         * {@code null} for autonomous engine transitions where no human
         * actor is involved.
         */
        @JsonProperty("actorId")
        UUID actorId,

        /**
         * Discriminator for the actor identity.
         * Typical values: {@code "USER"}, {@code "SYSTEM"}, {@code "POLICY_ENGINE"},
         * {@code "SCHEDULER"}.
         */
        @JsonProperty("actorType")
        String actorType,

        // ── 4. Execution context ──────────────────────────────────────────

        /**
         * UUID of the {@code intent_plans} row that was active when this
         * event occurred. {@code null} before the planning phase completes.
         */
        @JsonProperty("planId")
        UUID planId,

        /**
         * Snapshot of the plan's version at event time. Allows replay to
         * reconstruct the exact plan that was in use, even if the plan was
         * subsequently revised.
         */
        @JsonProperty("planVersion")
        Integer planVersion,

        /**
         * UUID of the {@code execution_records} row associated with this
         * event. Present on execution-phase events
         * ({@code ExecutionStarted}, {@code ExecutionCompleted},
         * {@code ExecutionFailed}); {@code null} otherwise.
         */
        @JsonProperty("executionId")
        UUID executionId,

        /**
         * 1-based attempt counter for the execution referenced by
         * {@link #executionId}. {@code null} when {@link #executionId}
         * is {@code null}.
         */
        @JsonProperty("attemptNumber")
        Integer attemptNumber,

        /**
         * UUID of the adapter that was invoked for this execution attempt.
         * {@code null} on non-execution events.
         */
        @JsonProperty("adapterId")
        UUID adapterId,

        // ── 5. Policy context ─────────────────────────────────────────────

        /**
         * UUID of the policy that triggered this event, when the event type
         * is {@code PolicyViolated}, {@code PolicyFallback}, or
         * {@code PolicyRetry}. {@code null} for all other event types.
         */
        @JsonProperty("policyId")
        UUID policyId,

        // ── 6. Metric snapshots ───────────────────────────────────────────

        /**
         * Drift score recorded at the moment this event was appended
         * (0.0000–1.0000). Captured so dashboards can plot how drift evolved
         * across the intent's lifetime without joining to the evaluations
         * table. {@code null} if no drift score was available at event time.
         */
        @JsonProperty("driftScoreSnapshot")
        BigDecimal driftScoreSnapshot,

        /**
         * Accumulated USD cost of the intent at the time of this event.
         * Updated on every {@code ExecutionCompleted} and {@code RetryScheduled}
         * event. {@code null} before the first execution attempt.
         */
        @JsonProperty("costUsdSnapshot")
        BigDecimal costUsdSnapshot,

        /**
         * Risk score snapshot at event time (0.0000–1.0000).
         * Populated by the quality scorer on {@code EvaluationCompleted}
         * events. {@code null} on earlier lifecycle events.
         */
        @JsonProperty("riskScoreSnapshot")
        BigDecimal riskScoreSnapshot,

        // ── 7. Distributed-tracing correlation ───────────────────────────

        /**
         * OpenTelemetry / W3C trace ID propagated from the originating
         * HTTP request. Allows correlating this event with spans in your
         * observability backend (Jaeger, Tempo, Honeycomb, etc.).
         * {@code null} when the event was generated by an async background
         * job outside an active trace context.
         */
        @JsonProperty("traceId")
        String traceId,

        /**
         * OpenTelemetry span ID of the unit of work that produced this event.
         */
        @JsonProperty("spanId")
        String spanId,

        /**
         * Parent span ID, enabling reconstruction of the full call tree
         * for a given trace. {@code null} for root spans.
         */
        @JsonProperty("parentSpanId")
        String parentSpanId,

        // ── 8. Arbitrary event payload ────────────────────────────────────

        /**
         * Free-form JSONB payload whose schema is defined by {@link #eventType}.
         * Consumers should inspect {@link #eventType} to determine which
         * keys to expect. Never {@code null} — guaranteed by the entity's
         * {@code nullable = false} constraint.
         */
        Map<String, Object> payload

) {

    // ─────────────────────────────────────────────────────────────────────
    // Factory
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Maps an {@link IntentEventEntity} to its DTO representation.
     *
     * <p>All fields are accessed directly (public Panache-style fields);
     * no getters required.
     *
     * @param e the entity loaded from {@code intent_events}; must not be {@code null}
     * @return an immutable {@code IntentEventDto} with every field populated
     *         from the entity, nullable fields left as {@code null} where absent
     */
    public static IntentEventResponse from(IntentEventEntity e) {
        return new IntentEventResponse(
                // 1. Identity & event-store metadata
                e.id,
                e.eventId,
                e.intentId,
                e.tenantId,
                e.version,
                e.eventType,
                e.aggregateType,
                e.occurredAt,
                // 2. Phase transition
                e.phaseFrom,
                e.phaseTo,
                // 3. Actor
                e.actorId,
                e.actorType,
                // 4. Execution context
                e.planId,
                e.planVersion,
                e.executionId,
                e.attemptNumber,
                e.adapterId,
                // 5. Policy context
                e.policyId,
                // 6. Metric snapshots
                e.driftScoreSnapshot,
                e.costUsdSnapshot,
                e.riskScoreSnapshot,
                // 7. Distributed-tracing correlation
                e.traceId,
                e.spanId,
                e.parentSpanId,
                // 8. Payload
                e.payload
        );
    }
}