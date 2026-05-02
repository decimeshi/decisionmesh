package com.decisionmesh.application.policy;

import com.decisionmesh.domain.policy.PolicyEvaluation;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class PolicyEvaluationResult {

    private final UUID evaluationId;
    private final String policyId;
    private final PolicyEvaluation evaluation;
    private final Instant evaluatedAt;

    private PolicyEvaluationResult(UUID evaluationId,
                                   String policyId,
                                   PolicyEvaluation evaluation,
                                   Instant evaluatedAt) {

        this.evaluationId = evaluationId;
        this.policyId = policyId;
        this.evaluation = evaluation;
        this.evaluatedAt = evaluatedAt;
    }

    public static PolicyEvaluationResult from(String policyId,
                                              PolicyEvaluation evaluation) {

        return new PolicyEvaluationResult(
                UUID.randomUUID(),
                policyId,
                evaluation,
                Instant.now()
        );
    }

    public UUID getEvaluationId() {
        return evaluationId;
    }

    public String getPolicyId() {
        return policyId;
    }

    public boolean isBlocking() {
        return evaluation.isBlocking();
    }

    public boolean isWarning() {
        return evaluation.isWarning();
    }

    public String getBlockReason() {
        return evaluation.reason();
    }

    public String getEvaluationDetail() {
        return evaluation.reason();
    }

    public Instant getEvaluatedAt() {
        return evaluatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PolicyEvaluationResult that)) return false;
        return evaluationId.equals(that.evaluationId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(evaluationId);
    }
}
