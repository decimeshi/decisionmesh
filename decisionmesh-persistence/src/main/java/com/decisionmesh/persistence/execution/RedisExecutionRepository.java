package com.decisionmesh.persistence.execution;

import com.decisionmesh.application.port.ExecutionRepositoryPort;
import com.decisionmesh.domain.execution.ExecutionRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.list.ReactiveListCommands;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

@Alternative
@Priority(1)
@ApplicationScoped
public class RedisExecutionRepository implements ExecutionRepositoryPort {

    private static final Logger log      = Logger.getLogger(RedisExecutionRepository.class);
    private static final String KEY_PREFIX = "execution:";

    private final ReactiveListCommands<String, String> listCommands;
    private final ObjectMapper                         mapper;

    @Inject
    public RedisExecutionRepository(ReactiveRedisDataSource redis, ObjectMapper mapper) {
        this.listCommands = redis.list(String.class);
        this.mapper       = mapper;
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    @Override
    public Uni<Void> append(ExecutionRecord record) {
        String key = buildKey(record.getIntentId());

        return Uni.createFrom().item(() -> record.toJson(mapper))
                .flatMap(json -> listCommands.rpush(key, json))
                .replaceWithVoid();
    }

    /**
     * Convenience batch append — NOT part of ExecutionRepositoryPort.
     * Each record is pushed as an individual Redis list entry.
     */
    public Uni<Void> appendAll(List<ExecutionRecord> records) {
        if (records == null || records.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        String key = buildKey(records.get(0).getIntentId());

        return Uni.createFrom().item(() ->
                        records.stream()
                                .map(r -> r.toJson(mapper))
                                .toArray(String[]::new)        // rpush varargs — one entry per record
                )
                .flatMap(jsonArray -> listCommands.rpush(key, jsonArray))
                .replaceWithVoid();
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    @Override
    public Uni<List<ExecutionRecord>> findByIntentId(UUID intentId) {
        String key = buildKey(intentId);

        return listCommands
                .lrange(key, 0, -1)
                .map(list -> list.stream()
                        .flatMap(json -> {
                            try {
                                return Stream.of(ExecutionRecord.fromJson(json, mapper));
                            } catch (Exception e) {
                                // Skip corrupt/stale records — log for observability
                                log.warnf("Skipping unreadable ExecutionRecord " +
                                        "for intent %s: %s", intentId, e.getMessage());
                                return Stream.empty();
                            }
                        })
                        .toList()
                );
    }

    // ── Key builder ───────────────────────────────────────────────────────────

    private String buildKey(UUID intentId) {
        return KEY_PREFIX + intentId;
    }
}