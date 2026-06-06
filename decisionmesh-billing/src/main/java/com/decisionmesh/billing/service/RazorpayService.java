package com.decisionmesh.billing.service;


import io.quarkus.logging.Log;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import io.vertx.core.json.JsonObject;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * Razorpay payment integration for Indian customers.
 *
 * Flow:
 *   1. POST /api/billing/razorpay/order  → creates Razorpay order, returns orderId + key
 *   2. Frontend opens Razorpay checkout popup with orderId
 *   3. POST /api/billing/razorpay/verify → verifies signature, grants credits/subscription
 *
 * Docs: https://razorpay.com/docs/payments/payment-gateway/web-integration/standard/
 *
 * Setup:
 *   razorpay.key-id=rzp_test_xxxxxxxxxxxx
 *   razorpay.key-secret=xxxxxxxxxxxxxxxxxxxx
 *   razorpay.webhook-secret=xxxxxxxxxxxxxxxxxxxx
 */
@ApplicationScoped
public class RazorpayService {

    private static final String RAZORPAY_API = "https://api.razorpay.com/v1";

    @ConfigProperty(name = "razorpay.key.id",        defaultValue = "")
    String keyId;

    @ConfigProperty(name = "razorpay.key.secret",    defaultValue = "")
    String keySecret;

    @ConfigProperty(name = "razorpay.webhook.secret", defaultValue = "")
    String webhookSecret;

    private HttpClient http;
    private String basicAuth;

    @PostConstruct
    void init() {
        this.http = HttpClient.newHttpClient();
        if (!keyId.isBlank() && !keySecret.isBlank()) {
            this.basicAuth = "Basic " + Base64.getEncoder()
                    .encodeToString((keyId + ":" + keySecret).getBytes(StandardCharsets.UTF_8));
            Log.infof("[Razorpay] Initialized with key: %s...", keyId.substring(0, Math.min(keyId.length(), 12)));
        } else {
            Log.warn("[Razorpay] No credentials configured — sandbox mode");
        }
        // Build price map: subscription plan IDs map to 0 (Razorpay plan carries amount),
        // credit pack keys map to INR amounts in paise from config.
        this.inrPrices = new java.util.HashMap<>();
        inrPrices.put(planBuilder,         0L);   // subscription amount is on the plan itself
        inrPrices.put(planPro,             0L);
        inrPrices.put("credits_starter",   amountCreditsStarter);
        inrPrices.put("credits_growth",    amountCreditsGrowth);
        inrPrices.put("credits_scale",     amountCreditsScale);
        inrPrices.put(planBuilderQuarterly,  0L);
        inrPrices.put(planBuilderHalfYearly, 0L);
        inrPrices.put(planBuilderYearly,     0L);
        inrPrices.put(planProQuarterly,      0L);
        inrPrices.put(planProHalfYearly,     0L);
        inrPrices.put(planProYearly,         0L);
    }

    // ── INR price map ─────────────────────────────────────────────────────────
    // Keys must match what the frontend sends as priceId:
    //   Subscriptions → real Razorpay plan IDs (plan_xxx) from application.properties
    //   Credit packs  → keys "credits_starter" | "credits_growth" | "credits_scale"
    //                   amounts read from razorpay.credits.*.amount in properties
    @ConfigProperty(name = "razorpay.builder.plan",          defaultValue = "") String planBuilder;
    @ConfigProperty(name = "razorpay.pro.plan",              defaultValue = "") String planPro;
    @ConfigProperty(name = "razorpay.credits.starter.amount",defaultValue = "84900")  long amountCreditsStarter;
    @ConfigProperty(name = "razorpay.credits.growth.amount", defaultValue = "209900") long amountCreditsGrowth;
    @ConfigProperty(name = "razorpay.credits.scale.amount",  defaultValue = "629900") long amountCreditsScale;
    @ConfigProperty(name = "razorpay.builder.quarterly.plan",  defaultValue = "") String planBuilderQuarterly;
    @ConfigProperty(name = "razorpay.builder.halfyearly.plan", defaultValue = "") String planBuilderHalfYearly;
    @ConfigProperty(name = "razorpay.builder.yearly.plan",     defaultValue = "") String planBuilderYearly;
    @ConfigProperty(name = "razorpay.pro.quarterly.plan",      defaultValue = "") String planProQuarterly;
    @ConfigProperty(name = "razorpay.pro.halfyearly.plan",     defaultValue = "") String planProHalfYearly;
    @ConfigProperty(name = "razorpay.pro.yearly.plan",         defaultValue = "") String planProYearly;

    private Map<String, Long> inrPrices;

    // ── Create Order or Subscription ─────────────────────────────────────────

    /**
     * Routes to the correct Razorpay API based on mode:
     *   subscription → POST /v1/subscriptions (plan_id based, returns subscriptionId)
     *   payment      → POST /v1/orders        (amount based, returns orderId)
     */
    public RazorpayOrderResponse createOrder(String priceId,
                                             String orgId,
                                             String mode,
                                             int creditAmount) throws Exception {
        if ("subscription".equalsIgnoreCase(mode)) {
            return createSubscription(priceId, orgId, mode, creditAmount);
        } else {
            return createOneTimeOrder(priceId, orgId, mode, creditAmount);
        }
    }

