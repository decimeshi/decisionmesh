package com.decisionmesh.application.enums;

public enum ViolationCode {
    RETRY_BUDGET_EXHAUSTED  ("Retry budget exhausted — maxRetries reached with no successful execution"),
    LATENCY_EXCEEDED        ("Latency ceiling exceeded — execution took longer than maxLatencyMs"),
    TIMEOUT                 ("Execution timed out — exceeded timeoutSeconds constraint"),
    BUDGET_EXCEEDED         ("Spending ceiling hit — actual cost exceeded budget.ceilingUsd"),
    POLICY_TOPIC_BLOCKED    ("Policy violation — response contained a blocked topic"),
    POLICY_MODEL_DISALLOWED ("Policy violation — adapter model not in allowedModels list"),
    POLICY_DRIFT_EXCEEDED   ("Policy violation — output drift exceeded driftThreshold"),
    POLICY_INJECTION_BLOCKED("Security — prompt injection detected and blocked"),
    RATE_LIMIT_EXCEEDED     ("Rate limit — tenant execution rate exceeded"),
    ADAPTER_ERROR           ("Adapter error — LLM provider returned an error response"),
    ADAPTER_TIMEOUT         ("Adapter timeout — LLM provider did not respond in time"),
    UNKNOWN_VIOLATION       ("Intent violated — see constraint settings");

    public final String description;
    ViolationCode(String d) { this.description = d; }
}