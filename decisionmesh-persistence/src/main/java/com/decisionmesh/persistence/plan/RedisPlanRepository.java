package com.decisionmesh.persistence.plan;

import com.decisionmesh.application.port.PlanRepositoryPort;
import com.decisionmesh.domain.plan.Plan;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.sortedset.ReactiveSortedSetCommands;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Alternative
@Priority(1)          // must be ≥ 1 to override the default bean
@ApplicationScoped
public class RedisPlanRepository implements PlanRepositoryPort {

    private static final String PLAN_KEY_PREFIX  = "plan:";
    private static final String PLAN_INDEX_PREFIX = "plan_index:";

    private final ReactiveValueCommands<String, String>     valueCommands;
    private final ReactiveSortedSetCommands<String, String> sortedSetCommands;
    private final ObjectMapper                              mapper;

    @Inject
    public RedisPlanRepository(ReactiveRedisDataSource redis, ObjectMapper mapper) {
        this.valueCommands     = redis.value(String.class);
        this.sortedSetCommands = redis.sortedSet(String.class);
        this.mapper            = mapper;
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    @Override
    public Uni<Void> save(Plan plan) {
        String planKey  = buildPlanKey(plan.getIntentId(), plan.getVersion().value());
        String indexKey = buildIndexKey(plan.getIntentId());

        // Serialize inside the Uni so failures propagate as Uni errors.
        // toJson() already wraps JsonProcessingException in IllegalStateException —
        // no re-wrapping needed here.
        return Uni.createFrom().item(() -> plan.toJson(mapper))
                .flatMap(json ->
                        valueCommands.set(planKey, json)
                                .flatMap(ignored -> sortedSetCommands.zadd(
                                        indexKey,
                                        plan.getVersion().value(),
                                        planKey
                                ))
                )
                .replaceWithVoid();
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    @Override
    public Uni<Plan> findLatestByIntentId(UUID intentId) {
        String indexKey = buildIndexKey(intentId);

        return sortedSetCommands.zrange(indexKey, 0, -1)
                .flatMap(keys -> {
                    if (keys == null || keys.isEmpty()) {
                        return Uni.createFrom().nullItem();
                    }

                    // zrange returns keys in ascending score order — last = highest version
                    String latestKey = keys.get(keys.size() - 1);

                    return valueCommands.get(latestKey)
                            .map(json -> Plan.fromJson(json, mapper));
                });
    }

    @Override
    public Uni<List<Plan>> findAllByIntentId(UUID intentId) {
        String indexKey = buildIndexKey(intentId);

        return sortedSetCommands.zrange(indexKey, 0, -1)
                .flatMap(keys -> {
                    if (keys == null || keys.isEmpty()) {
                        return Uni.createFrom().item(List.of());
                    }

                    List<Uni<Plan>> planUnis = keys.stream()
                            .map(key -> valueCommands.get(key)
                                    .map(json -> Plan.fromJson(json, mapper)))
                            .collect(Collectors.toList());

                    return Uni.combine().all().unis(planUnis)
                            .with(list -> list.stream()
                                    .map(Plan.class::cast)
                                    .collect(Collectors.toList()));
                });
    }

    // ── Key builders ──────────────────────────────────────────────────────────

    private String buildPlanKey(UUID intentId, int version) {
        return PLAN_KEY_PREFIX + intentId + ":" + version;
    }

    private String buildIndexKey(UUID intentId) {
        return PLAN_INDEX_PREFIX + intentId;
    }
}