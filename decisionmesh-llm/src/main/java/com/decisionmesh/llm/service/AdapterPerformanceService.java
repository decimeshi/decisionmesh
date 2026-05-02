package com.decisionmesh.llm.service;


import com.decisionmesh.persistence.repository.AdapterPerformanceRepository;
import com.decisionmesh.persistence.entity.AdapterPerformanceEntity;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.OffsetDateTime;
import java.util.UUID;

@ApplicationScoped
public class AdapterPerformanceService {

    private static final double ALPHA = 0.2;

    @Inject
    AdapterPerformanceRepository repository;

    public Uni<AdapterPerformanceEntity> get(UUID tenantId, UUID adapterId) {
        return repository.find(tenantId, adapterId)
                .onItem().ifNull().continueWith(createColdStart(adapterId, tenantId));
    }

    // Default when no data exists
    private AdapterPerformanceEntity createColdStart(UUID adapterId, UUID tenantId) {
        AdapterPerformanceEntity p = new AdapterPerformanceEntity();
        p.adapterId = adapterId;
        p.tenantId = tenantId;
        p.executionCount = 0L;
        p.coldStart = true;
        p.isDegraded = false;
        p.compositeScore = 0.5; // neutral prior
        return p;
    }

    @WithTransaction
    public Uni<Void> update(UUID tenantId, UUID adapterId, boolean success, long latencyMs, double costUsd) {
        return repository.find(tenantId, adapterId)
                .onItem().ifNull().switchTo(() ->
                        Uni.createFrom().item(createNew(tenantId, adapterId))
                )
                .flatMap(p -> {

                    // ─── EMA Updates ─────────────────────
                    p.emaSuccessRate = ema(p.emaSuccessRate, success ? 1.0 : 0.0);
                    p.emaLatencyMs   = ema(p.emaLatencyMs, latencyMs);
                    p.emaCost = ema(p.emaCost, costUsd);

                    // ─── Count ───────────────────────────
                    p.executionCount = p.executionCount + 1;

                    // ─── Derived metrics ────────────────
                    double latencyScore = 1.0 / (1.0 + (p.emaLatencyMs / 1000.0));
                    double costScore    = 1.0 / (1.0 + (p.emaCost * 1000.0));
                    double riskScore    = p.emaSuccessRate;

                    p.compositeScore =
                            0.40 * p.emaSuccessRate +
                                    0.25 * latencyScore +
                                    0.20 * costScore +
                                    0.15 * riskScore;

                    // ─── Circuit breaker ───────────────
                    p.isDegraded =
                            p.executionCount > 5 &&
                                    p.emaSuccessRate < 0.6;

                    p.coldStart = false;
                    p.updatedAt = OffsetDateTime.now();

                    return repository.persist(p);
                })
                .replaceWithVoid();
    }

    private double ema(Double oldVal, double newVal) {
        if (oldVal == null) return newVal;
        return ALPHA * newVal + (1 - ALPHA) * oldVal;
    }

    private AdapterPerformanceEntity createNew(UUID tenantId, UUID adapterId) {
        AdapterPerformanceEntity p = new AdapterPerformanceEntity();
        p.adapterId = adapterId;
        p.tenantId = tenantId;
        p.executionCount = 0L;
        p.coldStart = true;
        p.isDegraded = false;
        return p;
    }
}
