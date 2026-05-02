package com.decisionmesh.application.port;

import com.decisionmesh.domain.intent.Intent;
import com.decisionmesh.domain.plan.Plan;
import io.smallrye.mutiny.Uni;

public interface Planner {
    Uni<Plan> plan(Intent intent);
}