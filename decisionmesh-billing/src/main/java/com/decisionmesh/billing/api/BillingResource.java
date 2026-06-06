package com.decisionmesh.billing.api;

import com.decisionmesh.billing.service.RazorpayService;
import com.decisionmesh.billing.service.RazorpayService.RazorpayOrderResponse;
import com.decisionmesh.billing.service.StripeService;
import com.decisionmesh.billing.service.CreditLedgerService;
import com.decisionmesh.billing.service.SubscriptionService;
import com.decisionmesh.billing.model.SubscriptionEntity.Plan;
import com.decisionmesh.billing.model.SubscriptionEntity.Status;
import com.decisionmesh.contracts.security.context.TenantContext;
import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Billing API — supports both Stripe (international) and Razorpay (India).
 *
 * Payment gateway routing:
 *   Frontend sends { gateway: "stripe" | "razorpay" } in request.
 *   Default is "stripe" for international users.
 *   Indian users should use "razorpay" (INR prices, UPI/cards/netbanking).
 *
 * Stripe flow:
 *   POST /api/billing/checkout → { checkoutUrl } → redirect
 *
 * Razorpay flow:
 *   POST /api/billing/razorpay/order  → { orderId, keyId, amount, currency }
 *   Frontend opens Razorpay popup → payment success
 *   POST /api/billing/razorpay/verify → { success: true }
 */
