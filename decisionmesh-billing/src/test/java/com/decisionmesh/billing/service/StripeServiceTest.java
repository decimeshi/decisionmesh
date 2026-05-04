package com.decisionmesh.billing.service;


import com.stripe.model.checkout.Session;
import com.stripe.param.checkout.SessionCreateParams;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for StripeService.
 *
 * resolvePrice() tests are pure map lookups — no mocking needed.
 * Checkout tests mock Session.create() via Mockito static mocking
 * (requires mockito-inline on the classpath).
 *
 * Required pom.xml dependency:
 *   <dependency>
 *     <groupId>org.mockito</groupId>
 *     <artifactId>mockito-inline</artifactId>
 *     <scope>test</scope>
 *   </dependency>
 */
class StripeServiceTest {

    private StripeService service;

    // Price IDs matching application.properties
    private static final String PRICE_HOBBY              = "price_1TLxLuCNFjCgvrWwlVhrIuSI";
    private static final String PRICE_BUILDER            = "price_1TLxJPCNFjCgvrWwtI1Gc0yA";
    private static final String PRICE_PRO                = "price_1TLxKLCNFjCgvrWwfYnN1xBY";
    private static final String PRICE_CREDITS_STARTER    = "price_1TLxPKCNFjCgvrWwv6XIuo2N";
    private static final String PRICE_CREDITS_GROWTH     = "price_1TLxQBCNFjCgvrWw0KPetcMq";
    private static final String PRICE_CREDITS_SCALE      = "price_1TLxR6CNFjCgvrWwYWcUL1Bc";
    private static final String PRICE_BUILDER_QUARTERLY  = "price_1TM9RzCNFjCgvrWwJwURaYvO";
    private static final String PRICE_BUILDER_HALFYEARLY = "price_1TM9RzCNFjCgvrWwsoHtJHC6";
    private static final String PRICE_BUILDER_YEARLY     = "price_1TM9RzCNFjCgvrWwuOpeo8GB";
    private static final String PRICE_PRO_QUARTERLY      = "price_1TM9uaCNFjCgvrWwllLFWRsn";
    private static final String PRICE_PRO_HALFYEARLY     = "price_1TM9uaCNFjCgvrWwsdCcalQX";
    private static final String PRICE_PRO_YEARLY         = "price_1TM9uaCNFjCgvrWw2o7b6OSK";

    @BeforeEach
    void setUp() {
        service = new StripeService();
        service.stripeSecretKey       = "sk_test_placeholder";
        service.successUrl            = "http://localhost:3000/billing?success=1";
        service.cancelUrl             = "http://localhost:3000/billing?cancelled=1";
        service.priceHobby            = PRICE_HOBBY;
        service.priceBuilder          = PRICE_BUILDER;
        service.pricePro              = PRICE_PRO;
        service.priceCreditsStarter   = PRICE_CREDITS_STARTER;
        service.priceCreditsGrowth    = PRICE_CREDITS_GROWTH;
        service.priceCreditsScale     = PRICE_CREDITS_SCALE;
        service.priceBuilderQuarterly  = PRICE_BUILDER_QUARTERLY;
        service.priceBuilderHalfYearly = PRICE_BUILDER_HALFYEARLY;
        service.priceBuilderYearly     = PRICE_BUILDER_YEARLY;
        service.priceProQuarterly      = PRICE_PRO_QUARTERLY;
        service.priceProHalfYearly     = PRICE_PRO_HALFYEARLY;
        service.priceProYearly         = PRICE_PRO_YEARLY;
        service.init();
    }

    // ── resolvePrice — all 12 keys ────────────────────────────────────────────

