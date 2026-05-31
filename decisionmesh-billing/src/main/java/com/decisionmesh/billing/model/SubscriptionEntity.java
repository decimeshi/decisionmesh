package com.decisionmesh.billing.model;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "subscription")
public class SubscriptionEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    public UUID orgId;
    public String stripeCustomerId;
    public String stripeSubscriptionId;

    @Enumerated(EnumType.STRING)
    public Plan plan;

    @Enumerated(EnumType.STRING)
    public Status status;

    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;

    public enum Plan {
        FREE,        // $0       — 100 credits one-time, ALL features unlocked
        BUILDER,     // $19/mo   — 15,000 credits/month  ← primary revenue driver
        PRO,         // $49/mo   — 60,000 credits/month, 5 seats, SSO/SAML
        ENTERPRISE;  // custom   — unlimited, BYOK, BYOM, dedicated SLA

        /**
         * Monthly credit allocation for recurring plans.
         * FREE returns 0 here because its grant is one-time only —
         * use initialCredits() for the signup gift.
         */
        public int monthlyCredits() {
            return switch (this) {
                case FREE        -> 0;              // one-time only — no monthly reset
                case BUILDER     -> 15_000;
                case PRO         -> 60_000;
                case ENTERPRISE  -> Integer.MAX_VALUE;
            };
        }

        /**
         * One-time credit grant issued at signup / plan activation.
         * Only FREE gets a one-time grant. Paid plans get monthly credits.
         */
        public int initialCredits() {
            return switch (this) {
                case FREE        -> 100;   // full product access, 100 credits to evaluate
                case BUILDER     -> 0;     // covered by monthlyCredits()
                case PRO         -> 0;
                case ENTERPRISE  -> 0;
            };
        }

        /**
         * Parse from a Stripe price ID stored in session metadata.
         * Covers all billing intervals (monthly, quarterly, halfyearly, yearly).
         * HOBBY removed — no longer a supported plan.
         */
        public static Plan fromPriceId(String priceId) {
            if (priceId == null) return FREE;
            return switch (priceId) {
                // Builder — all intervals
                case "builder",
                     "builder_quarterly",
                     "builder_halfyearly",
                     "builder_yearly"     -> BUILDER;

                // Pro — all intervals
                case "pro",
                     "pro_quarterly",
                     "pro_halfyearly",
                     "pro_yearly"         -> PRO;

                // Enterprise — custom / contact sales
                case "enterprise"         -> ENTERPRISE;

                default                   -> FREE;
            };
        }
    }

    public enum Status {
        ACTIVE,
        INACTIVE,
        CANCELED,
        PAST_DUE
    }
}