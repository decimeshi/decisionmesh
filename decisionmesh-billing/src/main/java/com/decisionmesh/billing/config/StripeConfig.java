package com.decisionmesh.billing.config;

import com.stripe.Stripe;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class StripeConfig {

    @ConfigProperty(name = "stripe.api.key")
    String apiKey;

    @PostConstruct
    void init() {
        Stripe.apiKey = apiKey;
    }
}
