/*
package com.decisionmesh.billing.webhook;

import com.decisionmesh.billing.model.SubscriptionEntity.Plan;
import com.decisionmesh.billing.model.SubscriptionEntity.Status;
import com.decisionmesh.billing.service.BillingCustomerService;
import com.decisionmesh.billing.service.CreditLedgerService;
import com.decisionmesh.billing.service.SubscriptionService;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.Invoice;
import com.stripe.model.Subscription;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.UUID;

*/
/**
 * Handles all Stripe webhook events.
 *
 * Registered events (Stripe Dashboard → Webhooks):
 *   checkout.session.completed      — activate subscription OR grant credit pack
 *   invoice.payment_succeeded       — reset monthly credits on renewal
 *   invoice.payment_failed          — mark subscription PAST_DUE
 *   customer.subscription.updated   — plan upgrade / downgrade
 *   customer.subscription.deleted   — cancel → downgrade to FREE
 *//*

@Path("/webhook/stripe")
public class StripeWebhookResource {

    @Inject SubscriptionService    subscriptionService;
    @Inject BillingCustomerService billingCustomerService;
    @Inject CreditLedgerService    creditLedgerService;

    @ConfigProperty(name = "stripe.webhook.secret",defaultValue = "")
    String webhookSecret;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<Response> handleWebhook(
            String payload,
            @HeaderParam("Stripe-Signature") String sigHeader) {

        // ── Verify signature ──────────────────────────────────────────────────
        Event event;
        try {
            event = Webhook.constructEvent(payload, sigHeader, webhookSecret);
        } catch (SignatureVerificationException e) {
            Log.warnf("[Stripe Webhook] Invalid signature: %s", e.getMessage());
            return Uni.createFrom().item(Response.status(400).entity("Invalid signature").build());
        }

        Log.infof("[Stripe Webhook] Received: %s", event.getType());

        return switch (event.getType()) {
            case "checkout.session.completed"    -> onCheckoutCompleted(event);
            case "invoice.payment_succeeded"     -> onInvoicePaymentSucceeded(event);
            case "invoice.payment_failed"        -> onInvoicePaymentFailed(event);
            case "customer.subscription.updated" -> onSubscriptionUpdated(event);
            case "customer.subscription.deleted" -> onSubscriptionDeleted(event);
            default -> {
                Log.debugf("[Stripe Webhook] Unhandled event type: %s", event.getType());
                yield Uni.createFrom().item(Response.ok().build());
            }
        };
    }

    // ── checkout.session.completed ────────────────────────────────────────────
    // Two modes:
    //   "subscription"  → activate plan + grant initial credits
    //   "credit_pack"   → grant purchased credits, no plan change

    private Uni<Response> onCheckoutCompleted(Event event) {
        Session session = (Session) event.getDataObjectDeserializer()
                .getObject().orElse(null);

        if (session == null) {
            Log.warn("[Stripe Webhook] checkout.session.completed — null session");
            return Uni.createFrom().item(Response.status(400).build());
        }

        String mode     = session.getMetadata().getOrDefault("mode", "subscription");
        String orgIdStr = session.getMetadata().get("orgId");

        if (orgIdStr == null) {
            Log.warn("[Stripe Webhook] checkout.session.completed — missing orgId in metadata");
            return Uni.createFrom().item(Response.status(400).build());
        }

        UUID orgId = UUID.fromString(orgIdStr);

        if ("credit_pack".equals(mode)) {
            // One-time credit pack purchase
            int credits = Integer.parseInt(
                    session.getMetadata().getOrDefault("creditAmount", "0"));
            return creditLedgerService
                    .grantPurchasedCredits(orgId, credits, session.getId())
                    .replaceWith(Response.ok().build());
        }

        // Subscription activation
        String customerId      = session.getCustomer();
        String subscriptionId  = session.getSubscription();
        String planStr         = session.getMetadata().getOrDefault("plan", "FREE");
        Plan   plan            = Plan.valueOf(planStr);

        return subscriptionService
                .createOrUpdate(orgId, customerId, subscriptionId, plan, Status.ACTIVE)
                .flatMap(sub -> creditLedgerService.resetMonthlyAllocation(orgId, plan))
                .replaceWith(Response.ok().build());
    }

    // ── invoice.payment_succeeded ─────────────────────────────────────────────
    // Monthly renewal — reset credit allocation for the new billing period

    private Uni<Response> onInvoicePaymentSucceeded(Event event) {
        Invoice invoice = (Invoice) event.getDataObjectDeserializer()
                .getObject().orElse(null);

        if (invoice == null) return ok();

        String customerId = invoice.getCustomer();

        return billingCustomerService.getOrgId(customerId)
                .flatMap(orgId ->
                        subscriptionService.getByOrg(orgId)
                                .flatMap(sub -> {
                                    Log.infof("[Stripe Webhook] Invoice paid: orgId=%s plan=%s", orgId, sub.plan);
                                    // Mark active in case it was PAST_DUE
                                    sub.status = Status.ACTIVE;
                                    return creditLedgerService.resetMonthlyAllocation(orgId, sub.plan);
                                })
                )
                .replaceWith(Response.ok().build())
                .onFailure().recoverWithItem(ex -> {
                    Log.warnf("[Stripe Webhook] invoice.payment_succeeded handling failed: %s", ex.getMessage());
                    return Response.ok().build();  // always 200 to Stripe
                });
    }

    // ── invoice.payment_failed ────────────────────────────────────────────────
    // Mark subscription PAST_DUE — do not revoke access immediately
    // (Stripe retries before deleting the subscription)

    private Uni<Response> onInvoicePaymentFailed(Event event) {
        Invoice invoice = (Invoice) event.getDataObjectDeserializer()
                .getObject().orElse(null);

        if (invoice == null) return ok();

        String customerId = invoice.getCustomer();

        return billingCustomerService.getOrgId(customerId)
                .flatMap(orgId ->
                        subscriptionService.getByOrg(orgId)
                                .flatMap(sub -> {
                                    Log.warnf("[Stripe Webhook] Payment failed: orgId=%s", orgId);
                                    return subscriptionService.updateStatus(orgId, Status.PAST_DUE);
                                })
                )
                .replaceWith(Response.ok().build())
                .onFailure().recoverWithItem(ex -> {
                    Log.warnf("[Stripe Webhook] invoice.payment_failed handling failed: %s", ex.getMessage());
                    return Response.ok().build();
                });
    }

    // ── customer.subscription.updated ────────────────────────────────────────
    // Plan upgrade or downgrade — update stored plan, adjust credits

    private Uni<Response> onSubscriptionUpdated(Event event) {
        Subscription subscription = (Subscription) event.getDataObjectDeserializer()
                .getObject().orElse(null);

        if (subscription == null) return ok();

        String customerId     = subscription.getCustomer();
        String subscriptionId = subscription.getId();
        String priceId        = subscription.getItems().getData().get(0).getPrice().getId();
        Plan   newPlan        = Plan.fromPriceId(priceId);
        Status newStatus      = "active".equals(subscription.getStatus())
                ? Status.ACTIVE : Status.INACTIVE;

        return billingCustomerService.getOrgId(customerId)
                .flatMap(orgId -> {
                    Log.infof("[Stripe Webhook] Subscription updated: orgId=%s newPlan=%s", orgId, newPlan);
                    return subscriptionService.createOrUpdate(
                            orgId, customerId, subscriptionId, newPlan, newStatus);
                })
                .replaceWith(Response.ok().build())
                .onFailure().recoverWithItem(ex -> {
                    Log.warnf("[Stripe Webhook] subscription.updated handling failed: %s", ex.getMessage());
                    return Response.ok().build();
                });
    }

    // ── customer.subscription.deleted ────────────────────────────────────────
    // Subscription cancelled — downgrade to FREE, keep existing credit balance

    private Uni<Response> onSubscriptionDeleted(Event event) {
        Subscription subscription = (Subscription) event.getDataObjectDeserializer()
                .getObject().orElse(null);

        if (subscription == null) return ok();

        String customerId = subscription.getCustomer();

        return billingCustomerService.getOrgId(customerId)
                .flatMap(orgId -> {
                    Log.infof("[Stripe Webhook] Subscription cancelled: orgId=%s → downgrade to FREE", orgId);
                    return subscriptionService.updateStatus(orgId, Status.CANCELED)
                            .flatMap(v -> subscriptionService.downgradePlan(orgId, Plan.FREE));
                })
                .replaceWith(Response.ok().build())
                .onFailure().recoverWithItem(ex -> {
                    Log.warnf("[Stripe Webhook] subscription.deleted handling failed: %s", ex.getMessage());
                    return Response.ok().build();
                });
    }

    private static Uni<Response> ok() {
        return Uni.createFrom().item(Response.ok().build());
    }
}
*/
