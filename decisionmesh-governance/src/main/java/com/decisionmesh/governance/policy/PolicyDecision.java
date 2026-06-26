package com.decisionmesh.governance.policy;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class PolicyDecision {

    private final boolean allowed;
    private final String reason;

    @JsonCreator
    private PolicyDecision(
            @JsonProperty("allowed") boolean allowed,
            @JsonProperty("reason")  String  reason) {
        this.allowed = allowed;
        this.reason  = reason;
    }

    public static PolicyDecision allow() {
        return new PolicyDecision(true, "OK");
    }

    public static PolicyDecision deny(String reason) {
        return new PolicyDecision(false, reason);
    }

    @JsonProperty("allowed")
    public boolean isAllowed() { return allowed; }

    @JsonProperty("reason")
    public String getReason()  { return reason; }
}