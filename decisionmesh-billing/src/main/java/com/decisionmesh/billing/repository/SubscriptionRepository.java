
package com.decisionmesh.billing.repository;

import com.decisionmesh.billing.model.SubscriptionEntity;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;

@ApplicationScoped
public class SubscriptionRepository implements PanacheRepositoryBase<SubscriptionEntity, UUID> {

    public Uni<SubscriptionEntity> findByOrgId(UUID orgId) {
        return find("orgId", orgId).firstResult();
    }

    public Uni<SubscriptionEntity> findByStripeSubscriptionId(String stripeSubId) {
        return find("stripeSubscriptionId", stripeSubId).firstResult();
    }
}
