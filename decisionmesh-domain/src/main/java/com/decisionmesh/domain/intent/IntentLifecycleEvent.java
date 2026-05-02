package com.decisionmesh.domain.intent;

import com.decisionmesh.domain.event.DomainEvent;
import com.decisionmesh.domain.event.IntentEventType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class IntentLifecycleEvent implements DomainEvent {

    private final UUID eventId;
    private final UUID intentId;
    private final UUID tenantId;
    private final long version;
    private final IntentEventType eventType;
    private final IntentPhase fromPhase;
    private final IntentPhase toPhase;
    private final Instant occurredAt;

    public IntentLifecycleEvent(UUID eventId,
                                UUID intentId,
                                UUID tenantId,
                                long version,
                                IntentEventType eventType,
                                IntentPhase fromPhase,
                                IntentPhase toPhase,
                                Instant occurredAt) {

        this.eventId = Objects.requireNonNull(eventId);
        this.intentId = Objects.requireNonNull(intentId);
        this.tenantId = Objects.requireNonNull(tenantId);
        this.version = version;
        this.eventType = Objects.requireNonNull(eventType);
        this.fromPhase = Objects.requireNonNull(fromPhase);
        this.toPhase = Objects.requireNonNull(toPhase);
        this.occurredAt = Objects.requireNonNull(occurredAt);
    }

    @Override
    public UUID eventId() {
        return eventId;
    }

    @Override
    public UUID aggregateId() {
        return intentId;
    }

    @Override
    public String aggregateType() {
        return "Intent";
    }

    @Override
    public UUID tenantId() {
        return tenantId;
    }

    @Override
    public long version() {
        return version;
    }

    @Override
    public IntentEventType eventType() {
        return eventType;
    }

    @Override
    public Instant occurredAt() {
        return occurredAt;
    }

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @Override
    public Map<String, Object> toJson() {
        return MAPPER.convertValue(this, new TypeReference<Map<String, Object>>() {});
    }

    public IntentPhase fromPhase() {
        return fromPhase;
    }

    public IntentPhase toPhase() {
        return toPhase;
    }
}
