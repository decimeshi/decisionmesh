package com.decisionmesh.billing.service;

import com.decisionmesh.billing.model.SubscriptionEntity;
import com.decisionmesh.billing.model.SubscriptionEntity.Plan;
import com.decisionmesh.billing.model.SubscriptionEntity.Status;
import com.decisionmesh.billing.repository.SubscriptionRepository;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.LocalDateTime;
import java.util.UUID;

@ApplicationScoped
public class SubscriptionService {

    @Inject
    SubscriptionRepository repository;

    // ── Create or update ──────────────────────────────────────────────────────

    @WithTransaction
    public Uni<SubscriptionEntity> createOrUpdate(UUID orgId,
                                                  String customerId,
                                                  String subscriptionId,
                                                  Plan plan,
                                                  Status status) {
        return repository.findByStripeSubscriptionId(subscriptionId)
                .onItem().ifNotNull().transform(existing -> {
                    existing.plan      = plan;
                    existing.status    = status;
                    existing.updatedAt = LocalDateTime.now();
                    Log.infof("[Subscription] Updated: orgId=%s plan=%s status=%s", orgId, plan, status);
                    return existing;
                })
                .onItem().ifNull().switchTo(() -> {
                    SubscriptionEntity entity = new SubscriptionEntity();
                    entity.orgId                = orgId;
                    entity.stripeCustomerId     = customerId;
                    entity.stripeSubscriptionId = subscriptionId;
                    entity.plan                 = plan;
                    entity.status               = status;
                    entity.createdAt            = LocalDateTime.now();
                    entity.updatedAt            = LocalDateTime.now();
                    Log.infof("[Subscription] Created: orgId=%s plan=%s", orgId, plan);
                    return repository.persist(entity).replaceWith(entity);
                });
    }

    // ── Status update ─────────────────────────────────────────────────────────

    @WithTransaction
    public Uni<Void> updateStatus(UUID orgId, Status status) {
        return repository.findByOrgId(orgId)
                .onItem().ifNotNull().transformToUni(sub -> {
                    sub.status    = status;
                    sub.updatedAt = LocalDateTime.now();
                    Log.infof("[Subscription] Status → %s: orgId=%s", status, orgId);
                    return Uni.createFrom().voidItem();
                })
                .onItem().ifNull().continueWith((Void) null);
    }

    // ── Plan downgrade ────────────────────────────────────────────────────────

    @WithTransaction
    public Uni<Void> downgradePlan(UUID orgId, Plan plan) {
        return repository.findByOrgId(orgId)
                .onItem().ifNotNull().transformToUni(sub -> {
                    sub.plan      = plan;
                    sub.updatedAt = LocalDateTime.now();
                    Log.infof("[Subscription] Downgraded to %s: orgId=%s", plan, orgId);
                    return Uni.createFrom().voidItem();
                })
                .onItem().ifNull().continueWith((Void) null);
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    public Uni<SubscriptionEntity> getByOrg(UUID orgId) {
        return repository.findByOrgId(orgId)
                .onItem().ifNull().failWith(() ->
                        new RuntimeException("Subscription not found for orgId: " + orgId));
    }
}
