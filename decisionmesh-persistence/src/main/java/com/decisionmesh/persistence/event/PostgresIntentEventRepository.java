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
                chain = chain.flatMap(v -> persist(entity).replaceWithVoid());
            }
            return chain;
        });
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

        // ✓ Instant.atOffset(UTC) — not OffsetDateTime.from(Instant)
        // OffsetDateTime.from() requires a ZoneOffset in the TemporalAccessor,
        // which Instant does not carry. atOffset() explicitly attaches +00:00.
        entity.occurredAt    = event.occurredAt().atOffset(ZoneOffset.UTC);

        return entity;
    }
}