package com.decisionmesh.billing.service;

import com.decisionmesh.billing.model.BillingCustomerEntity;
import com.decisionmesh.billing.repository.BillingCustomerRepository;
import com.stripe.Stripe;
import com.stripe.model.Customer;
import com.stripe.param.CustomerCreateParams;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.UUID;

@ApplicationScoped
public class BillingCustomerService {

    @Inject
    BillingCustomerRepository repository;

    @ConfigProperty(name = "stripe.secret.key", defaultValue = "")
    String stripeSecretKey;

    // ── Read ──────────────────────────────────────────────────────────────────

    /** Returns the orgId for a given Stripe customer — used by webhook handler. */
    public Uni<UUID> getOrgId(String stripeCustomerId) {
        return repository.findByStripeCustomerId(stripeCustomerId)
                .onItem().ifNull().failWith(() ->
                        new RuntimeException("Customer not found: " + stripeCustomerId))
                .map(e -> e.orgId);
    }

    /** Returns the Stripe customer ID for a given org — used by checkout. */
    public Uni<String> getStripeCustomerId(UUID orgId) {
        return repository.findByOrgId(orgId)
                .map(e -> e != null ? e.stripeCustomerId : null)
                .onItem().ifNull().failWith(() ->
                        new RuntimeException("No Stripe customer for orgId: " + orgId));
    }

    // ── Create or get ─────────────────────────────────────────────────────────

    /**
     * Ensures a Stripe customer exists for this org.
     * Called on first checkout — idempotent.
     */
    @WithTransaction
    public Uni<String> getOrCreateStripeCustomerId(UUID orgId, String email) {
        return repository.findByOrgId(orgId)
                .flatMap(existing -> {
                    if (existing != null) {
                        return Uni.createFrom().item(existing.stripeCustomerId);
                    }
                    // Create in Stripe then persist locally
                    try {
                        if (!stripeSecretKey.isBlank()) Stripe.apiKey = stripeSecretKey;
                        CustomerCreateParams params = CustomerCreateParams.builder()
                                .setEmail(email)
                                .putMetadata("orgId", orgId.toString())
                                .build();
                        Customer customer = Customer.create(params);
                        String stripeId = customer.getId();

                        BillingCustomerEntity entity = new BillingCustomerEntity();
                        entity.orgId            = orgId;
                        entity.stripeCustomerId = stripeId;

                        return repository.persist(entity)
                                .map(e -> stripeId);
                    } catch (Exception e) {
                        Log.warnf("[Billing] Stripe customer creation failed: %s", e.getMessage());
                        // Return a placeholder so checkout can still proceed
                        return Uni.createFrom().item("cus_placeholder_" + orgId);
                    }
                });
    }
}
