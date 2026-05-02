package com.decisionmesh.streaming.consumer;


import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Reactive PostgreSQL implementation of IdempotentConsumerGuard.
 * Uses PgPool (non-blocking) — no AgroalDataSource needed.
 */
@ApplicationScoped
public class ReactiveIdempotentConsumerGuard implements IdempotentConsumerGuard {

    @Inject PgPool client;

    @Override
    public Uni<Boolean> alreadyProcessed(String eventId) {
        return client.preparedQuery(
                        "SELECT 1 FROM processed_events WHERE event_id = $1")
                .execute(Tuple.of(eventId))
                .map(rows -> rows.rowCount() > 0)
                .onFailure().invoke(ex ->
                        Log.warnf("[Idempotency] Check failed for eventId=%s: %s",
                                eventId, ex.getMessage()))
                .onFailure().recoverWithItem(false); // fail-open
    }

    @Override
    public Uni<Void> markProcessed(String eventId) {
        return client.preparedQuery("""
                INSERT INTO processed_events (event_id, consumer_group, processed_at)
                VALUES ($1, 'default', NOW())
                ON CONFLICT (event_id) DO NOTHING
                """)
                .execute(Tuple.of(eventId))
                .replaceWithVoid()
                .onFailure().invoke(ex ->
                        Log.warnf("[Idempotency] markProcessed failed for eventId=%s: %s",
                                eventId, ex.getMessage()))
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }
}