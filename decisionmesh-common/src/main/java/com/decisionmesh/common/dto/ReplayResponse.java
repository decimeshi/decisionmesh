package com.decisionmesh.common.dto;


import java.time.Instant;

public class ReplayResponse {

    public Instant timestamp;
    public String decision;
    public String reason;
    public String plan;

    public ReplayResponse(Instant timestamp, String decision, String reason, String plan) {
        this.timestamp = timestamp;
        this.decision = decision;
        this.reason = reason;
        this.plan = plan;
    }
}
