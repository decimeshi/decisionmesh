package com.decisionmesh.billing.service;


import com.decisionmesh.billing.model.CreditLedgerEntity;
import com.decisionmesh.billing.model.SubscriptionEntity.Plan;
import com.decisionmesh.billing.repository.CreditLedgerRepository;
import org.mockito.Mock;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for CreditLedgerService.
 *
 * All tests use direct instantiation + Mockito repository mock.
 * No Quarkus container needed — @WithTransaction interceptors are bypassed
 * intentionally to isolate credit ledger business logic.
 *
 * Each test verifies:
 *   - The correct amount is written to the ledger
 *   - The correct reason code is used
 *   - The referenceId is set as expected
 */
@ExtendWith(org.mockito.junit.jupiter.MockitoExtension.class)
class CreditLedgerServiceTest {

    @Mock
    CreditLedgerRepository repository;

    CreditLedgerService service;

    private static final UUID ORG_ID    = UUID.randomUUID();
    private static final UUID INTENT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new CreditLedgerService();
        service.repository = repository;
        // lenient() prevents UnnecessaryStubbingException for tests that don't call persist()
        Mockito.lenient()
                .when(repository.persist(any(CreditLedgerEntity.class)))
                .thenAnswer(inv -> Uni.createFrom().item(inv.<CreditLedgerEntity>getArgument(0)));
    }

    // ── grantRegistrationGift ─────────────────────────────────────────────────

    @Test
    @DisplayName("Registration gift appends +100 with REGISTRATION_GIFT reason")
    void grantRegistrationGift_appends500_withCorrectReason() {
        service.grantRegistrationGift(ORG_ID).await().indefinitely();

        CreditLedgerEntity entry = captureEntry();
        assertThat(entry.orgId).isEqualTo(ORG_ID);
        assertThat(entry.amount).isEqualTo(100);   // updated: 500 → 100 per new free plan strategy
        assertThat(entry.reason).isEqualTo("REGISTRATION_GIFT");
        assertThat(entry.referenceId).isNull();
    }

    // ── resetMonthlyAllocation ────────────────────────────────────────────────

    @ParameterizedTest(name = "Plan {0} → {1} credits on monthly reset")
    @CsvSource({
            "BUILDER,  15000",
            "PRO,      60000",
    })
    @DisplayName("Monthly allocation reset grants plan-specific credit amount")
    void resetMonthlyAllocation_grantsPlanCredits(String planName, int expectedCredits) {
        Plan plan = Plan.valueOf(planName);
        service.resetMonthlyAllocation(ORG_ID, plan).await().indefinitely();

        CreditLedgerEntity entry = captureEntry();
        assertThat(entry.amount).isEqualTo(expectedCredits);
        assertThat(entry.reason).isEqualTo("SUBSCRIPTION");
        assertThat(entry.orgId).isEqualTo(ORG_ID);
    }

    @Test
    @DisplayName("FREE plan monthly reset is a no-op — no ledger entry written")
    void resetMonthlyAllocation_freePlan_noLedgerEntry() {
        service.resetMonthlyAllocation(ORG_ID, Plan.FREE).await().indefinitely();

        verify(repository, never()).persist(any(CreditLedgerEntity.class));
    }

    // ── grantPurchasedCredits ─────────────────────────────────────────────────

    @Test
    @DisplayName("Credit pack purchase appends correct amount with PURCHASE reason")
    void grantPurchasedCredits_appendsCorrectEntry() {
        String sessionId = "cs_test_abc123";
        service.grantPurchasedCredits(ORG_ID, 32000, sessionId).await().indefinitely();

        CreditLedgerEntity entry = captureEntry();
        assertThat(entry.amount).isEqualTo(32000);
        assertThat(entry.reason).isEqualTo("PURCHASE");
        assertThat(entry.referenceId).isEqualTo(sessionId);
        assertThat(entry.orgId).isEqualTo(ORG_ID);
    }

    @ParameterizedTest(name = "Pack {0} → {1} credits")
    @CsvSource({
            "Starter, 12000",
            "Growth,  32000",
            "Scale,  100000",
    })
    @DisplayName("All credit pack sizes are written to ledger correctly")
    void grantPurchasedCredits_allPackSizes_writeCorrectAmount(String packName, int credits) {
        service.grantPurchasedCredits(ORG_ID, credits, "session_" + packName)
                .await().indefinitely();

        assertThat(captureEntry().amount).isEqualTo(credits);
    }

    // ── grantReferralBonus ────────────────────────────────────────────────────

    @Test
    @DisplayName("Referral bonus appends correct amount with REFERRAL reason")
    void grantReferralBonus_appendsCorrectEntry() {
        service.grantReferralBonus(ORG_ID, 200).await().indefinitely();

        CreditLedgerEntity entry = captureEntry();
        assertThat(entry.amount).isEqualTo(200);
        assertThat(entry.reason).isEqualTo("REFERRAL");
    }

    // ── debitExecution ────────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0} model tier debits -{1} credits")
    @CsvSource({
            "Economy,   1",
            "Standard,  5",
            "Premium,  25",
    })
    @DisplayName("Execution debit writes negative amount matching model tier cost")
    void debitExecution_modelTiers_debitCorrectCredits(String tier, int tierCredits) {
        service.debitExecution(ORG_ID, INTENT_ID, tierCredits).await().indefinitely();

        CreditLedgerEntity entry = captureEntry();
        assertThat(entry.amount).isEqualTo(-tierCredits);
        assertThat(entry.reason).isEqualTo("INTENT_EXECUTION");
        assertThat(entry.referenceId).isEqualTo(INTENT_ID.toString());
        assertThat(entry.orgId).isEqualTo(ORG_ID);
    }

    // ── debitRetry ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Retry debit writes negative amount with RETRY reason")
    void debitRetry_appendsNegativeAmountWithRetryReason() {
        service.debitRetry(ORG_ID, INTENT_ID, 5).await().indefinitely();

        CreditLedgerEntity entry = captureEntry();
        assertThat(entry.amount).isEqualTo(-5);
        assertThat(entry.reason).isEqualTo("RETRY");
        assertThat(entry.referenceId).isEqualTo(INTENT_ID.toString());
    }

    // ── refundExecution ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Execution refund writes positive amount with REFUND reason")
    void refundExecution_appendsPositiveAmountWithRefundReason() {
        service.refundExecution(ORG_ID, INTENT_ID, 25).await().indefinitely();

        CreditLedgerEntity entry = captureEntry();
        assertThat(entry.amount).isEqualTo(25);
        assertThat(entry.reason).isEqualTo("REFUND");
        assertThat(entry.referenceId).isEqualTo(INTENT_ID.toString());
    }

    // ── adminAdjustment ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Admin credit grant writes positive amount with ADMIN_ADJUSTMENT reason")
    void adminAdjustment_grant_appendsPositiveAmount() {
        service.adminAdjustment(ORG_ID, 1000, "onboarding bonus", "admin-user-id")
                .await().indefinitely();

        CreditLedgerEntity entry = captureEntry();
        assertThat(entry.amount).isEqualTo(1000);
        assertThat(entry.reason).isEqualTo("ADMIN_ADJUSTMENT");
        assertThat(entry.referenceId).contains("admin-user-id").contains("onboarding bonus");
    }

    @Test
    @DisplayName("Admin credit deduction writes negative amount")
    void adminAdjustment_deduct_appendsNegativeAmount() {
        service.adminAdjustment(ORG_ID, -500, "correction", "admin-user-id")
                .await().indefinitely();

        assertThat(captureEntry().amount).isEqualTo(-500);
    }

    // ── getBalance ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getBalance returns sum from repository")
    void getBalance_returnsRepositorySum() {
        Mockito.when(repository.sumByOrgId(ORG_ID)).thenReturn(Uni.createFrom().item(15500L));

        long balance = service.getBalance(ORG_ID).await().indefinitely();

        assertThat(balance).isEqualTo(15500L);
    }

    @Test
    @DisplayName("getBalance returns 0 when repository returns null (empty ledger)")
    void getBalance_nullSum_returnsZero() {
        Mockito.when(repository.sumByOrgId(ORG_ID)).thenReturn(Uni.createFrom().item((Long) null));

        long balance = service.getBalance(ORG_ID).await().indefinitely();

        assertThat(balance).isZero();
    }

    // ── hasSufficientCredits ──────────────────────────────────────────────────

    @Test
    @DisplayName("hasSufficientCredits returns true when balance meets requirement")
    void hasSufficientCredits_sufficient_returnsTrue() {
        Mockito.when(repository.sumByOrgId(ORG_ID)).thenReturn(Uni.createFrom().item(100L));

        boolean result = service.hasSufficientCredits(ORG_ID, 100).await().indefinitely();

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("hasSufficientCredits returns false when balance is below requirement")
    void hasSufficientCredits_insufficient_returnsFalse() {
        Mockito.when(repository.sumByOrgId(ORG_ID)).thenReturn(Uni.createFrom().item(4L));

        boolean result = service.hasSufficientCredits(ORG_ID, 5).await().indefinitely();

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("hasSufficientCredits returns false when balance is zero")
    void hasSufficientCredits_zeroBalance_returnsFalse() {
        Mockito.when(repository.sumByOrgId(ORG_ID)).thenReturn(Uni.createFrom().item(0L));

        assertThat(service.hasSufficientCredits(ORG_ID, 1).await().indefinitely()).isFalse();
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    /** Captures the single CreditLedgerEntity passed to repository.persist(). */
    private CreditLedgerEntity captureEntry() {
        ArgumentCaptor<CreditLedgerEntity> captor = ArgumentCaptor.forClass(CreditLedgerEntity.class);
        verify(repository).persist(captor.capture());
        return captor.getValue();
    }
}