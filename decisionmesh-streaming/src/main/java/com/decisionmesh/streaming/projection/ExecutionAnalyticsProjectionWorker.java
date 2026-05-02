package com.decisionmesh.streaming.projection;

import com.decisionmesh.streaming.consumer.ReactiveIdempotentConsumerGuard;
import com.decisionmesh.streaming.worker.ProjectionWorker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Updates spend_records and drift_tracking from domain events.
 * Uses reactive PgPool — no blocking JDBC.
 */
@ApplicationScoped
public class ExecutionAnalyticsProjectionWorker implements ProjectionWorker {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject PgPool                         client;
    @Inject ReactiveIdempotentConsumerGuard idempotencyGuard;

    @Override
    public void handle(String eventType, String payloadJson) {
        try {
            JsonNode payload = MAPPER.readTree(payloadJson);
            String eventId  = payload.path("eventId").asText(null);
            String intentId = payload.path("intentId").asText(
                    payload.path("aggregateId").asText(null));
            String tenantId = payload.path("tenantId").asText(null);

            if (intentId == null) return;

            String guardKey = eventId != null ? "analytics:" + eventId : null;
            if (guardKey != null) {
                Boolean already = idempotencyGuard.alreadyProcessed(guardKey)
                        .await().atMost(Duration.ofSeconds(3));
                if (Boolean.TRUE.equals(already)) return;
            }

            switch (eventType) {
                case "SATISFIED"     -> writeSpendRecord(intentId, tenantId, "SATISFIED");
                case "VIOLATED"      -> writeSpendRecord(intentId, tenantId, "VIOLATED");
                case "DRIFT_UPDATED" -> writeDrift(intentId, tenantId, payload);
                default              -> { return; }
            }

            if (guardKey != null) {
                idempotencyGuard.markProcessed(guardKey)
                        .await().atMost(Duration.ofSeconds(3));
            }

        } catch (Exception ex) {
            Log.warnf("[AnalyticsProjection] Failed to handle event type=%s: %s",
                    eventType, ex.getMessage());
        }
    }

    private void writeSpendRecord(String intentId, String tenantId, String outcome) {
        if (tenantId == null) return;
        client.preparedQuery("""
                INSERT INTO spend_records (intent_id, tenant_id, outcome, recorded_at)
                VALUES ($1::uuid, $2::uuid, $3, $4)
                ON CONFLICT (intent_id) DO UPDATE
                    SET outcome = EXCLUDED.outcome, recorded_at = EXCLUDED.recorded_at
                """)
                .execute(Tuple.of(intentId, tenantId, outcome,
                        OffsetDateTime.now(ZoneOffset.UTC)))
                .await().atMost(Duration.ofSeconds(5));
        Log.debugf("[AnalyticsProjection] spend_record %s intent=%s", outcome, intentId);
    }

    private void writeDrift(String intentId, String tenantId, JsonNode payload) {
        if (tenantId == null) return;
        double score = payload.path("driftScore").asDouble(0.0);
        client.preparedQuery("""
                INSERT INTO drift_tracking (intent_id, tenant_id, drift_score, recorded_at)
                VALUES ($1::uuid, $2::uuid, $3, $4)
                ON CONFLICT (intent_id) DO UPDATE
                    SET drift_score = EXCLUDED.drift_score,
                        recorded_at = EXCLUDED.recorded_at
                """)
                .execute(Tuple.of(intentId, tenantId, score,
                        OffsetDateTime.now(ZoneOffset.UTC)))
                .await().atMost(Duration.ofSeconds(5));
    }
}