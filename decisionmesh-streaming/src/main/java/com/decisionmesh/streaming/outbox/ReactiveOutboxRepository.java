package com.decisionmesh.streaming.outbox;

import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reactive PostgreSQL implementation of OutboxRepository.
 * Uses PgPool (non-blocking) — no AgroalDataSource needed.
 */
@ApplicationScoped
public class ReactiveOutboxRepository implements OutboxRepository {

    @Inject PgPool client;

    // ── Write ─────────────────────────────────────────────────────────────────

    public Uni<Void> insertAll(List<OutboxEvent> events) {
        if (events.isEmpty()) return Uni.createFrom().voidItem();

        // Build batch — one query per event using reactive client
        List<Uni<Void>> inserts = events.stream().map(this::insertOne).toList();
        return Uni.join().all(inserts).andFailFast()
                .invoke(v -> Log.debugf("[Outbox] Inserted %d events", events.size()))
                .replaceWithVoid();
    }

    private Uni<Void> insertOne(OutboxEvent event) {
        return client.preparedQuery("""
                INSERT INTO event_outbox
                    (id, aggregate_type, aggregate_id, event_type, payload_json, published, created_at)
                VALUES ($1, $2, $3, $4, $5, false, $6)
                ON CONFLICT (id) DO NOTHING
                """)
                .execute(Tuple.of(
                        event.getId(),
                        event.getAggregateType(),
                        event.getAggregateId(),
                        event.getEventType(),
                        event.getPayloadJson(),
                        OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC)
                ))
                .replaceWithVoid();
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    @Override
    public Uni<List<OutboxEvent>> findUnpublished() {
        return client.preparedQuery("""
                SELECT id, aggregate_type, aggregate_id, event_type, payload_json, published, created_at
                FROM   event_outbox
                WHERE  published = false
                ORDER  BY created_at
                LIMIT  100
                """)
                .execute()
                .map(rows -> {
                    List<OutboxEvent> result = new ArrayList<>();
                    for (Row row : rows) {
                        result.add(new OutboxEvent(
                                row.getUUID("id"),
                                row.getString("aggregate_type"),
                                row.getUUID("aggregate_id"),
                                row.getString("event_type"),
                                row.getString("payload_json"),
                                row.getBoolean("published"),
                                row.getOffsetDateTime("created_at").toInstant()
                        ));
                    }
                    return result;
                });
    }

    // ── Mark published ────────────────────────────────────────────────────────

    @Override
    public Uni<Void> markPublished(OutboxEvent event) {
        return client.preparedQuery(
                        "UPDATE event_outbox SET published = true WHERE id = $1")
                .execute(Tuple.of(event.getId()))
                .replaceWithVoid();
    }
}