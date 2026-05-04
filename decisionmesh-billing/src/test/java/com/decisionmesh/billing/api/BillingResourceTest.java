
package com.decisionmesh.billing.api;

import com.decisionmesh.billing.service.*;
import com.decisionmesh.billing.model.SubscriptionEntity.Plan;
import com.decisionmesh.billing.model.SubscriptionEntity.Status;
import com.decisionmesh.billing.service.RazorpayService.RazorpayOrderResponse;
import com.decisionmesh.contracts.security.context.TenantContext;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Integration tests for BillingResource REST endpoints.
 *
 * Uses @QuarkusTest to start the full Quarkus container.
 * All services are mocked via @InjectMock — no real Stripe or Razorpay calls.
 * TenantContext is mocked to return a fixed test tenant ID.
 *
 * @TestSecurity provides JWT roles without a real OIDC provider.
 */
@QuarkusTest
@TestSecurity(user = "testuser", roles = {"tenant_user", "sys_admin"})
class BillingResourceTest {

    private static final UUID   TEST_TENANT_ID   = UUID.fromString("731f3338-0d74-43b0-b278-a01eda5872fb");
    private static final String TEST_EMAIL        = "test@decisionmesh.io";
    private static final String TEST_WEBHOOK_SECRET = "test_webhook_secret_xyz789";

    @InjectMock TenantContext      tenantContext;
    @InjectMock StripeService      stripeService;
    @InjectMock RazorpayService    razorpayService;
    @InjectMock CreditLedgerService creditLedgerService;
    @InjectMock SubscriptionService subscriptionService;

    @BeforeEach
    void setUp() {
        when(tenantContext.getTenantId()).thenReturn(TEST_TENANT_ID);
    }

    // =========================================================================
    // POST /api/billing/checkout — Stripe
    // =========================================================================

    @Test
    @DisplayName("Stripe subscription checkout returns checkoutUrl for Builder monthly")
    void createCheckout_stripe_builderMonthly_returnsCheckoutUrl() throws Exception {
        String expectedUrl = "https://checkout.stripe.com/c/pay/cs_test_builder_monthly";
        when(stripeService.resolvePrice("builder")).thenReturn("price_1TLxJPCNFjCgvrWwtI1Gc0yA");
        when(stripeService.createSubscriptionCheckout(any(), any(), any(), any(), any()))
                .thenReturn(expectedUrl);

        given()
                .contentType(ContentType.JSON)
                .body("""
                    {"email":"%s","plan":"builder","mode":"subscription","interval":"monthly"}
                    """.formatted(TEST_EMAIL))
                .when()
                .post("/api/billing/checkout")
                .then()
                .statusCode(200)
                .body("checkoutUrl", equalTo(expectedUrl));
    }

    @Test
    @DisplayName("Stripe subscription checkout returns checkoutUrl for Builder quarterly")
    void createCheckout_stripe_builderQuarterly_returnsCheckoutUrl() throws Exception {
        String expectedUrl = "https://checkout.stripe.com/c/pay/cs_test_builder_quarterly";
        when(stripeService.resolvePrice("builder_quarterly")).thenReturn("price_1TM9RzCNFjCgvrWwJwURaYvO");
        when(stripeService.createSubscriptionCheckout(any(), any(), any(), any(), any()))
                .thenReturn(expectedUrl);

        given()
                .contentType(ContentType.JSON)
                .body("""
                    {"email":"%s","plan":"builder_quarterly","mode":"subscription","interval":"quarterly"}
                    """.formatted(TEST_EMAIL))
                .when()
                .post("/api/billing/checkout")
                .then()
                .statusCode(200)
                .body("checkoutUrl", equalTo(expectedUrl));
    }

    @Test
    @DisplayName("Stripe credit pack checkout returns checkoutUrl for Growth pack")
    void createCheckout_stripe_creditsGrowth_returnsCheckoutUrl() throws Exception {
        String expectedUrl = "https://checkout.stripe.com/c/pay/cs_test_credits_growth";
        when(stripeService.resolvePrice("credits_growth")).thenReturn("price_1TLxQBCNFjCgvrWw0KPetcMq");
        when(stripeService.createCreditPackCheckout(any(), any(), any(), anyInt()))
                .thenReturn(expectedUrl);

        given()
                .contentType(ContentType.JSON)
                .body("""
                    {"email":"%s","plan":"credits_growth","mode":"payment","creditAmount":32000}
                    """.formatted(TEST_EMAIL))
                .when()
                .post("/api/billing/checkout")
                .then()
                .statusCode(200)
                .body("checkoutUrl", equalTo(expectedUrl));
    }

