package com.decisionmesh.governance.snapshot;

import com.decisionmesh.billing.model.SubscriptionEntity;
import com.decisionmesh.governance.policy.PolicyDecision;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class PolicySnapshot {

    private final PolicyDecision decision;
    private final String policyVersion;
    private final String enforcementMode;
    private final String evaluatedContextJson;
    private final SubscriptionEntity.Plan plan;

    @JsonCreator
    public PolicySnapshot(
            @JsonProperty("decision")             PolicyDecision          decision,
            @JsonProperty("policyVersion")        String                  policyVersion,
            @JsonProperty("enforcementMode")      String                  enforcementMode,
            @JsonProperty("evaluatedContextJson") String                  evaluatedContextJson,
            @JsonProperty("plan")                 SubscriptionEntity.Plan plan) {
        this.decision             = decision;
        this.policyVersion        = policyVersion;
        this.enforcementMode      = enforcementMode;
        this.evaluatedContextJson = evaluatedContextJson;
        this.plan                 = plan;
    }

    @JsonProperty("decision")
    public PolicyDecision getDecision()          { return decision; }

    @JsonProperty("plan")
    public SubscriptionEntity.Plan getPlan()     { return plan; }

    @JsonProperty("policyVersion")
    public String getPolicyVersion()             { return policyVersion; }

    @JsonProperty("enforcementMode")
    public String getEnforcementMode()           { return enforcementMode; }

    @JsonProperty("evaluatedContextJson")
    public String getEvaluatedContextJson()      { return evaluatedContextJson; }
}