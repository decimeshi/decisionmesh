package com.decisionmesh.governance.policy;


public class PolicyDecision {

    private final boolean allowed;
    private final String reason;

    private PolicyDecision(boolean allowed, String reason) {
        this.allowed = allowed;
        this.reason = reason;
    }

    public static PolicyDecision allow() {
        return new PolicyDecision(true, "OK");
    }

    public static PolicyDecision deny(String reason) {
        return new PolicyDecision(false, reason);
    }

    public boolean isAllowed() {
        return allowed;
    }

    public String getReason() {
        return reason;
    }
}