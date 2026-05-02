package com.decisionmesh.domain.execution;

public enum FailureType {
    ADAPTER_ERROR,
    POLICY_BLOCK,
    TIMEOUT,
    INVALID_OUTPUT,
    BUDGET_EXCEEDED
}