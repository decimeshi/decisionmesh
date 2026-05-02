package com.decisionmesh.billing.service;

import com.decisionmesh.billing.model.CreditLedgerEntity;
import com.decisionmesh.billing.repository.CreditLedgerRepository;
import com.decisionmesh.billing.model.SubscriptionEntity.Plan;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.UUID;

/**
 * Manages the credit ledger for every org.
 *
 * Credit reasons:
 *   REGISTRATION_GIFT   — 500 credits on first signup, one-time
 *   SUBSCRIPTION        — monthly allocation reset on invoice.payment_succeeded
 *   PURCHASE            — one-time credit pack purchase
 *   REFERRAL            — referral conversion bonus
 *   INTENT_EXECUTION    — debit per intent attempt
 *   RETRY               — debit per retry attempt
 *   REFUND              — credit back when execution fails before any LLM call
 *   ADMIN_ADJUSTMENT    — manual override
 */
@ApplicationScoped
public class CreditLedgerService {

    @Inject
    CreditLedgerRepository repository;

    // ── Read ──────────────────────────────────────────────────────────────────

    public Uni<Long> getBalance(UUID orgId) {
        return repository.sumByOrgId(orgId)
                .map(sum -> sum == null ? 0L : sum);
    }

    // ── Earn ──────────────────────────────────────────────────────────────────

    @WithTransaction
    public Uni<Void> grantRegistrationGift(UUID orgId) {
        Log.infof("[Credits] Registration gift: orgId=%s +500", orgId);
        return append(orgId, 500, "REGISTRATION_GIFT", null);
    }

    /**
     * Called on invoice.payment_succeeded — resets monthly allocation.
     * Issues a negative entry to zero out previous month then grants new allocation.
     */
    @WithTransaction
    public Uni<Void> resetMonthlyAllocation(UUID orgId, Plan plan) {
        int credits = plan.monthlyCredits();
        if (credits <= 0) {
            return Uni.createFrom().voidItem();
        }
        Log.infof("[Credits] Monthly reset: orgId=%s plan=%s +%d", orgId, plan, credits);
        return append(orgId, credits, "SUBSCRIPTION", null);
    }

    /**
     * Called on checkout.session.completed for one-time credit pack purchases.
     */
    @WithTransaction
    public Uni<Void> grantPurchasedCredits(UUID orgId, int credits, String stripeSessionId) {
        Log.infof("[Credits] Pack purchase: orgId=%s +%d session=%s", orgId, credits, stripeSessionId);
        return append(orgId, credits, "PURCHASE", stripeSessionId);
    }

    @WithTransaction
    public Uni<Void> grantReferralBonus(UUID orgId, int credits) {
        Log.infof("[Credits] Referral bonus: orgId=%s +%d", orgId, credits);
        return append(orgId, credits, "REFERRAL", null);
    }

    // ── Debit ─────────────────────────────────────────────────────────────────

    /**
     * Debit credits for an intent execution.
     * tierCredits = 1 (Economy) | 5 (Standard) | 25 (Premium)
     */
    @WithTransaction
    public Uni<Void> debitExecution(UUID orgId, UUID intentId, int tierCredits) {
        Log.infof("[Credits] Execution debit: orgId=%s intentId=%s -%d", orgId, intentId, tierCredits);
        return append(orgId, -tierCredits, "INTENT_EXECUTION", intentId.toString());
    }

    @WithTransaction
    public Uni<Void> debitRetry(UUID orgId, UUID intentId, int tierCredits) {
        Log.debugf("[Credits] Retry debit: orgId=%s intentId=%s -%d", orgId, intentId, tierCredits);
        return append(orgId, -tierCredits, "RETRY", intentId.toString());
    }

    @WithTransaction
    public Uni<Void> refundExecution(UUID orgId, UUID intentId, int tierCredits) {
        Log.infof("[Credits] Execution refund: orgId=%s intentId=%s +%d", orgId, intentId, tierCredits);
        return append(orgId, tierCredits, "REFUND", intentId.toString());
    }

    // ── Convenience check ─────────────────────────────────────────────────────

    public Uni<Boolean> hasSufficientCredits(UUID orgId, int required) {
        return getBalance(orgId).map(balance -> balance >= required);
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private Uni<Void> append(UUID orgId, int amount, String reason, String referenceId) {
        CreditLedgerEntity entry = new CreditLedgerEntity();
        entry.orgId       = orgId;
        entry.amount      = amount;
        entry.reason      = reason;
        entry.referenceId = referenceId;
        entry.createdAt   = Instant.now();
        return repository.persist(entry).replaceWithVoid();
    }
}
