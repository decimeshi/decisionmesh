package com.decisionmesh.streaming.publisher;

import com.decisionmesh.streaming.outbox.OutboxEvent;
import com.decisionmesh.streaming.outbox.ReactiveOutboxRepository;
import com.decisionmesh.streaming.projection.ExecutionAnalyticsProjectionWorker;
import com.decisionmesh.streaming.projection.IntentProjectionWorker;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.smallrye.common.annotation.Blocking;

import java.util.List;

/**
 * Outbox relay — polls event_outbox every 5 seconds and publishes
 * unpublished events via DomainEventPublisher.
 *
 * This is the Transactional Outbox pattern:
 *   1. ControlPlaneOrchestrator writes domain events to event_outbox
 *      atomically with the intent state change (drainEventsToOutbox).
 *   2. This scheduler polls and relays unpublished events to Kafka/webhook.
 *   3. On success, the event is marked published = true.
 *   4. On failure, the event stays unpublished and is retried next cycle.
 *
 * Benefits:
 *   - At-least-once delivery guarantee.
 *   - Intent state change and event emission are atomic (same DB write).
 *   - Kafka unavailability never blocks intent processing.
 */
@ApplicationScoped
public class KafkaOutboxPublisher {

    @Inject ReactiveOutboxRepository          outboxRepository;
    @Inject DomainEventPublisherImpl           domainEventPublisher;
    @Inject IntentProjectionWorker             intentProjection;
    @Inject ExecutionAnalyticsProjectionWorker analyticsProjection;

    /**
     * Scheduled outbox relay — runs every 5 seconds.
     * Reads up to 100 unpublished events, publishes each, marks as published.
     * Non-fatal: any failure is logged and retried on next cycle.
     */
    @Scheduled(every = "5s", delayed = "15s")
    @Blocking
    void relayPendingEvents() {
        publishPending()
                .subscribe().with(
                        v  -> { /* silent on success */ },
                        ex -> Log.warnf("[Outbox] Relay cycle failed (will retry): %s", ex.getMessage()));
    }

    public Uni<Void> publishPending() {
        return outboxRepository.findUnpublished()
                .flatMap(this::publishBatch);
    }

    private Uni<Void> publishBatch(List<OutboxEvent> events) {
        if (events.isEmpty()) return Uni.createFrom().voidItem();

        Log.debugf("[Outbox] Publishing %d pending events", events.size());

        // Publish sequentially — preserves ordering within aggregate
        Uni<Void> chain = Uni.createFrom().voidItem();
        for (OutboxEvent event : events) {
            chain = chain.flatMap(v -> publishOne(event));
        }
        return chain;
    }

    private Uni<Void> publishOne(OutboxEvent event) {
        return domainEventPublisher.publishFromOutbox(
                        event.getAggregateType(),
                        event.getAggregateId(),
                        event.getEventType(),
                        event.getPayloadJson())
                // Run projection workers on a worker thread — NOT the event loop.
                // .invoke() runs on the event loop which cannot be blocked.
                // .call() with executeBlocking() offloads to a worker thread safely.
                .call(v -> Uni.createFrom().<Void>emitter(em -> {
                    try {
                        intentProjection.handle(event.getEventType(), event.getPayloadJson());
                        analyticsProjection.handle(event.getEventType(), event.getPayloadJson());
                        em.complete(null);
                    } catch (Exception ex) {
                        Log.warnf("[Outbox] Projection failed for event=%s (non-fatal): %s",
                                event.getId(), ex.getMessage());
                        em.complete(null); // non-fatal — always complete
                    }
                }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool()))
                .flatMap(v -> outboxRepository.markPublished(event))
                .onFailure().invoke(ex ->
                        Log.errorf("[Outbox] Failed to relay event=%s type=%s: %s",
                                event.getId(), event.getEventType(), ex.getMessage()))
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }
}