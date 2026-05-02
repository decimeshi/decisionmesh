package com.decisionmesh.application.engine;

import com.decisionmesh.application.port.ExecutionEngine;
import com.decisionmesh.domain.execution.ExecutionRecord;
import com.decisionmesh.domain.execution.FailureType;
import com.decisionmesh.domain.plan.Plan;
import com.decisionmesh.domain.value.PlanVersion;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import java.math.BigDecimal;

@Alternative
@Priority(0)
@ApplicationScoped
public class DefaultExecutionEngine implements ExecutionEngine {

    @Override
    public Uni<ExecutionRecord> execute(Plan plan, int attempt) {

        return Uni.createFrom().item(() -> {

            if (plan == null) {
                throw new IllegalArgumentException("Plan cannot be null");
            }

            if (plan.getOrderedAdapters() == null ||
                    plan.getOrderedAdapters().isEmpty()) {
                throw new IllegalStateException(
                        "Plan has no adapters to execute");
            }

            if (attempt <= 0) {
                throw new IllegalArgumentException(
                        "Attempt must be >= 1");
            }

            long start = System.currentTimeMillis();

            // Intent-centric: execute adapters sequentially
            for (String adapter : plan.getOrderedAdapters()) {
                if (adapter == null || adapter.isBlank()) {
                    throw new IllegalStateException(
                            "Invalid adapter in execution plan");
                }

                // Future: delegate to real adapter executors here
            }

            long latency = System.currentTimeMillis() - start;

            // For now: deterministic success, no failure
            FailureType failureType = null;

            // PlanVersion must be constructed properly
            PlanVersion version = new PlanVersion(plan.getVersion().value());

            // Use last adapter as execution marker
            String adapterId = plan.getOrderedAdapters()
                    .get(plan.getOrderedAdapters().size() - 1);

            return ExecutionRecord.of(
                    plan.getIntentId(),
                    attempt,
                    adapterId,
                    latency,
                    BigDecimal.valueOf(0.0),               // cost model not implemented yet
                    failureType,
                    version
            );
        });
    }
}