    /**
     * Creates a Razorpay Subscription for recurring plans (monthly/quarterly/half-yearly/yearly).
     * Uses POST /v1/subscriptions with the Razorpay plan_id.
     * Returns subscriptionId — frontend passes this as subscription_id to Razorpay checkout JS.
     */
    private RazorpayOrderResponse createSubscription(String planId,
                                                     String orgId,
                                                     String mode,
                                                     int creditAmount) throws Exception {
        if (keyId.isBlank()) {
            Log.warn("[Razorpay] No credentials — returning sandbox subscription");
            return new RazorpayOrderResponse(
                    null,
                    "sub_sandbox_" + UUID.randomUUID().toString().substring(0, 12),
                    keyId, 0L, "INR", planId, orgId, mode, creditAmount
            );
        }

        JsonObject body = new JsonObject()
                .put("plan_id",     planId)
                .put("total_count", 120)   // 10 years — effectively unlimited
                .put("quantity",    1)
                .put("notes", new JsonObject()
                        .put("orgId",  orgId)
                        .put("planId", planId)
                        .put("mode",   mode));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(RAZORPAY_API + "/subscriptions"))
                .header("Authorization", basicAuth)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());

        if (res.statusCode() != 200 && res.statusCode() != 201) {
            Log.errorf("[Razorpay] Subscription creation failed: %d %s", res.statusCode(), res.body());
            throw new RuntimeException("Razorpay subscription creation failed: " + res.body());
        }

        JsonObject sub = new JsonObject(res.body());
        String subscriptionId = sub.getString("id");
        Log.infof("[Razorpay] Subscription created: subscriptionId=%s planId=%s orgId=%s",
                subscriptionId, planId, orgId);

        return new RazorpayOrderResponse(
                null, subscriptionId, keyId, 0L, "INR", planId, orgId, mode, creditAmount
        );
    }

    /**
     * Creates a Razorpay Order for one-time credit pack purchases.
     * Uses POST /v1/orders with INR amount in paise.
     * Returns orderId — frontend passes this as order_id to Razorpay checkout JS.
     */
    private RazorpayOrderResponse createOneTimeOrder(String priceId,
                                                     String orgId,
                                                     String mode,
                                                     int creditAmount) throws Exception {
        Long amountPaise = inrPrices.getOrDefault(priceId, -1L);

        if (amountPaise < 0) {
            Log.errorf("[Razorpay] Unknown priceId: %s — not in inrPrices map", priceId);
            throw new RuntimeException("Unknown Razorpay priceId: " + priceId);
        }

        if (keyId.isBlank()) {
            Log.warn("[Razorpay] No credentials — returning sandbox order");
            return new RazorpayOrderResponse(
                    "order_sandbox_" + UUID.randomUUID().toString().substring(0, 12),
                    null, "rzp_test_sandbox",
                    amountPaise, "INR", priceId, orgId, mode, creditAmount
            );
        }

        String receiptId = "rcpt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        JsonObject body = new JsonObject()
                .put("amount",   amountPaise)
                .put("currency", "INR")
                .put("receipt",  receiptId)
                .put("notes", new JsonObject()
                        .put("orgId",        orgId)
                        .put("priceId",      priceId)
                        .put("mode",         mode)
                        .put("creditAmount", String.valueOf(creditAmount)));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(RAZORPAY_API + "/orders"))
                .header("Authorization", basicAuth)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());

        if (res.statusCode() != 200) {
            Log.errorf("[Razorpay] Order creation failed: %d %s", res.statusCode(), res.body());
            throw new RuntimeException("Razorpay order creation failed: " + res.body());
        }

        JsonObject order = new JsonObject(res.body());
        String orderId = order.getString("id");
        Log.infof("[Razorpay] Order created: orderId=%s orgId=%s amount=%d paise", orderId, orgId, amountPaise);

        return new RazorpayOrderResponse(
                orderId, null, keyId, amountPaise, "INR", priceId, orgId, mode, creditAmount
        );
    }

    // ── Verify Payment ────────────────────────────────────────────────────────

    /**
     * Verifies Razorpay payment signature after successful payment.
     * Called by frontend after Razorpay checkout completes.
     *
     * Signature = HMAC-SHA256(orderId + "|" + paymentId, keySecret)
     */
    public boolean verifyPaymentSignature(String orderId,
                                          String paymentId,
                                          String signature) {
        try {
            String payload = orderId + "|" + paymentId;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(keySecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String computed = HexFormat.of().formatHex(hash);
            boolean valid = computed.equals(signature);
            Log.infof("[Razorpay] Signature verify: orderId=%s valid=%b", orderId, valid);
            return valid;
        } catch (Exception e) {
            Log.errorf("[Razorpay] Signature verification error: %s", e.getMessage());
            return false;
        }
    }

    // ── Webhook Signature Verify ──────────────────────────────────────────────

    public boolean verifyWebhookSignature(String payload, String receivedSignature) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String computed = HexFormat.of().formatHex(hash);
            return computed.equals(receivedSignature);
        } catch (Exception e) {
            Log.errorf("[Razorpay] Webhook signature error: %s", e.getMessage());
            return false;
        }
    }

    // ── Response DTO ──────────────────────────────────────────────────────────

    public record RazorpayOrderResponse(
            String  orderId,          // set for credit pack payments (order_id in checkout JS)
            String  subscriptionId,   // set for subscriptions (subscription_id in checkout JS)
            String  keyId,
            Long    amount,
            String  currency,
            String  priceId,
            String  orgId,
            String  mode,
            int     creditAmount
    ) {}
}