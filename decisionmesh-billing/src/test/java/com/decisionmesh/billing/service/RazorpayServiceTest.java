package com.decisionmesh.billing.service;


import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for RazorpayService.
 *
 * Signature tests are pure HMAC-SHA256 — no mocking needed.
 * Order/subscription creation tests use sandbox mode (keyId blank)
 * so no real HTTP calls to Razorpay API are made.
 */
class RazorpayServiceTest {

    private RazorpayService service;

    private static final String TEST_KEY_SECRET     = "test_key_secret_abcdef123456";
    private static final String TEST_WEBHOOK_SECRET = "test_webhook_secret_xyz789";
    private static final String TEST_ORG_ID         = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() throws Exception {
        service = new RazorpayService();
        // Leave keyId blank → sandbox mode (no HTTP calls)
        service.keyId              = "";
        service.keySecret          = TEST_KEY_SECRET;
        service.webhookSecret      = TEST_WEBHOOK_SECRET;
        // Subscription plan IDs (from application.properties)
        service.planBuilder           = "plan_SdDtQreYZOuDuZ";
        service.planPro               = "plan_SdDv8HzOQxPoFm";
        service.planBuilderQuarterly  = "plan_SdR7GBM6uj4NqV";
        service.planBuilderHalfYearly = "plan_SdR8W9gg1aXHkK";
        service.planBuilderYearly     = "plan_SdR92osLF8JvPx";
        service.planProQuarterly      = "plan_SdR9crmN0Nxxzj";
        service.planProHalfYearly     = "plan_SdRA09r9Rs9KA0";
        service.planProYearly         = "plan_SdRAPgVKuMooyq";
        // Credit pack amounts (paise)
        service.amountCreditsStarter = 84900L;
        service.amountCreditsGrowth  = 209900L;
        service.amountCreditsScale   = 629900L;
        service.init();
    }

    // ── verifyPaymentSignature ────────────────────────────────────────────────

    @Test
    @DisplayName("Correct HMAC-SHA256 signature passes payment verification")
    void verifyPaymentSignature_validSignature_returnsTrue() throws Exception {
        String orderId   = "order_test123ABC";
        String paymentId = "pay_test456DEF";
        String signature = hmac(orderId + "|" + paymentId, TEST_KEY_SECRET);

        assertThat(service.verifyPaymentSignature(orderId, paymentId, signature)).isTrue();
    }

    @Test
    @DisplayName("Wrong signature fails payment verification")
    void verifyPaymentSignature_wrongSignature_returnsFalse() {
        assertThat(service.verifyPaymentSignature("order_x", "pay_x", "badhex000")).isFalse();
    }

    @Test
    @DisplayName("Tampered orderId produces signature mismatch")
    void verifyPaymentSignature_tamperedOrderId_returnsFalse() throws Exception {
        String orderId   = "order_original_123";
        String paymentId = "pay_test456";
        String signature = hmac(orderId + "|" + paymentId, TEST_KEY_SECRET);

        assertThat(service.verifyPaymentSignature("order_TAMPERED_999", paymentId, signature)).isFalse();
    }

    @Test
    @DisplayName("Tampered paymentId produces signature mismatch")
    void verifyPaymentSignature_tamperedPaymentId_returnsFalse() throws Exception {
        String orderId   = "order_abc";
        String paymentId = "pay_original";
        String signature = hmac(orderId + "|" + paymentId, TEST_KEY_SECRET);

        assertThat(service.verifyPaymentSignature(orderId, "pay_TAMPERED", signature)).isFalse();
    }

    // ── verifyWebhookSignature ────────────────────────────────────────────────

    @Test
    @DisplayName("Valid webhook signature passes")
    void verifyWebhookSignature_validSignature_returnsTrue() throws Exception {
        String payload   = "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_test\"}}}}";
        String signature = hmac(payload, TEST_WEBHOOK_SECRET);

        assertThat(service.verifyWebhookSignature(payload, signature)).isTrue();
    }

