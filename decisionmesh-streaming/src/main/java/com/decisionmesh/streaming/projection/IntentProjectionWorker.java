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

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Updates the intents read-model from domain events.
 * Uses reactive PgPool — no blocking JDBC.
 */
@ApplicationScoped
public class IntentProjectionWorker implements ProjectionWorker {

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

            if (intentId == null) return;

            String guardKey = eventId != null ? "intent-proj:" + eventId : null;
            if (guardKey != null) {
                Boolean already = idempotencyGuard.alreadyProcessed(guardKey)
                        .await().atMost(java.time.Duration.ofSeconds(3));
                if (Boolean.TRUE.equals(already)) return;
            }

            applyEvent(eventType, intentId, payload);

            if (guardKey != null) {
                idempotencyGuard.markProcessed(guardKey)
                        .await().atMost(java.time.Duration.ofSeconds(3));
            }

        } catch (Exception ex) {
            Log.warnf("[IntentProjection] Failed to handle event type=%s: %s",
                    eventType, ex.getMessage());
        }
    }

    private void applyEvent(String eventType, String intentId,
                            JsonNode payload) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        switch (eventType) {
            case "PLANNING_STARTED"   -> updatePhase(intentId, "PLANNING", now);
            case "PLANNED"            -> updatePhase(intentId, "PLANNED", now);
            case "EXECUTION_STARTED"  -> updatePhase(intentId, "EXECUTING", now);
            case "EVALUATION_STARTED" -> updatePhase(intentId, "EVALUATING", now);
            case "RETRY_SCHEDULED"    -> incrementRetry(intentId, now);
            case "DRIFT_UPDATED"      -> updateDrift(intentId, payload, now);
            case "SATISFIED"          -> updateTerminal(intentId, "COMPLETED", "SATISFIED", now);
            case "VIOLATED"           -> updateTerminal(intentId, "COMPLETED", "VIOLATED", now);
            case "CANCELLED"          -> updateTerminal(intentId, "CANCELLED", "NOT_SATISFIED", now);
            default -> Log.debugf("[IntentProjection] Unhandled event=%s intent=%s",
                    eventType, intentId);
        }
    }

    private void updatePhase(String intentId, String phase, OffsetDateTime now) {
        client.preparedQuery(
                        "UPDATE intents SET phase = $1, updated_at = $2 WHERE id = $3::uuid")
                .execute(Tuple.of(phase, now, intentId))
                .await().atMost(java.time.Duration.ofSeconds(5));
        Log.debugf("[IntentProjection] phase=%s intent=%s", phase, intentId);
    }

    private void updateTerminal(String intentId, String phase,
                                String satisfaction, OffsetDateTime now) {
        client.preparedQuery("""
                UPDATE intents
                SET phase = $1, satisfaction_state = $2,
                    terminal = true, updated_at = $3
                WHERE id = $4::uuid
                """)
                .execute(Tuple.of(phase, satisfaction, now, intentId))
                .await().atMost(java.time.Duration.ofSeconds(5));
        Log.debugf("[IntentProjection] terminal phase=%s sat=%s intent=%s",
                phase, satisfaction, intentId);
    }

    private void incrementRetry(String intentId, OffsetDateTime now) {
        client.preparedQuery("""
                UPDATE intents
                SET retry_count = retry_count + 1, updated_at = $1
                WHERE id = $2::uuid
                """)
                .execute(Tuple.of(now, intentId))
                .await().atMost(java.time.Duration.ofSeconds(5));
    }

    private void updateDrift(String intentId, JsonNode payload, OffsetDateTime now) {
        double score = payload.path("driftScore").asDouble(0.0);
        client.preparedQuery("""
                UPDATE intents
                SET payload = jsonb_set(COALESCE(payload,'{}'), '{driftScore}', $1::jsonb),
                    updated_at = $2
                WHERE id = $3::uuid
                """)
                .execute(Tuple.of(String.valueOf(score), now, intentId))
                .await().atMost(java.time.Duration.ofSeconds(5));
    }
}