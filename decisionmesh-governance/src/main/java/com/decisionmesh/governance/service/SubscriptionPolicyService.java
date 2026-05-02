
package com.decisionmesh.governance.service;

import com.decisionmesh.billing.service.SubscriptionService;
import com.decisionmesh.billing.model.SubscriptionEntity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.UUID;

@ApplicationScoped
public class SubscriptionPolicyService {

    @Inject
    SubscriptionService subscriptionService;

    public Uni<Boolean> isAllowed(UUID orgId) {
        return subscriptionService.getByOrg(orgId)
                .map(sub -> sub.status == SubscriptionEntity.Status.ACTIVE)
                .onItem().ifNull().continueWith(false);
    }
}
