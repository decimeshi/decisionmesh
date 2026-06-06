package com.decisionmesh.billing.service;


import com.decisionmesh.billing.model.SubscriptionEntity;
import com.decisionmesh.billing.model.SubscriptionEntity.Plan;
import com.decisionmesh.billing.model.SubscriptionEntity.Status;
import com.decisionmesh.billing.repository.SubscriptionRepository;
import org.mockito.Mock;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SubscriptionService.
 *
 * Tests verify:
 *   - New subscriptions are created when subscriptionId is not found
 *   - Existing subscriptions are updated in-place (no duplicate rows)
 *   - Status and plan changes are applied correctly
 *   - getByOrg throws when no subscription exists
 */
@ExtendWith(org.mockito.junit.jupiter.MockitoExtension.class)
class SubscriptionServiceTest {

    @Mock
    SubscriptionRepository repository;

    SubscriptionService service;

    private static final UUID   ORG_ID          = UUID.randomUUID();
    private static final String SUBSCRIPTION_ID = "sub_test_abc123";
    private static final String CUSTOMER_ID     = "cus_test_xyz456";

    @BeforeEach
    void setUp() {
        service = new SubscriptionService();
        service.repository = repository;
    }

    // ── createOrUpdate — new subscription ────────────────────────────────────

    @Test
    @DisplayName("New subscription is persisted when subscriptionId is not found in DB")
    void createOrUpdate_newSubscription_persistsEntity() {
        when(repository.findByStripeSubscriptionId(SUBSCRIPTION_ID))
                .thenReturn(Uni.createFrom().nullItem());
        when(repository.persist(any(SubscriptionEntity.class)))
                .thenAnswer(inv -> Uni.createFrom().item(inv.<SubscriptionEntity>getArgument(0)));

        SubscriptionEntity result = service
                .createOrUpdate(ORG_ID, CUSTOMER_ID, SUBSCRIPTION_ID, Plan.BUILDER, Status.ACTIVE)
                .await().indefinitely();

        verify(repository).persist(any(SubscriptionEntity.class));
        assertThat(result.orgId).isEqualTo(ORG_ID);
        assertThat(result.plan).isEqualTo(Plan.BUILDER);
        assertThat(result.status).isEqualTo(Status.ACTIVE);
        assertThat(result.stripeSubscriptionId).isEqualTo(SUBSCRIPTION_ID);
        assertThat(result.stripeCustomerId).isEqualTo(CUSTOMER_ID);
        assertThat(result.createdAt).isNotNull();
        assertThat(result.updatedAt).isNotNull();
    }

    @Test
    @DisplayName("New Razorpay subscription uses orderId as subscriptionId with null customerId")
    void createOrUpdate_razorpaySubscription_nullCustomerId_persists() {
        String razorpayOrderId = "order_razorpay_abc";
        when(repository.findByStripeSubscriptionId(razorpayOrderId))
                .thenReturn(Uni.createFrom().nullItem());
        when(repository.persist(any(SubscriptionEntity.class)))
                .thenAnswer(inv -> Uni.createFrom().item(inv.<SubscriptionEntity>getArgument(0)));

        SubscriptionEntity result = service
                .createOrUpdate(ORG_ID, null, razorpayOrderId, Plan.PRO, Status.ACTIVE)
                .await().indefinitely();

        assertThat(result.stripeCustomerId).isNull();
        assertThat(result.stripeSubscriptionId).isEqualTo(razorpayOrderId);
        assertThat(result.plan).isEqualTo(Plan.PRO);
    }

    // ── createOrUpdate — existing subscription ────────────────────────────────

