package com.decisionmesh.governance.service;

import com.decisionmesh.governance.entity.ProcessedEvent;
import com.decisionmesh.governance.repository.IdempotencyRepository;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;

@ApplicationScoped
public class IdempotencyService {

    @Inject
    IdempotencyRepository repo;

    /**
     * Returns true if this eventId has already been processed.
     * Looks up by the String eventId field — not a UUID PK.
     */
    @WithTransaction
    public Uni<Boolean> isProcessed(String eventId) {
        return repo.findByEventId(eventId)
                .map(e -> e != null);
    }

    /**
     * Marks an eventId as processed by persisting a ProcessedEvent record.
     */
    @WithTransaction
    public Uni<Void> markProcessed(String eventId) {
        ProcessedEvent event = new ProcessedEvent();
        event.eventId    = eventId;
        event.processedAt = Instant.now();
        return repo.persist(event).replaceWithVoid();
    }
}