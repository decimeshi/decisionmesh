package com.decisionmesh.application.exception;

/**
 * Thrown when an LLM provider request exceeds the configured timeout.
 *
 * Classified as "TIMEOUT" in ExecutionRecord.
 * Triggers fallback step when condition_expr includes "TIMEOUT" in failure_types.
 */
public class LlmTimeoutException extends LlmAdapterException {

    public LlmTimeoutException(String message, String provider,
                                String model, int attempt, long latencyMs) {
        super("TIMEOUT", message, provider, model, attempt, latencyMs);
    }
}
