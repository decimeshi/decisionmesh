package com.decisionmesh.application.service;

import com.decisionmesh.application.exception.SLAException;
import com.decisionmesh.application.port.SLAGuard;
import com.decisionmesh.domain.execution.ExecutionRecord;
import com.decisionmesh.domain.intent.Intent;
import com.decisionmesh.domain.intent.IntentPhase;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.time.Instant;

@ApplicationScoped
public class IntentCentricSLAGuard implements SLAGuard {

    @Override
    public Uni<Void> validateBeforeExecution(Intent intent) {

        return Uni.createFrom().voidItem()
                .invoke(() -> {

                    // 1️⃣ Terminal protection
                    if (intent.isTerminal()) {
                        throw new SLAException("Intent already terminal");
                    }

                    // 2️⃣ Phase correctness
                    if (intent.getPhase() != IntentPhase.PLANNED &&
                            intent.getPhase() != IntentPhase.EXECUTING) {
                        throw new SLAException("Intent not eligible for execution");
                    }

                    // 3️⃣ Retry protection (derived from constraints)
                    if (intent.getRetryCount() >=
                            intent.getConstraints().maxRetries()) {
                        throw new SLAException("Retry budget exhausted");
                    }

                    // 4️⃣ Budget protection
                    if (intent.getBudget().isExceeded()) {
                        throw new SLAException("Budget exhausted");
                    }

                    // 5️⃣ Drift threshold enforcement
                    if (intent.getDriftScore().value() > intent.getConstraints().maxDriftThreshold()) {
                        throw new SLAException("Drift threshold violated");
                    }

                    // 6️⃣ Time-to-live constraint
                    int windowDays = Math.toIntExact(intent.getConstraints().maxExecutionWindow());

                    if (windowDays > 0) {   //   only enforce if > 0
                        Duration ttl = Duration.ofDays(windowDays);

                        Duration age = Duration.between(
                                intent.getCreatedAt(),
                                Instant.now()
                        );

                        if (age.compareTo(ttl) > 0) {
                            throw new SLAException("Execution window expired");
                        }
                    }
                });
    }

    @Override
    public Uni<Void> validateAfterExecution(Intent intent,
                                            ExecutionRecord record) {

        return Uni.createFrom().voidItem()
                .invoke(() -> {

                    // 1️⃣ Cost guard
                    if (record.getCost().doubleValue() > intent.getBudget().remaining()) {
                        throw new SLAException("Execution cost exceeds remaining budget");
                    }

                    // 2️⃣ Latency guard — maxLatency() returns the value in milliseconds
                    long maxLatencyMs = intent.getConstraints().maxLatency();
                    if (maxLatencyMs > 0 && record.getLatencyMs() > maxLatencyMs) {
                        throw new SLAException("Latency constraint violated — actual=" +
                                record.getLatencyMs() + "ms, limit=" + maxLatencyMs + "ms");
                    }

                    // 3️⃣ Failure guard (success semantics)
                    if (!record.isSuccess()) {
                        throw new SLAException(
                                "Execution failed: " + record.getFailureReason()
                        );
                    }
                });
    }

}