package com.decisionmesh.streaming.publisher;

import com.decisionmesh.domain.event.DomainEvent;
import io.smallrye.mutiny.Uni;

public interface DomainEventPublisher {

    Uni<Void> publish(DomainEvent event);

}