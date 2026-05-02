package com.decisionmesh.application.port;


import com.decisionmesh.domain.event.DomainEvent;
import com.decisionmesh.streaming.outbox.OutboxEvent;
import com.decisionmesh.streaming.outbox.ReactiveOutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Implements OutboxPort using ReactiveOutboxRepository.
 * Lives in decisionmesh-streaming — bridges application and streaming modules.
 * Converts DomainEvent → OutboxEvent and delegates to the reactive repository.
 */
@ApplicationScoped
public class OutboxPortAdapter implements OutboxPort {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    ReactiveOutboxRepository repository;

    @Override
    public Uni<Void> publish(List<DomainEvent> events) {
        if (events == null || events.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        List<OutboxEvent> outboxEvents = events.stream().map(event -> {
            String payload;
            try {
                payload = MAPPER.writeValueAsString(event.toJson());
            } catch (Exception ex) {
                Log.warnf("[OutboxPortAdapter] Serialization failed for event=%s: %s",
                        event.eventType(), ex.getMessage());
                payload = "{\"error\":\"serialization_failed\"}";
            }
            return new OutboxEvent(
                    UUID.randomUUID(),
                    event.aggregateType(),
                    event.aggregateId(),
                    event.eventType().name(),
                    payload,
                    false,
                    Instant.now()
            );
        }).toList();

        return repository.insertAll(outboxEvents)
                .onFailure().invoke(ex ->
                        Log.warnf("[OutboxPortAdapter] insertAll failed: %s", ex.getMessage()));
    }
}
