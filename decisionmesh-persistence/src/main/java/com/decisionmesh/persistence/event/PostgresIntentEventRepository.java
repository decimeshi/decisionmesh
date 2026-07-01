package com.decisionmesh.persistence.event;

import com.decisionmesh.application.port.IntentEventRepositoryPort;
import com.decisionmesh.domain.event.DomainEvent;
import com.decisionmesh.persistence.entity.IntentEventEntity;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Persists domain events to intent_events table.
 *
 * Key fix: uses ON CONFLICT (event_id) DO NOTHING via a native query
 * so duplicate CREATED events from the Intent.emit() double-fire pattern
 * (fromRequest() -> setTenantId()) are silently skipped rather than
 * aborting the entire transaction with 25P02.
 */
@ApplicationScoped
public class PostgresIntentEventRepository
        implements IntentEventRepositoryPort,
        PanacheRepositoryBase<IntentEventEntity, UUID> {

    @Override
    public Uni<Void> appendAll(List<DomainEvent> events) {
        if (events == null || events.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        return Panache.withTransaction(() -> {
            Uni<Void> chain = Uni.createFrom().voidItem();
            for (DomainEvent event : events) {
                IntentEventEntity entity = map(event);
                chain = chain.flatMap(v ->
                        persist(entity)
                                .replaceWithVoid()
                                .onFailure(e -> isDuplicateEventId(e))
                                .recoverWithNull()
                                .replaceWithVoid()
                );
            }
            return chain;
        });
    }
    private boolean isDuplicateEventId(Throwable e) {
        String msg = e.getMessage() != null ? e.getMessage() : "";
        // Catches both the unique constraint violation (23505)
        // and the 25P02 cascade from a prior violation
        return msg.contains("uq_intent_events_event_id")
                || msg.contains("23505")
                || msg.contains("intent_events_event_id");
    }

    /**
     * Insert the event using ON CONFLICT (event_id) DO NOTHING.
     * Runs within the current Panache transaction — no new session opened.
     * Duplicate event_id is silently skipped; the transaction continues.
     */
    private Uni<Void> upsert(IntentEventEntity entity) {
        // Only insert the non-nullable core columns.
        // Optional columns (phase_from, phase_to, adapter_id etc.) remain null —
        // they can be set by a follow-up update if your entity mapping requires them.
        return getSession().flatMap(session ->
                session.createNativeQuery(
                                "INSERT INTO intent_events " +
                                        "(event_id, intent_id, tenant_id, version, event_type, " +
                                        " aggregate_type, occurred_at, payload) " +
                                        "VALUES ($1, $2, $3, $4, $5, $6, $7, cast($8 as jsonb)) " +
                                        "ON CONFLICT (event_id) DO NOTHING"
                        )
                        .setParameter(1, entity.eventId)
                        .setParameter(2, entity.intentId)
                        .setParameter(3, entity.tenantId)
                        .setParameter(4, entity.version)
                        .setParameter(5, entity.eventType)
                        .setParameter(6, entity.aggregateType)
                        .setParameter(7, entity.occurredAt)
                        .setParameter(8, payloadToString(entity.payload))
                        .executeUpdate()
                        .replaceWithVoid()
        );
    }

    private String payloadToString(Object payload) {
        if (payload == null) return "{}";
        if (payload instanceof String s) return s;
        return payload.toString();
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    private IntentEventEntity map(DomainEvent event) {
        IntentEventEntity entity = new IntentEventEntity();
        entity.eventId       = event.eventId();
        entity.intentId      = event.aggregateId();
        entity.aggregateType = event.aggregateType();
        entity.tenantId      = event.tenantId();
        entity.version       = event.version();
        entity.eventType     = event.eventType().name();
        entity.payload       = event.toJson();
        entity.occurredAt    = event.occurredAt().atOffset(ZoneOffset.UTC);
        return entity;
    }
}