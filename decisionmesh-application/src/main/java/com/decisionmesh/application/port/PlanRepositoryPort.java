package com.decisionmesh.application.port;


import com.decisionmesh.domain.plan.Plan;
import io.smallrye.mutiny.Uni;

import java.util.List;
import java.util.UUID;

public interface PlanRepositoryPort {

    /**
     * Persist a new plan version for an intent.
     */
    Uni<Void> save(Plan plan);

    /**
     * Retrieve latest plan for an intent.
     */
    Uni<Plan> findLatestByIntentId(UUID intentId);

    /**
     * Retrieve full plan history for audit / drift analysis.
     */
    Uni<List<Plan>> findAllByIntentId(UUID intentId);
}
