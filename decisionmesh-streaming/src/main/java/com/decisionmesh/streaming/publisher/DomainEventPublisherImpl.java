package com.decisionmesh.streaming.publisher;

import com.decisionmesh.streaming.config.KafkaConfig;
import com.decisionmesh.domain.event.DomainEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.UUID;

/**
 * Concrete implementation of DomainEventPublisher.
 *
 * Current mode: structured logging — events are fully serialized and logged
 * at INFO level so they are observable in any log aggregator (Grafana, ELK)
 * without Kafka being present.
 *
 * Kafka mode (when ready): inject SmallRye Reactive Messaging emitter and
 * replace the Log.infof() call in publishRaw() with:
 *
 *   @Channel(KafkaConfig.INTENT_TOPIC)
 *   MutinyEmitter<String> intentEmitter;
 *
 *   return intentEmitter.send(payloadJson);
 *
 * Topic routing (for Kafka mode):
 *   Intent events    → KafkaConfig.INTENT_TOPIC     ("intent-events")
 *   Execution events → KafkaConfig.EXECUTION_TOPIC  ("execution-events")
 *   Governance events→ KafkaConfig.GOVERNANCE_TOPIC ("governance-events")
 */
@ApplicationScoped
public class DomainEventPublisherImpl implements DomainEventPublisher {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    /**
     * Publish a typed DomainEvent.
     * Called directly from ControlPlaneOrchestrator for immediate events.
     * Serializes via DomainEvent.toJson() — preserves the contract defined
     * in the domain layer without a double-serialization round-trip.
     */
    @Override
    public Uni<Void> publish(DomainEvent event) {
        if (event == null) return Uni.createFrom().voidItem();

        return Uni.createFrom().item(() -> {
            try {
                Map<String, Object> json = event.toJson();
                String payloadJson       = MAPPER.writeValueAsString(json);
                String topic             = resolveTopic(event.aggregateType(),
                        event.eventType().name());
                logEvent(topic, event.aggregateType(),
                        event.aggregateId(), event.eventType().name(), payloadJson);
            } catch (Exception ex) {
                Log.warnf("[EventPublisher] Serialization failed for event=%s type=%s: %s",
                        event.eventId(), event.eventType(), ex.getMessage());
            }
            return null;
        }).replaceWithVoid();
    }

    /**
     * Low-level publish called by KafkaOutboxPublisher during outbox relay.
     * Payload is already a JSON string from the event_outbox row.
     */
    public Uni<Void> publishFromOutbox(String aggregateType, UUID aggregateId,
                                       String eventType, String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return Uni.createFrom().voidItem();
        }

        return Uni.createFrom().item(() -> {
            String topic = resolveTopic(aggregateType, eventType);
            logEvent(topic, aggregateType, aggregateId, eventType, payloadJson);
            return null;
        }).replaceWithVoid();
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Route event to the correct Kafka topic based on aggregate + event type.
     *
     * Intent lifecycle events → intent-events
     * Execution/cost events   → execution-events
     * Policy/drift/SLA events → governance-events
     */
    private String resolveTopic(String aggregateType, String eventType) {
        if (aggregateType == null || eventType == null) {
            return KafkaConfig.INTENT_TOPIC;
        }
        String et = eventType.toUpperCase();
        if (et.contains("DRIFT") || et.contains("POLICY") ||
                et.contains("VIOLATED") || et.contains("BUDGET")) {
            return KafkaConfig.GOVERNANCE_TOPIC;
        }
        if (et.contains("EXECUTION") || et.contains("SATISFIED")) {
            return KafkaConfig.EXECUTION_TOPIC;
        }
        return KafkaConfig.INTENT_TOPIC;
    }

    /**
     * Structured log output — acts as the event bus until Kafka is wired.
     * Log format matches what a Kafka consumer would receive so log-based
     * replays are possible without schema changes.
     */
    private void logEvent(String topic, String aggregateType,
                          UUID aggregateId, String eventType, String payloadJson) {
        Log.infof("[EventBus] topic=%s aggregateType=%s aggregateId=%s eventType=%s payload=%s",
                topic, aggregateType, aggregateId, eventType, payloadJson);
        // TODO: replace above with emitter.send(payloadJson) when Kafka is available
    }
}