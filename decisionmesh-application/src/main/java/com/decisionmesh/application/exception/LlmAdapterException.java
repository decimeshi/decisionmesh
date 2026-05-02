package com.decisionmesh.application.exception;

/**
 * Thrown by an LlmAdapter when the provider returns an error or the
 * request cannot be completed for a non-timeout reason.
 *
 * failureType maps directly to ExecutionRecord status values:
 *   ADAPTER_ERROR, RATE_LIMITED, INVALID_OUTPUT, POLICY_BLOCK
 */
public class LlmAdapterException extends RuntimeException {

    private final String failureType;
    private final String provider;
    private final String model;
    private final int    attempt;
    private final long   latencyMs;

    public LlmAdapterException(String failureType, String message,
                                String provider, String model,
                                int attempt, long latencyMs) {
        super(message);
        this.failureType = failureType;
        this.provider    = provider;
        this.model       = model;
        this.attempt     = attempt;
        this.latencyMs   = latencyMs;
    }

    public String getFailureType() { return failureType; }
    public String getProvider()    { return provider; }
    public String getModel()       { return model; }
    public int    getAttempt()     { return attempt; }
    public long   getLatencyMs()   { return latencyMs; }
}
