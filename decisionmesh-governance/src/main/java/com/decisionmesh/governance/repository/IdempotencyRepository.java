package com.decisionmesh.governance.repository;

import com.decisionmesh.common.repository.ReactivePanacheRepository;
import com.decisionmesh.governance.entity.ProcessedEvent;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class IdempotencyRepository
        implements ReactivePanacheRepository<ProcessedEvent> {

    public Uni<ProcessedEvent> findByEventId(String eventId) {
        return find("eventId", eventId).firstResult();
    }
}