@Path("/api/billing")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class BillingResource {

    @Inject StripeService      stripeService;
    @Inject RazorpayService    razorpayService;
    @Inject TenantContext      tenantContext;
    @Inject JsonWebToken       jwt;
    @Inject CreditLedgerService creditLedgerService;
    @Inject SubscriptionService subscriptionService;

    // =========================================================================
    // STRIPE — International payments (USD)
    // =========================================================================

    /**
     * POST /api/billing/checkout
     * Creates a Stripe Checkout session and returns the hosted URL.
     */
    @POST
    @Path("/checkout")
    public Response createCheckout(CheckoutRequest request) {
        if (request.plan == null || request.plan.isBlank())
            return Response.status(400).entity(Map.of("error", "plan is required")).build();

        if (tenantContext.getTenantId() == null)
            return Response.status(403).entity(Map.of("error", "Tenant not resolved")).build();

        String orgId = tenantContext.getTenantId().toString();

        String customerEmail = request.email;
        if (customerEmail == null || customerEmail.isBlank())
            return Response.status(400).entity(Map.of("error", "email is required")).build();

        String interval = request.interval != null ? request.interval : "monthly";

        try {
            String url;
            if ("payment".equalsIgnoreCase(request.mode)) {
                // Credit pack — resolve price_xxx from pack key e.g. "starter"
                String priceId = stripeService.resolvePrice(request.plan);
                int credits = request.creditAmount != null ? request.creditAmount : 0;
                url = stripeService.createCreditPackCheckout(customerEmail, priceId, orgId, credits);
            } else {
                // Subscription — resolve interval-aware key e.g. "builder_quarterly"
                String priceKey = request.plan; // frontend already sends "builder_quarterly" etc.
                String priceId  = stripeService.resolvePrice(priceKey);
                url = stripeService.createSubscriptionCheckout(
                        customerEmail, priceId, orgId, request.plan, interval);
            }

            Log.infof("[Billing/Stripe] Checkout: orgId=%s mode=%s plan=%s interval=%s",
                    orgId, request.mode, request.plan, interval);
            return Response.ok(Map.of("checkoutUrl", url)).build();

        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(Map.of("error", e.getMessage())).build();
        } catch (Exception e) {
            Log.errorf(e, "[Billing/Stripe] Checkout failed: orgId=%s", orgId);
            return Response.status(500)
                    .entity(Map.of("error", "Checkout failed", "detail", e.getMessage()))
                    .build();
        }
    }

    // =========================================================================
    // RAZORPAY — India payments (INR)
    // =========================================================================

    /**
     * POST /api/billing/razorpay/order
     *
     * Creates a Razorpay order. Frontend uses the returned orderId + keyId
     * to open the Razorpay checkout popup.
     *
     * Request:  { priceId, mode, creditAmount? }
     * Response: { orderId, keyId, amount, currency, priceId, orgId }
     */
    @POST
    @Path("/razorpay/order")
    public Response createRazorpayOrder(CheckoutRequest request) {
        if (request.priceId == null || request.priceId.isBlank())
            return Response.status(400).entity(Map.of("error", "priceId is required")).build();

        if (tenantContext.getTenantId() == null)
            return Response.status(403).entity(Map.of("error", "Tenant not resolved")).build();

        String orgId = tenantContext.getTenantId().toString();
        int credits  = request.creditAmount != null ? request.creditAmount : 0;
        String mode  = request.mode != null ? request.mode : "subscription";
        String customerEmail = request.email; // forwarded from frontend ID token profile

        try {
            RazorpayOrderResponse order = razorpayService.createOrder(
                    request.priceId, orgId, mode, credits);

            Log.infof("[Billing/Razorpay] Order: orgId=%s priceId=%s amount=%d",
                    orgId, request.priceId, order.amount());

            return Response.ok(Map.of(
                    "orderId",        order.orderId() != null ? order.orderId() : "",
                    "subscriptionId", order.subscriptionId() != null ? order.subscriptionId() : "",
                    "keyId",          order.keyId(),
                    "amount",         order.amount(),
                    "currency",       order.currency(),
                    "priceId",        order.priceId(),
                    "orgId",          order.orgId(),
                    "mode",           order.mode(),
                    "creditAmount",   order.creditAmount()
            )).build();

        } catch (Exception e) {
            Log.errorf(e, "[Billing/Razorpay] Order failed: orgId=%s", orgId);
            return Response.status(500)
                    .entity(Map.of("error", "Razorpay order creation failed", "detail", e.getMessage()))
                    .build();
        }
    }

    /**
     * POST /api/billing/razorpay/verify
     *
     * Verifies Razorpay payment signature after frontend checkout completes.
     * Called by frontend with { orderId, paymentId, signature }.
     *
     * On success: grants credits / activates subscription in DB.
     */
    @POST
    @Path("/razorpay/verify")
    public Response verifyRazorpayPayment(RazorpayVerifyRequest request) {
        if (request.orderId == null || request.paymentId == null || request.signature == null)
            return Response.status(400).entity(Map.of("error", "orderId, paymentId, signature required")).build();

        if (tenantContext.getTenantId() == null)
            return Response.status(403).entity(Map.of("error", "Tenant not resolved")).build();

        String orgId = tenantContext.getTenantId().toString();

        boolean valid = razorpayService.verifyPaymentSignature(
                request.orderId, request.paymentId, request.signature);

        if (!valid) {
            Log.warnf("[Billing/Razorpay] Invalid signature: orgId=%s orderId=%s", orgId, request.orderId);
            return Response.status(400)
                    .entity(Map.of("error", "Payment verification failed — invalid signature"))
                    .build();
        }

        // ── Grant credits / activate subscription ─────────────────────────────
        try {
            UUID orgUUID = UUID.fromString(orgId);
            if ("payment".equalsIgnoreCase(request.mode)) {
                // One-time credit pack purchase
                int credits = request.creditAmount != null ? request.creditAmount : 0;
                creditLedgerService.grantPurchasedCredits(orgUUID, credits, request.paymentId)
                        .await().indefinitely();
                Log.infof("[Billing/Razorpay] Credits granted: orgId=%s credits=%d paymentId=%s",
                        orgId, credits, request.paymentId);
            } else {
                // Subscription activation
                Plan plan = Plan.valueOf(
                        (request.plan != null ? request.plan : "FREE").toUpperCase());
                subscriptionService.createOrUpdate(
                        orgUUID,
                        (String) null,         // no Stripe customerId for Razorpay
                        request.orderId,       // use orderId as subscription reference
                        plan,
                        Status.ACTIVE
                ).await().indefinitely();
                creditLedgerService.resetMonthlyAllocation(orgUUID, plan)
                        .await().indefinitely();
                Log.infof("[Billing/Razorpay] Subscription activated: orgId=%s plan=%s paymentId=%s",
                        orgId, plan, request.paymentId);
            }
        } catch (Exception e) {
            Log.errorf(e, "[Billing/Razorpay] Post-payment grant failed: orgId=%s", orgId);
            // Payment succeeded — don't return error, log for manual recovery
        }

        Log.infof("[Billing/Razorpay] Payment verified: orgId=%s orderId=%s paymentId=%s",
                orgId, request.orderId, request.paymentId);

        return Response.ok(Map.of(
                "success",   true,
                "paymentId", request.paymentId,
                "message",   "Payment successful — credits have been credited"
        )).build();
    }

    /**
     * POST /api/billing/razorpay/webhook
     * Handles Razorpay webhook events (payment.captured, etc.)
     */
    @POST
    @Path("/razorpay/webhook")
    @Consumes(MediaType.TEXT_PLAIN)
    public Response razorpayWebhook(String payload,
                                    @HeaderParam("X-Razorpay-Signature") String signature) {
        if (!razorpayService.verifyWebhookSignature(payload, signature)) {
            Log.warn("[Billing/Razorpay] Webhook signature invalid");
            return Response.status(400).entity(Map.of("error", "Invalid signature")).build();
        }

        Log.infof("[Billing/Razorpay] Webhook received: %s", payload.substring(0, Math.min(200, payload.length())));
        // TODO: parse event type and process:
        // payment.captured  → grant credits
        // subscription.activated → activate plan
        // subscription.cancelled → downgrade plan

        return Response.ok(Map.of("received", true)).build();
    }

    // =========================================================================
    // PLAN & USAGE INFO (gateway-agnostic)
    // =========================================================================

    @GET
    @Path("/plans")
    public Response getPlans() {
        // Returns both USD (Stripe) and INR (Razorpay) prices
        return Response.ok(List.of(
                Map.of("id", "free",    "name", "Free",    "usdPrice", 0,  "inrPrice", 0,      "interval", "month",
                        "credits", 100,  "features", List.of("100 credits (one-time)", "All adapters", "Full audit log", "Policy builder", "Decision replay", "Budget enforcement")),
                Map.of("id", "builder", "name", "Builder", "usdPrice", 19, "inrPrice", 1599,   "interval", "month",
                        "stripePriceId", "price_builder_monthly", "razorpayPriceId", "price_builder_monthly",
                        "credits", 15000, "features", List.of("15,000 credits/month", "All adapters", "Priority support")),
                Map.of("id", "pro",     "name", "Pro",     "usdPrice", 49, "inrPrice", 4099,   "interval", "month",
                        "stripePriceId", "price_pro_monthly", "razorpayPriceId", "price_pro_monthly",
                        "credits", 60000, "features", List.of("60,000 credits/month", "5 team seats", "SSO"))
        )).build();
    }

    @GET
    @Path("/credit-packs")
    public Response getCreditPacks() {
        return Response.ok(List.of(
                Map.of("id", "starter", "name", "Starter", "usdPrice", 10, "inrPrice", 849,
                        "credits", 12000, "stripePriceId", "price_credits_starter",
                        "razorpayPriceId", "price_credits_starter"),
                Map.of("id", "growth",  "name", "Growth",  "usdPrice", 25, "inrPrice", 2099,
                        "credits", 32000, "stripePriceId", "price_credits_growth",
                        "razorpayPriceId", "price_credits_growth", "popular", true),
                Map.of("id", "scale",   "name", "Scale",   "usdPrice", 75, "inrPrice", 6299,
                        "credits", 100000, "stripePriceId", "price_credits_scale",
                        "razorpayPriceId", "price_credits_scale")
        )).build();
    }

    @GET
    @Path("/subscription")
    public Response getSubscription() {
        if (tenantContext.getTenantId() == null)
            return Response.status(403).entity(Map.of("error", "Tenant not resolved")).build();
        return Response.ok(Map.of(
                "plan",          "free",
                "status",        "active",
                "currentPeriod", java.time.LocalDate.now().withDayOfMonth(1).toString(),
                "nextBilling",   java.time.LocalDate.now().plusMonths(1).withDayOfMonth(1).toString(),
                "seats",         1,
                "features",      List.of("intents", "analytics", "api_keys")
        )).build();
    }

    @GET
    @Path("/usage")
    public Response getUsage() {
        if (tenantContext.getTenantId() == null)
            return Response.status(403).entity(Map.of("error", "Tenant not resolved")).build();
        return Response.ok(Map.of(
                "intentsUsed",   0, "intentsLimit",  100,
                "creditsUsed",   0, "creditsLimit",  500,
                "apiCallsUsed",  0, "apiCallsLimit", 1000,
                "periodStart",   java.time.LocalDate.now().withDayOfMonth(1).toString(),
                "periodEnd",     java.time.LocalDate.now().plusMonths(1).withDayOfMonth(1).minusDays(1).toString()
        )).build();
    }

    @GET
    @Path("/debug-token")
    public Response debugToken() {
        return Response.ok(jwt.getClaimNames()
                        .stream()
                        .collect(java.util.stream.Collectors.toMap(k -> k, k -> jwt.getClaim(k).toString())))
                .build();
    }

    // =========================================================================
    // DTOs
    // =========================================================================

    public static class CheckoutRequest {
        public String  priceId;
        public String  mode;          // "subscription" | "payment"
        public String  plan;
        public String  interval;      // "monthly" | "quarterly" | "halfyearly" | "yearly"
        public Integer creditAmount;
        public String  successUrl;
        public String  cancelUrl;
        public String  email;
    }

    public static class RazorpayVerifyRequest {
        public String orderId;
        public String paymentId;
        public String signature;
        public String priceId;
        public String mode;
        public String plan;          // "builder" | "pro"
        public Integer creditAmount;
    }
}