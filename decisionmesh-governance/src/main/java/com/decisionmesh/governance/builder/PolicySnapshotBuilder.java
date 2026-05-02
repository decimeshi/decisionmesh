package com.decisionmesh.governance.builder;


import com.decisionmesh.billing.model.SubscriptionEntity;
import com.decisionmesh.billing.service.SubscriptionService;
import com.decisionmesh.governance.policy.PolicyDecision;
import com.decisionmesh.governance.snapshot.PolicySnapshot;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.UUID;

@ApplicationScoped
public class PolicySnapshotBuilder {

    @Inject
    SubscriptionService subscriptionService;

    public Uni<PolicySnapshot> build(UUID orgId) {

        return subscriptionService.getByOrg(orgId)
                .map(sub -> {

                    PolicyDecision decision =
                            sub.status == SubscriptionEntity.Status.ACTIVE
                                    ? PolicyDecision.allow()
                                    : PolicyDecision.deny("Subscription inactive");

                    return new PolicySnapshot(
                            decision,
                            "v1",
                            "STRICT",
                            "{}",
                            sub.plan
                    );
                });
    }
}