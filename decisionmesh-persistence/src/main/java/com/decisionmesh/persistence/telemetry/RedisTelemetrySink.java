package com.decisionmesh.persistence.telemetry;

import com.decisionmesh.application.telemetry.IntentTelemetryEvent;
import com.decisionmesh.application.telemetry.TelemetrySink;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.list.ReactiveListCommands;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@ApplicationScoped
public class RedisTelemetrySink implements TelemetrySink {

    private static final String KEY_PREFIX = "telemetry:";

    private final ReactiveListCommands<String, String> list;

    @Inject
    public RedisTelemetrySink(ReactiveRedisDataSource redis) {
        this.list = redis.list(String.class);
    }

    @Override
    public Uni<Void> send(IntentTelemetryEvent event) {
        Objects.requireNonNull(event, "Telemetry event cannot be null");
        return list.rpush(buildKey(event), event.toJson())
                .replaceWithVoid();
    }

    /**
     * Publishes a drift alert to the tenant's telemetry Redis list.
     *
     * Key:     telemetry:<tenantId>
     * Payload: JSON with event=DRIFT_ALERT, intentId, adapterId, driftScore, timestamp
     *
     * Consumed by the Drift Dashboard to show the alert banner when
     * driftScore > intent.constraints.maxDriftThreshold.
     *
     * FIX: was using non-existent redisClient.rpush(List<>) —
     *      now uses list.rpush(key, value) which matches the injected ReactiveListCommands.
     */
    @Override
    public Uni<Void> publishDriftAlert(UUID tenantId, UUID intentId,
                                       String adapterId, BigDecimal driftScore) {
        String key = KEY_PREFIX + tenantId;
        String payload = String.format(
                "{\"event\":\"DRIFT_ALERT\",\"tenantId\":\"%s\",\"intentId\":\"%s\"," +
                        "\"adapterId\":\"%s\",\"driftScore\":%.4f,\"timestamp\":\"%s\"}",
                tenantId, intentId,
                adapterId != null ? adapterId : "unknown",
                driftScore.doubleValue(),
                Instant.now());

        return list.rpush(key, payload)
                .replaceWithVoid();
    }

    private String buildKey(IntentTelemetryEvent event) {
        return KEY_PREFIX + event.getTenantId();
    }
}