package com.decisionmesh.streaming.consumer;

import io.smallrye.mutiny.Uni;

public interface IdempotentConsumerGuard {

    Uni<Boolean> alreadyProcessed(String eventId);

    Uni<Void> markProcessed(String eventId);
}