    @ParameterizedTest(name = "Key \"{0}\" resolves to expected price ID")
    @CsvSource({
            "hobby,              price_1TLxLuCNFjCgvrWwlVhrIuSI",
            "builder,            price_1TLxJPCNFjCgvrWwtI1Gc0yA",
            "pro,                price_1TLxKLCNFjCgvrWwfYnN1xBY",
            "credits_starter,    price_1TLxPKCNFjCgvrWwv6XIuo2N",
            "credits_growth,     price_1TLxQBCNFjCgvrWw0KPetcMq",
            "credits_scale,      price_1TLxR6CNFjCgvrWwYWcUL1Bc",
            "builder_quarterly,  price_1TM9RzCNFjCgvrWwJwURaYvO",
            "builder_halfyearly, price_1TM9RzCNFjCgvrWwsoHtJHC6",
            "builder_yearly,     price_1TM9RzCNFjCgvrWwuOpeo8GB",
            "pro_quarterly,      price_1TM9uaCNFjCgvrWwllLFWRsn",
            "pro_halfyearly,     price_1TM9uaCNFjCgvrWwsdCcalQX",
            "pro_yearly,         price_1TM9uaCNFjCgvrWw2o7b6OSK",
    })
    @DisplayName("resolvePrice maps all configured keys to correct Stripe price IDs")
    void resolvePrice_allConfiguredKeys_returnCorrectPriceId(String key, String expectedPriceId) {
        assertThat(service.resolvePrice(key.trim())).isEqualTo(expectedPriceId.trim());
    }

