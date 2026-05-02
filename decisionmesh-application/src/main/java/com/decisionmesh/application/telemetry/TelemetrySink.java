package com.decisionmesh.application.telemetry;

import io.smallrye.mutiny.Uni;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Port for writing telemetry events to the sink (Redis).
 * Implemented by RedisTelemetrySink in decisionmesh-persistence.
 */
public interface TelemetrySink {

    /**
     * Sends an intent lifecycle telemetry event to Redis.
     */
    Uni<Void> send(IntentTelemetryEvent event);

    /**
     * Publishes a drift alert when adapter response exceeds maxDriftThreshold.
     * Stored in Redis under telemetry:<tenantId> list.
     * Consumed by Drift Dashboard to show alert banner.
     */
    Uni<Void> publishDriftAlert(UUID tenantId, UUID intentId,
                                String adapterId, BigDecimal driftScore);
}