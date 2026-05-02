package com.decisionmesh.domain.event;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public interface DomainEvent {

    UUID            tenantId();
    UUID            eventId();
    IntentEventType eventType();
    long            version();
    UUID            aggregateId();
    String          aggregateType();
    Instant         occurredAt();

    /**
     * Serialises this event to a Map for storage in the
     * intent_events.payload JSONB column.
     *
     * Returns Map<String, Object> instead of String so that:
     *  - persistence layer can store it directly via @JdbcTypeCode(SqlTypes.JSON)
     *    without a double-serialisation round-trip
     *  - callers that need a JSON string can use Jackson:
     *    MAPPER.writeValueAsString(event.toJson())
     */
    Map<String, Object> toJson();

}