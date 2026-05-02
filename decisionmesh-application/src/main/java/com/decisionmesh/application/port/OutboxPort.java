package com.decisionmesh.application.port;


import com.decisionmesh.domain.event.DomainEvent;
import io.smallrye.mutiny.Uni;

import java.util.List;

/**
 * Port for writing domain events to the transactional outbox.
 *
 * Implemented by decisionmesh-streaming's OutboxPortAdapter,
 * which writes to event_outbox via ReactiveOutboxRepository.
 *
 * Keeps decisionmesh-application free of streaming dependencies —
 * the orchestrator only knows about domain events and this port,
 * not about OutboxEvent, PgPool, or Kafka topology.
 */
public interface OutboxPort {

    /**
     * Write domain events to the outbox for async relay.
     * Non-fatal — callers should recover on failure.
     */
    Uni<Void> publish(List<DomainEvent> events);
}
