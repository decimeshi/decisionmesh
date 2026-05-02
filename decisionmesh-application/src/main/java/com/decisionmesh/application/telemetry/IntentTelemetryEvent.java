package com.decisionmesh.application.telemetry;

import com.decisionmesh.domain.intent.IntentPhase;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.time.Instant;
import java.util.UUID;

public final class IntentTelemetryEvent {

    private final UUID tenantId;
    private final UUID intentId;
    private final long version;
    private final IntentPhase phase;
    private final Instant timestamp;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    public IntentTelemetryEvent(UUID tenantId,
                                UUID intentId,
                                long version,
                                IntentPhase phase) {
        this.tenantId = tenantId;
        this.intentId = intentId;
        this.version = version;
        this.phase = phase;
        this.timestamp = Instant.now();
    }

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize telemetry event", e);
        }
    }

    public UUID getTenantId() { return tenantId; }
    public UUID getIntentId() { return intentId; }
    public long getVersion() { return version; }
    public IntentPhase getPhase() { return phase; }
    public Instant getTimestamp() { return timestamp; }
}