    @Test
    @DisplayName("Existing subscription is updated in-place — no new persist called")
    void createOrUpdate_existingSubscription_updatesWithoutNewPersist() {
        SubscriptionEntity existing = existingEntity(Plan.FREE, Status.ACTIVE);
        when(repository.findByStripeSubscriptionId(SUBSCRIPTION_ID))
                .thenReturn(Uni.createFrom().item(existing));

        SubscriptionEntity result = service
                .createOrUpdate(ORG_ID, CUSTOMER_ID, SUBSCRIPTION_ID, Plan.BUILDER, Status.ACTIVE)
                .await().indefinitely();

        // Should NOT call persist — updating the existing managed entity is enough
        verify(repository, never()).persist(any(SubscriptionEntity.class));
        assertThat(result.plan).isEqualTo(Plan.BUILDER);
        assertThat(result.status).isEqualTo(Status.ACTIVE);
        assertThat(result.updatedAt).isAfterOrEqualTo(existing.createdAt);
    }

    @Test
    @DisplayName("Plan upgrade from FREE to PRO updates existing subscription")
    void createOrUpdate_planUpgrade_updatesExistingEntity() {
        SubscriptionEntity existing = existingEntity(Plan.FREE, Status.ACTIVE);
        when(repository.findByStripeSubscriptionId(SUBSCRIPTION_ID))
                .thenReturn(Uni.createFrom().item(existing));

        SubscriptionEntity result = service
                .createOrUpdate(ORG_ID, CUSTOMER_ID, SUBSCRIPTION_ID, Plan.PRO, Status.ACTIVE)
                .await().indefinitely();

        assertThat(result.plan).isEqualTo(Plan.PRO);
    }

    // ── updateStatus ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("updateStatus changes status on existing subscription")
    void updateStatus_existingOrg_updatesStatus() {
        SubscriptionEntity existing = existingEntity(Plan.BUILDER, Status.ACTIVE);
        when(repository.findByOrgId(ORG_ID)).thenReturn(Uni.createFrom().item(existing));

        service.updateStatus(ORG_ID, Status.CANCELED).await().indefinitely();

        assertThat(existing.status).isEqualTo(Status.CANCELED);
        assertThat(existing.updatedAt).isNotNull();
    }

    @Test
    @DisplayName("updateStatus with no existing subscription is a silent no-op")
    void updateStatus_noExistingSubscription_noOp() {
        when(repository.findByOrgId(ORG_ID)).thenReturn(Uni.createFrom().nullItem());

        // Should not throw
        assertThatCode(() -> service.updateStatus(ORG_ID, Status.CANCELED).await().indefinitely())
                .doesNotThrowAnyException();
    }

    // ── downgradePlan ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("downgradePlan changes plan to FREE on subscription.deleted webhook")
    void downgradePlan_toFree_updatesPlan() {
        SubscriptionEntity existing = existingEntity(Plan.BUILDER, Status.ACTIVE);
        when(repository.findByOrgId(ORG_ID)).thenReturn(Uni.createFrom().item(existing));

        service.downgradePlan(ORG_ID, Plan.FREE).await().indefinitely();

        assertThat(existing.plan).isEqualTo(Plan.FREE);
        assertThat(existing.updatedAt).isNotNull();
    }

    // ── getByOrg ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getByOrg throws RuntimeException when no subscription exists for org")
    void getByOrg_notFound_throwsRuntimeException() {
        when(repository.findByOrgId(ORG_ID))
                .thenReturn(Uni.createFrom().nullItem());

        assertThatThrownBy(() -> service.getByOrg(ORG_ID).await().indefinitely())
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Subscription not found");
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private SubscriptionEntity existingEntity(Plan plan, Status status) {
        SubscriptionEntity e = new SubscriptionEntity();
        e.orgId                = ORG_ID;
        e.stripeCustomerId     = CUSTOMER_ID;
        e.stripeSubscriptionId = SUBSCRIPTION_ID;
        e.plan                 = plan;
        e.status               = status;
        e.createdAt            = LocalDateTime.now().minusDays(10);
        e.updatedAt            = LocalDateTime.now().minusDays(10);
        return e;
    }
}