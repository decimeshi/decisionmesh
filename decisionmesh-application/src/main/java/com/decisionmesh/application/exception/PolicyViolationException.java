package com.decisionmesh.application.exception;

public class PolicyViolationException extends RuntimeException {
    private final String policyId;
    private final String blockReason;

    public PolicyViolationException(String policyId, String blockReason) {
        super("Policy violation: " + policyId + " - " + blockReason);
        this.policyId = policyId;
        this.blockReason = blockReason;
    }

    public String getPolicyId() { return policyId; }
    public String getBlockReason() { return blockReason; }
}