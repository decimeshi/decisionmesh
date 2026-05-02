package com.decisionmesh.application.port;

import com.decisionmesh.application.policy.PolicyEvaluationResult;
import com.decisionmesh.domain.intent.Intent;
import com.decisionmesh.domain.execution.ExecutionRecord;
import com.decisionmesh.domain.policy.PolicyEvaluation;
import io.smallrye.mutiny.Uni;

public interface PolicyEngine {
    Uni<PolicyEvaluationResult> evaluatePreSubmission(Intent intent);
    Uni<PolicyEvaluationResult> evaluatePreExecution(Intent intent);
    Uni<PolicyEvaluationResult> evaluatePostExecution(Intent intent, ExecutionRecord record);
}