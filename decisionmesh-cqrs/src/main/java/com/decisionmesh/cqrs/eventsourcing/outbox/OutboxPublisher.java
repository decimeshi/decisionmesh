package com.decisionmesh.cqrs.eventsourcing.outbox;

import io.smallrye.mutiny.Uni;

public interface OutboxPublisher {

    Uni<Void> publishPending();

}