    @Test
    @DisplayName("Invalid webhook signature fails")
    void verifyWebhookSignature_invalidSignature_returnsFalse() {
        assertThat(service.verifyWebhookSignature("{\"event\":\"payment.captured\"}", "invalidsig")).isFalse();
    }

    @Test
    @DisplayName("Tampered webhook payload fails signature check — prevents replay attacks")
    void verifyWebhookSignature_tamperedPayload_returnsFalse() throws Exception {
        String original  = "{\"event\":\"payment.captured\",\"amount\":84900}";
        String signature = hmac(original, TEST_WEBHOOK_SECRET);
        String tampered  = "{\"event\":\"payment.captured\",\"amount\":1}";

        assertThat(service.verifyWebhookSignature(tampered, signature)).isFalse();
    }

    // ── createOrder — subscription (sandbox mode) ─────────────────────────────

    @Test
    @DisplayName("Subscription order returns subscriptionId and null orderId")
    void createOrder_subscription_sandbox_returnsSubscriptionId_notOrderId() throws Exception {
        var result = service.createOrder("plan_SdDtQreYZOuDuZ", TEST_ORG_ID, "subscription", 0);

        assertThat(result.subscriptionId()).isNotBlank().startsWith("sub_sandbox_");
        assertThat(result.orderId()).isNull();
    }

    @ParameterizedTest(name = "{0} subscription resolves plan ID {1}")
    @CsvSource({
            "builder monthly,    plan_SdDtQreYZOuDuZ",
            "builder quarterly,  plan_SdR7GBM6uj4NqV",
            "builder halfyearly, plan_SdR8W9gg1aXHkK",
            "builder yearly,     plan_SdR92osLF8JvPx",
            "pro monthly,        plan_SdDv8HzOQxPoFm",
            "pro quarterly,      plan_SdR9crmN0Nxxzj",
            "pro halfyearly,     plan_SdRA09r9Rs9KA0",
            "pro yearly,         plan_SdRAPgVKuMooyq",
    })
    @DisplayName("Subscription order echoes priceId from properties")
    void createOrder_subscription_sandbox_echoesPriceId(String label, String planId) throws Exception {
        var result = service.createOrder(planId.trim(), TEST_ORG_ID, "subscription", 0);

        assertThat(result.priceId()).isEqualTo(planId.trim());
        assertThat(result.mode()).isEqualTo("subscription");
    }

    // ── createOrder — one-time payment (sandbox mode) ─────────────────────────

    @Test
    @DisplayName("Credit pack order returns orderId and null subscriptionId")
    void createOrder_payment_sandbox_returnsOrderId_notSubscriptionId() throws Exception {
        var result = service.createOrder("credits_starter", TEST_ORG_ID, "payment", 12000);

        assertThat(result.orderId()).isNotBlank().startsWith("order_sandbox_");
        assertThat(result.subscriptionId()).isNull();
    }

    @ParameterizedTest(name = "{0} → {1} paise (INR)")
    @CsvSource({
            "credits_starter, 84900,  12000",
            "credits_growth,  209900, 32000",
            "credits_scale,   629900, 100000",
    })
    @DisplayName("Credit pack returns correct INR paise amount from properties")
    void createOrder_payment_sandbox_returnsCorrectAmount(String priceId, long expectedPaise, int credits) throws Exception {
        var result = service.createOrder(priceId, TEST_ORG_ID, "payment", credits);

        assertThat(result.amount()).isEqualTo(expectedPaise);
        assertThat(result.currency()).isEqualTo("INR");
        assertThat(result.creditAmount()).isEqualTo(credits);
    }

    @Test
    @DisplayName("Unknown priceId throws RuntimeException with descriptive message")
    void createOrder_unknownPriceId_throwsRuntimeException() {
        assertThatThrownBy(() -> service.createOrder("credits_unknown", TEST_ORG_ID, "payment", 0))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Unknown Razorpay priceId")
                .hasMessageContaining("credits_unknown");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String hmac(String payload, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }
}