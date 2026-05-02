package com.decisionmesh.cqrs.eventsourcing.store;

import com.decisionmesh.domain.event.DomainEvent;
import io.smallrye.mutiny.Uni;
import java.util.List;
import java.util.UUID;

public interface EventStore {

    Uni<Void> append(UUID aggregateId, long expectedVersion, List<DomainEvent> events);

    Uni<List<DomainEvent>> load(UUID aggregateId);

}