    @Test
    @DisplayName("Unknown key throws IllegalArgumentException with key name in message")
    void resolvePrice_unknownKey_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.resolvePrice("starter"))  // wrong — should be "credits_starter"
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No Stripe price configured for key")
                .hasMessageContaining("starter");
    }

    @Test
    @DisplayName("Empty string key throws IllegalArgumentException")
    void resolvePrice_emptyKey_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.resolvePrice(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Null key throws NullPointerException or IllegalArgumentException")
    void resolvePrice_nullKey_throws() {
        assertThatThrownBy(() -> service.resolvePrice(null))
                .isInstanceOf(Exception.class);
    }

    // ── createSubscriptionCheckout ────────────────────────────────────────────

    @Test
    @DisplayName("Subscription checkout creates Stripe session and returns hosted URL")
    void createSubscriptionCheckout_builderMonthly_returnsCheckoutUrl() throws Exception {
        String expectedUrl = "https://checkout.stripe.com/c/pay/cs_test_builder";

        try (MockedStatic<Session> sessionMock = Mockito.mockStatic(Session.class)) {
            Session mockSession = mock(Session.class);
            when(mockSession.getUrl()).thenReturn(expectedUrl);
            sessionMock.when(() -> Session.create(any(SessionCreateParams.class)))
                    .thenReturn(mockSession);

            String url = service.createSubscriptionCheckout(
                    "user@example.com", PRICE_BUILDER, "org-123", "BUILDER", "monthly");

            assertThat(url).isEqualTo(expectedUrl);
            sessionMock.verify(() -> Session.create(any(SessionCreateParams.class)), times(1));
        }
    }

    @Test
    @DisplayName("Subscription checkout embeds orgId and plan in session metadata")
    void createSubscriptionCheckout_embedsCorrectMetadata() throws Exception {
        try (MockedStatic<Session> sessionMock = Mockito.mockStatic(Session.class)) {
            Session mockSession = mock(Session.class);
            when(mockSession.getUrl()).thenReturn("https://checkout.stripe.com/test");

            // Capture the params to verify metadata
            var capturedParams = new java.util.concurrent.atomic.AtomicReference<SessionCreateParams>();
            sessionMock.when(() -> Session.create(any(SessionCreateParams.class)))
                    .thenAnswer(inv -> {
                        capturedParams.set(inv.getArgument(0));
                        return mockSession;
                    });

            service.createSubscriptionCheckout(
                    "user@example.com", PRICE_PRO, "org-uuid-456", "PRO", "quarterly");

            SessionCreateParams params = capturedParams.get();
            assertThat(params.getMetadata()).containsEntry("orgId", "org-uuid-456");
            assertThat(params.getMetadata()).containsEntry("plan", "PRO");
            assertThat(params.getMetadata()).containsEntry("interval", "quarterly");
            assertThat(params.getMetadata()).containsEntry("mode", "subscription");
            assertThat(params.getMode()).isEqualTo(SessionCreateParams.Mode.SUBSCRIPTION);
        }
    }

    @Test
    @DisplayName("Subscription checkout success URL appends session_id placeholder")
    void createSubscriptionCheckout_successUrlContainsSessionId() throws Exception {
        try (MockedStatic<Session> sessionMock = Mockito.mockStatic(Session.class)) {
            Session mockSession = mock(Session.class);
            when(mockSession.getUrl()).thenReturn("https://checkout.stripe.com/test");

            var capturedParams = new java.util.concurrent.atomic.AtomicReference<SessionCreateParams>();
            sessionMock.when(() -> Session.create(any(SessionCreateParams.class)))
                    .thenAnswer(inv -> { capturedParams.set(inv.getArgument(0)); return mockSession; });

            service.createSubscriptionCheckout("u@e.com", PRICE_HOBBY, "org1", "HOBBY", "monthly");

            assertThat(capturedParams.get().getSuccessUrl()).contains("session_id={CHECKOUT_SESSION_ID}");
        }
    }

    // ── createCreditPackCheckout ──────────────────────────────────────────────

    @Test
    @DisplayName("Credit pack checkout creates one-time PAYMENT mode session")
    void createCreditPackCheckout_growthPack_returnsCheckoutUrl() throws Exception {
        String expectedUrl = "https://checkout.stripe.com/c/pay/cs_test_growth";

        try (MockedStatic<Session> sessionMock = Mockito.mockStatic(Session.class)) {
            Session mockSession = mock(Session.class);
            when(mockSession.getUrl()).thenReturn(expectedUrl);
            sessionMock.when(() -> Session.create(any(SessionCreateParams.class)))
                    .thenReturn(mockSession);

            String url = service.createCreditPackCheckout(
                    "user@example.com", PRICE_CREDITS_GROWTH, "org-789", 32000);

            assertThat(url).isEqualTo(expectedUrl);
        }
    }

    @Test
    @DisplayName("Credit pack checkout uses PAYMENT mode and embeds creditAmount in metadata")
    void createCreditPackCheckout_embedsCreditAmountAndPaymentMode() throws Exception {
        try (MockedStatic<Session> sessionMock = Mockito.mockStatic(Session.class)) {
            Session mockSession = mock(Session.class);
            when(mockSession.getUrl()).thenReturn("https://checkout.stripe.com/test");

            var capturedParams = new java.util.concurrent.atomic.AtomicReference<SessionCreateParams>();
            sessionMock.when(() -> Session.create(any(SessionCreateParams.class)))
                    .thenAnswer(inv -> { capturedParams.set(inv.getArgument(0)); return mockSession; });

            service.createCreditPackCheckout("user@example.com", PRICE_CREDITS_SCALE, "org-abc", 100000);

            SessionCreateParams params = capturedParams.get();
            assertThat(params.getMode()).isEqualTo(SessionCreateParams.Mode.PAYMENT);
            assertThat(params.getMetadata()).containsEntry("creditAmount", "100000");
            assertThat(params.getMetadata()).containsEntry("orgId", "org-abc");
            assertThat(params.getMetadata()).containsEntry("mode", "credit_pack");
        }
    }

    @Test
    @DisplayName("Credit pack success URL appends credits count for frontend toast")
    void createCreditPackCheckout_successUrlContainsCreditsCount() throws Exception {
        try (MockedStatic<Session> sessionMock = Mockito.mockStatic(Session.class)) {
            Session mockSession = mock(Session.class);
            when(mockSession.getUrl()).thenReturn("https://checkout.stripe.com/test");

            var capturedParams = new java.util.concurrent.atomic.AtomicReference<SessionCreateParams>();
            sessionMock.when(() -> Session.create(any(SessionCreateParams.class)))
                    .thenAnswer(inv -> { capturedParams.set(inv.getArgument(0)); return mockSession; });

            service.createCreditPackCheckout("u@e.com", PRICE_CREDITS_STARTER, "org1", 12000);

            assertThat(capturedParams.get().getSuccessUrl()).contains("credits=12000");
        }
    }
}