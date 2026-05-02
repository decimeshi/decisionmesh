package com.decisionmesh.streaming.outbox;

import java.time.Instant;
import java.util.UUID;

public class OutboxEvent {

    private final UUID id;
    private final String aggregateType;
    private final UUID aggregateId;
    private final String eventType;
    private final String payloadJson;
    private final boolean published;
    private final Instant createdAt;

    public OutboxEvent(UUID id,
                       String aggregateType,
                       UUID aggregateId,
                       String eventType,
                       String payloadJson,
                       boolean published,
                       Instant createdAt) {
        this.id = id;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payloadJson = payloadJson;
        this.published = published;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public String getAggregateType() { return aggregateType; }
    public UUID getAggregateId() { return aggregateId; }
    public String getEventType() { return eventType; }
    public String getPayloadJson() { return payloadJson; }
    public boolean isPublished() { return published; }
}