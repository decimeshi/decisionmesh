package com.decisionmesh.domain.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class IntentStateChangedEvent implements DomainEvent {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private final UUID           eventId;
    private final UUID           intentId;
    private final UUID           tenantId;
    private final long           version;
    private final IntentEventType eventType;
    private final Instant        occurredAt;

    public IntentStateChangedEvent(
            UUID            eventId,
            UUID            intentId,
            UUID            tenantId,
            long            version,
            IntentEventType eventType,
            Instant         occurredAt) {

        this.eventId    = eventId;
        this.intentId   = intentId;
        this.tenantId   = tenantId;
        this.version    = version;
        this.eventType  = eventType;
        this.occurredAt = occurredAt;
    }

    // ── DomainEvent ───────────────────────────────────────────────────────────

    @Override public UUID            eventId()       { return eventId; }
    @Override public UUID            aggregateId()   { return intentId; }
    @Override public String          aggregateType() { return "Intent"; }
    @Override public UUID            tenantId()      { return tenantId; }
    @Override public long            version()       { return version; }
    @Override public IntentEventType eventType()     { return eventType; }
    @Override public Instant         occurredAt()    { return occurredAt; }

    /**
     * Serialises this event to a JSON string for the intent_events.payload JSONB column.
     *
     * Uses a LinkedHashMap to preserve insertion order in the output, making
     * logs and debugging easier. Replaces the Vert.x JsonObject.encode() chain.
     *
     * aggregateId is kept as an alias for intentId so downstream consumers
     * that read either field continue to work without changes.
     */
    @Override
    public Map<String, Object> toJson() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("eventId",       eventId    != null ? eventId.toString()    : null);
        map.put("intentId",      intentId   != null ? intentId.toString()   : null);
        map.put("aggregateId",   intentId   != null ? intentId.toString()   : null);
        map.put("aggregateType", "Intent");
        map.put("tenantId",      tenantId   != null ? tenantId.toString()   : null);
        map.put("version",       version);
        map.put("eventType",     eventType  != null ? eventType.name()      : null);
        map.put("occurredAt",    occurredAt != null ? occurredAt.toString() : null);
        return map;
    }

    // ── equals / hashCode ─────────────────────────────────────────────────────

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IntentStateChangedEvent that)) return false;
        return version == that.version
                && Objects.equals(eventId,    that.eventId)
                && Objects.equals(intentId,   that.intentId)
                && Objects.equals(tenantId,   that.tenantId)
                && eventType == that.eventType
                && Objects.equals(occurredAt, that.occurredAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(eventId, intentId, tenantId, version, eventType, occurredAt);
    }
}