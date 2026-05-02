package com.decisionmesh.application.telemetry;

import com.decisionmesh.domain.intent.IntentPhase;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.util.UUID;

@ApplicationScoped
public class IntentCentricTelemetryPublisher implements TelemetryPublisher {

    private final TelemetrySink sink;

    @Inject
    public IntentCentricTelemetryPublisher(TelemetrySink sink) {
        this.sink = sink;
    }

    @Override
    public Uni<Void> publish(IntentPhase phase,
                             UUID tenantId,
                             UUID intentId,
                             long version) {
        IntentTelemetryEvent event = new IntentTelemetryEvent(
                tenantId, intentId, version, phase);
        return sink.send(event);
    }

    /**
     * Publishes a drift alert to Redis via RedisTelemetrySink.
     * Fired by ControlPlaneOrchestrator.finalizeIntent() when
     * driftScore > intent.constraints.maxDriftThreshold.
     *
     * FIX: return type was Uni<Object> → must be Uni<Void>
     *      was returning null → now delegates to sink.publishDriftAlert()
     */
    @Override
    public Uni<Void> publishDriftAlert(UUID tenantId,
                                       UUID intentId,
                                       String adapterId,
                                       BigDecimal driftScore) {
        return sink.publishDriftAlert(tenantId, intentId, adapterId, driftScore);
    }
}