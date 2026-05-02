package com.decisionmesh.billing.service;

import com.stripe.Stripe;
import com.stripe.model.checkout.Session;
import com.stripe.param.checkout.SessionCreateParams;
import io.quarkus.logging.Log;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Map;

@ApplicationScoped
public class StripeService {

    @ConfigProperty(name = "stripe.secret.key")
    String stripeSecretKey;
    @ConfigProperty(name = "stripe.success.url", defaultValue = "http://localhost:3000/billing?success=1")
    String successUrl;
    @ConfigProperty(name = "stripe.cancel.url",  defaultValue = "http://localhost:3000/billing?cancelled=1")
    String cancelUrl;

    // ── Inject price IDs from config ──────────────────────────────────────────
    @ConfigProperty(name = "stripe.price.hobby")           String priceHobby;
    @ConfigProperty(name = "stripe.price.builder")         String priceBuilder;
    @ConfigProperty(name = "stripe.price.pro")             String pricePro;
    @ConfigProperty(name = "stripe.price.credits.starter") String priceCreditsStarter;
    @ConfigProperty(name = "stripe.price.credits.growth")  String priceCreditsGrowth;
    @ConfigProperty(name = "stripe.price.credits.scale")   String priceCreditsScale;

    @ConfigProperty(name = "stripe.price.builder.quarterly",  defaultValue = "") String priceBuilderQuarterly;
    @ConfigProperty(name = "stripe.price.builder.halfyearly", defaultValue = "") String priceBuilderHalfYearly;
    @ConfigProperty(name = "stripe.price.builder.yearly",     defaultValue = "") String priceBuilderYearly;
    @ConfigProperty(name = "stripe.price.pro.quarterly",      defaultValue = "") String priceProQuarterly;
    @ConfigProperty(name = "stripe.price.pro.halfyearly",     defaultValue = "") String priceProHalfYearly;
    @ConfigProperty(name = "stripe.price.pro.yearly",         defaultValue = "") String priceProYearly;

    private Map<String, String> priceMap;

    @PostConstruct
    void init() {
        Stripe.apiKey = stripeSecretKey;
        priceMap = Map.ofEntries(
                Map.entry("hobby",             priceHobby),
                Map.entry("builder",           priceBuilder),
                Map.entry("builder_quarterly", priceBuilderQuarterly),
                Map.entry("builder_halfyearly",priceBuilderHalfYearly),
                Map.entry("builder_yearly",    priceBuilderYearly),
                Map.entry("pro",               pricePro),
                Map.entry("pro_quarterly",     priceProQuarterly),
                Map.entry("pro_halfyearly",    priceProHalfYearly),
                Map.entry("pro_yearly",        priceProYearly),
                Map.entry("credits_starter",   priceCreditsStarter),
                Map.entry("credits_growth",    priceCreditsGrowth),
                Map.entry("credits_scale",     priceCreditsScale)
        );
        Log.info("[Stripe] API key initialised");
    }

    // ── Subscription checkout (recurring) ─────────────────────────────────────

    /**
     * Creates a Stripe Checkout Session for a subscription plan (HOBBY / BUILDER / PRO).
     * Returns the hosted checkout URL — frontend redirects to this.
     *
     * Metadata carries orgId and plan so the webhook can read them
     * without another Stripe API call.
     */
    public String createSubscriptionCheckout(String customerEmail,
                                             String priceId,
                                             String orgId,
                                             String plan,
                                             String interval) throws Exception {
        SessionCreateParams params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                .setCustomerEmail(customerEmail)
                .addLineItem(
                        SessionCreateParams.LineItem.builder()
                                .setPrice(priceId)
                                .setQuantity(1L)
                                .build()
                )
                .setSuccessUrl(successUrl + "&session_id={CHECKOUT_SESSION_ID}")
                .setCancelUrl(cancelUrl)
                .putMetadata("orgId",     orgId)
                .putMetadata("plan",      plan.toUpperCase())   // webhook uses Plan.valueOf()
                .putMetadata("interval",  interval != null ? interval : "monthly")
                .putMetadata("mode",      "subscription")
                .build();

        Session session = Session.create(params);
        Log.infof("[Stripe] Subscription checkout created: orgId=%s plan=%s interval=%s", orgId, plan, interval);
        return session.getUrl();
    }

    // ── Credit pack checkout (one-time payment) ───────────────────────────────

    /**
     * Creates a Stripe Checkout Session for a one-time credit pack purchase.
     * creditAmount is stored in metadata so the webhook knows how many credits
     * to grant without hardcoding pack sizes in the webhook handler.
     *
     * Credit packs:
     *   Starter  $10  → 12,000 credits  (price_credits_starter)
     *   Growth   $25  → 32,000 credits  (price_credits_growth)
     *   Scale    $75  → 100,000 credits (price_credits_scale)
     */
    public String createCreditPackCheckout(String customerEmail,
                                           String priceId,
                                           String orgId,
                                           int creditAmount) throws Exception {
        SessionCreateParams params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setCustomerEmail(customerEmail)
                .addLineItem(
                        SessionCreateParams.LineItem.builder()
                                .setPrice(priceId)
                                .setQuantity(1L)
                                .build()
                )
                .setSuccessUrl(successUrl + "&session_id={CHECKOUT_SESSION_ID}&credits=" + creditAmount)
                .setCancelUrl(cancelUrl)
                .putMetadata("orgId",         orgId)
                .putMetadata("mode",          "credit_pack")
                .putMetadata("creditAmount",  String.valueOf(creditAmount))
                .build();

        Session session = Session.create(params);
        Log.infof("[Stripe] Credit pack checkout created: orgId=%s credits=%d", orgId, creditAmount);
        return session.getUrl();
    }
    /** Resolves a plan/pack key to a real Stripe price ID. */
    public String resolvePrice(String key) {
        String id = priceMap.get(key);
        if (id == null || id.isBlank())
            throw new IllegalArgumentException("No Stripe price configured for key: " + key);
        return id;
    }
}