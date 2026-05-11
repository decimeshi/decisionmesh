package com.decisionmesh.governance.snapshot;

import com.decisionmesh.billing.model.SubscriptionEntity;
import com.decisionmesh.governance.policy.PolicyDecision;

public class PolicySnapshot {

    private final PolicyDecision decision;
    private final String policyVersion;
    private final String enforcementMode;
    private final String evaluatedContextJson;
    private final SubscriptionEntity.Plan plan;

    public PolicySnapshot(PolicyDecision decision,
                          String policyVersion,
                          String enforcementMode,
                          String evaluatedContextJson,
                          SubscriptionEntity.Plan plan) {
        this.decision = decision;
        this.policyVersion = policyVersion;
        this.enforcementMode = enforcementMode;
        this.evaluatedContextJson = evaluatedContextJson;
        this.plan = plan;
    }

    public PolicyDecision getDecision()          { return decision; }
    public SubscriptionEntity.Plan getPlan()     { return plan; }
    public String getPolicyVersion()             { return policyVersion; }
    public String getEnforcementMode()           { return enforcementMode; }
    public String getEvaluatedContextJson()      { return evaluatedContextJson; }
}