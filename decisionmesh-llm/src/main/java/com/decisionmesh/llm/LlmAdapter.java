package com.decisionmesh.llm;

import com.decisionmesh.domain.execution.ExecutionRecord;
import com.decisionmesh.domain.intent.Intent;
import com.decisionmesh.domain.plan.PlanStep;
import io.smallrye.mutiny.Uni;

/**
 * Provider-agnostic LLM adapter contract.
 *
 * Each concrete implementation handles:
 *   - Request serialization for its provider's wire format
 *   - HTTP call with timeout enforcement
 *   - Response deserialization + token/cost accounting
 *   - Provider-specific error classification
 *
 * The execution engine selects the correct adapter by matching
 * PlanStep.configSnapshot["provider"] against provider().
 *
 * To add a new provider (e.g. Gemini, Cohere, Azure OpenAI):
 *   1. Create a new @ApplicationScoped class implementing this interface
 *   2. Return the provider string from provider()
 *   3. No other changes required — CDI discovers it automatically
 */
public interface LlmAdapter {

    /**
     * Provider identifier this adapter handles.
     * Must match the value stored in PlanStep config_snapshot["provider"].
     * Compared case-insensitively.
     *
     * Examples: "OPENAI", "ANTHROPIC", "AZURE_OPENAI", "GEMINI"
     */
    String provider();

    /**
     * Execute a single plan step against this LLM provider.
     *
     * @param intent   Full intent context (objective, constraints, budget)
     * @param step     Plan step containing model, timeout, budget allocation
     * @param attempt  Attempt number (1-based) for logging and cost tracking
     * @return         Completed ExecutionRecord — success or failure variant.
     *                 Never returns null. Provider failures surface as:
     *                   - LlmAdapterException  (4xx, invalid output, rate limit)
     *                   - LlmTimeoutException  (request timed out)
     */
    Uni<ExecutionRecord> execute(Intent intent, PlanStep step, int attempt);
}
