package com.decisionmesh.application.port;

import com.decisionmesh.domain.execution.ExecutionRecord;
import com.decisionmesh.domain.plan.Plan;
import io.smallrye.mutiny.Uni;

/**
 * Port: executes a Plan and returns an ExecutionRecord.
 * Implemented by LlmExecutionEngine in decisionmesh-llm.
 */
public interface ExecutionEngine {

    /**
     * Execute the given plan on the appropriate LLM adapter.
     *
     * @param plan          Plan with PRIMARY + optional FALLBACK steps
     * @param attemptNumber 1-based retry counter from the orchestrator
     * @return              Completed ExecutionRecord — success or failure variant
     */
    Uni<ExecutionRecord> execute(Plan plan, int attemptNumber);
}