    @Test
    @DisplayName("Checkout with missing plan field returns 400")
    void createCheckout_missingPlan_returns400() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                    {"email":"%s","mode":"subscription"}
                    """.formatted(TEST_EMAIL))
                .when()
                .post("/api/billing/checkout")
                .then()
                .statusCode(400)
                .body("error", notNullValue());
    }

    @Test
    @DisplayName("Checkout with unknown plan key returns 400 from resolvePrice")
    void createCheckout_unknownPlanKey_returns400() throws Exception {
        when(stripeService.resolvePrice("unknown_plan"))
                .thenThrow(new IllegalArgumentException("No Stripe price configured for key: unknown_plan"));

        given()
                .contentType(ContentType.JSON)
                .body("""
                    {"email":"%s","plan":"unknown_plan","mode":"subscription","interval":"monthly"}
                    """.formatted(TEST_EMAIL))
                .when()
                .post("/api/billing/checkout")
                .then()
                .statusCode(400)
                .body("error", notNullValue());
    }

    // =========================================================================
    // POST /api/billing/razorpay/order — Razorpay
    // =========================================================================

    @Test
    @DisplayName("Razorpay subscription order returns subscriptionId (not orderId) for plan")
    void createRazorpayOrder_subscription_returnsSubscriptionId() throws Exception {
        when(razorpayService.createOrder(eq("plan_SdDtQreYZOuDuZ"), any(), eq("subscription"), anyInt()))
                .thenReturn(new RazorpayOrderResponse(
                        null, "sub_test_builder_xyz", "rzp_test_key",
                        0L, "INR", "plan_SdDtQreYZOuDuZ",
                        TEST_TENANT_ID.toString(), "subscription", 0));

        given()
                .contentType(ContentType.JSON)
                .body("""
                    {"priceId":"plan_SdDtQreYZOuDuZ","mode":"subscription","plan":"builder","interval":"monthly"}
                    """)
                .when()
                .post("/api/billing/razorpay/order")
                .then()
                .statusCode(200)
                .body("subscriptionId", equalTo("sub_test_builder_xyz"))
                .body("orderId", equalTo(""))
                .body("currency", equalTo("INR"));
    }

    @Test
    @DisplayName("Razorpay credit pack order returns orderId (not subscriptionId)")
    void createRazorpayOrder_payment_creditsGrowth_returnsOrderId() throws Exception {
        when(razorpayService.createOrder(eq("credits_growth"), any(), eq("payment"), eq(32000)))
                .thenReturn(new RazorpayOrderResponse(
                        "order_test_growth_abc", null, "rzp_test_key",
                        209900L, "INR", "credits_growth",
                        TEST_TENANT_ID.toString(), "payment", 32000));

        given()
                .contentType(ContentType.JSON)
                .body("""
                    {"priceId":"credits_growth","mode":"payment","creditAmount":32000}
                    """)
                .when()
                .post("/api/billing/razorpay/order")
                .then()
                .statusCode(200)
                .body("orderId", equalTo("order_test_growth_abc"))
                .body("subscriptionId", equalTo(""))
                .body("amount", equalTo(209900))
                .body("currency", equalTo("INR"));
    }

    @Test
    @DisplayName("Razorpay order with missing priceId returns 400")
    void createRazorpayOrder_missingPriceId_returns400() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"mode\":\"payment\"}")
                .when()
                .post("/api/billing/razorpay/order")
                .then()
                .statusCode(400)
                .body("error", notNullValue());
    }

    // =========================================================================
    // POST /api/billing/razorpay/verify — Razorpay payment verification
    // =========================================================================

    @Test
    @DisplayName("Valid Razorpay payment signature activates subscription and grants credits")
    void verifyRazorpayPayment_validSignature_subscription_returnsSuccess() throws Exception {
        when(razorpayService.verifyPaymentSignature("order_abc", "pay_xyz", "valid_sig"))
                .thenReturn(true);
        when(subscriptionService.createOrUpdate(any(), any(), any(), any(), any()))
                .thenReturn(Uni.createFrom().item(new com.decisionmesh.billing.model.SubscriptionEntity()));
        when(creditLedgerService.resetMonthlyAllocation(any(), any()))
                .thenReturn(Uni.createFrom().voidItem());

        given()
                .contentType(ContentType.JSON)
                .body("""
                    {
                      "orderId":"order_abc",
                      "paymentId":"pay_xyz",
                      "signature":"valid_sig",
                      "mode":"subscription",
                      "plan":"builder"
                    }
                    """)
                .when()
                .post("/api/billing/razorpay/verify")
                .then()
                .statusCode(200)
                .body("success", equalTo(true))
                .body("paymentId", equalTo("pay_xyz"));
    }

    @Test
    @DisplayName("Valid Razorpay payment signature grants credits for credit pack purchase")
    void verifyRazorpayPayment_validSignature_creditPack_grantsCredits() throws Exception {
        when(razorpayService.verifyPaymentSignature("order_abc", "pay_xyz", "valid_sig"))
                .thenReturn(true);
        when(creditLedgerService.grantPurchasedCredits(any(), anyInt(), any()))
                .thenReturn(Uni.createFrom().voidItem());

        given()
                .contentType(ContentType.JSON)
                .body("""
                    {
                      "orderId":"order_abc",
                      "paymentId":"pay_xyz",
                      "signature":"valid_sig",
                      "mode":"payment",
                      "creditAmount":32000
                    }
                    """)
                .when()
                .post("/api/billing/razorpay/verify")
                .then()
                .statusCode(200)
                .body("success", equalTo(true));
    }

    @Test
    @DisplayName("Invalid Razorpay signature returns 400 — tampered payment rejected")
    void verifyRazorpayPayment_invalidSignature_returns400() {
        when(razorpayService.verifyPaymentSignature(any(), any(), any())).thenReturn(false);

        given()
                .contentType(ContentType.JSON)
                .body("""
                    {
                      "orderId":"order_abc",
                      "paymentId":"pay_xyz",
                      "signature":"tampered_sig",
                      "mode":"payment",
                      "creditAmount":32000
                    }
                    """)
                .when()
                .post("/api/billing/razorpay/verify")
                .then()
                .statusCode(400)
                .body("error", notNullValue());
    }

    @Test
    @DisplayName("Missing orderId/paymentId/signature in verify request returns 400")
    void verifyRazorpayPayment_missingRequiredFields_returns400() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"mode\":\"payment\"}")
                .when()
                .post("/api/billing/razorpay/verify")
                .then()
                .statusCode(400);
    }

    // =========================================================================
    // POST /api/billing/razorpay/webhook
    // =========================================================================

    @Test
    @DisplayName("Valid Razorpay webhook signature returns 200 received:true")
    void razorpayWebhook_validSignature_returns200() throws Exception {
        String payload   = "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_test\"}}}}";
        String signature = hmac(payload, TEST_WEBHOOK_SECRET);

        when(razorpayService.verifyWebhookSignature(payload, signature)).thenReturn(true);

        given()
                .contentType(ContentType.TEXT)
                .header("X-Razorpay-Signature", signature)
                .body(payload)
                .when()
                .post("/api/billing/razorpay/webhook")
                .then()
                .statusCode(200)
                .body("received", equalTo(true));
    }

    @Test
    @DisplayName("Invalid Razorpay webhook signature returns 400 — replay/tamper protection")
    void razorpayWebhook_invalidSignature_returns400() {
        String payload = "{\"event\":\"payment.captured\"}";
        when(razorpayService.verifyWebhookSignature(any(), any())).thenReturn(false);

        given()
                .contentType(ContentType.TEXT)
                .header("X-Razorpay-Signature", "invalid_signature")
                .body(payload)
                .when()
                .post("/api/billing/razorpay/webhook")
                .then()
                .statusCode(400)
                .body("error", notNullValue());
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private String hmac(String payload, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }
}
