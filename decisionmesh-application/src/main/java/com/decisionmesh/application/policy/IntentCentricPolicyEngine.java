package com.decisionmesh.application.policy;


import com.decisionmesh.application.port.PolicyEngine;
import com.decisionmesh.domain.execution.ExecutionRecord;
import com.decisionmesh.domain.intent.Intent;
import com.decisionmesh.domain.policy.PolicyEvaluation;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class IntentCentricPolicyEngine implements PolicyEngine {

    @Override
    public Uni<PolicyEvaluationResult> evaluatePreSubmission(Intent intent) {

        return Uni.createFrom().item(() -> {

            List<PolicyEvaluation> evaluations = new ArrayList<>();

            // 1️⃣ Budget policy
            if (BigDecimal.valueOf(intent.getBudget().remaining()).compareTo(BigDecimal.ZERO) <= 0) {
                evaluations.add(new PolicyEvaluation(
                        PolicyEvaluation.Decision.VIOLATION,
                        PolicyEvaluation.EnforcementMode.HARD_STOP,
                        "Budget exhausted"
                ));
            }

            // 2️⃣ Terminal state policy
            if (intent.isTerminal()) {
                evaluations.add(new PolicyEvaluation(
                        PolicyEvaluation.Decision.VIOLATION,
                        PolicyEvaluation.EnforcementMode.HARD_STOP,
                        "Intent already terminal"
                ));
            }

            return aggregate("PRE_SUBMISSION_POLICY", evaluations);
        });
    }

    @Override
    public Uni<PolicyEvaluationResult> evaluatePreExecution(Intent intent) {

        return Uni.createFrom().item(() -> {

            List<PolicyEvaluation> evaluations = new ArrayList<>();

            // Retry limit enforcement
            if (intent.getRetryCount() >= intent.getMaxRetries()) {
                evaluations.add(new PolicyEvaluation(
                        PolicyEvaluation.Decision.VIOLATION,
                        PolicyEvaluation.EnforcementMode.HARD_STOP,
                        "Max retries exceeded"
                ));
            }

            return aggregate("PRE_EXECUTION_POLICY", evaluations);
        });
    }

    @Override
    public Uni<PolicyEvaluationResult> evaluatePostExecution(Intent intent,
                                                             ExecutionRecord record) {

        return Uni.createFrom().item(() -> {

            List<PolicyEvaluation> evaluations = new ArrayList<>();

            // Cost drift policy
            if (record.isSuccess()) {
                BigDecimal cost = record.getCost();
                BigDecimal budgetRemaining = BigDecimal.valueOf(intent.getBudget().remaining());

                if (cost.compareTo(budgetRemaining) > 0) {
                    evaluations.add(new PolicyEvaluation(
                            PolicyEvaluation.Decision.WARNING,
                            PolicyEvaluation.EnforcementMode.WARN_ONLY,
                            "Execution cost exceeded remaining budget"
                    ));
                }
            }

            // Failure policy
            if (!record.isSuccess()) {
                evaluations.add(new PolicyEvaluation(
                        PolicyEvaluation.Decision.WARNING,
                        PolicyEvaluation.EnforcementMode.LOG_ONLY,
                        "Execution failed: " + record.getFailureReason()
                ));
            }

            return aggregate("POST_EXECUTION_POLICY", evaluations);
        });
    }

    /**
     * Aggregate multiple policy evaluations into a single result.
     * Hard-stop violations take precedence over warnings.
     */
    private PolicyEvaluationResult aggregate(String policyId,
                                             List<PolicyEvaluation> evaluations) {

        if (evaluations.isEmpty()) {
            return PolicyEvaluationResult.from(
                    policyId,
                    new PolicyEvaluation(
                            PolicyEvaluation.Decision.ALLOWED,
                            PolicyEvaluation.EnforcementMode.LOG_ONLY,
                            "Allowed"
                    )
            );
        }

        for (PolicyEvaluation evaluation : evaluations) {
            if (evaluation.isBlocking()) {
                return PolicyEvaluationResult.from(policyId, evaluation);
            }
        }

        // If no blocking but at least one warning
        return PolicyEvaluationResult.from(policyId, evaluations.get(0));
    }
}
