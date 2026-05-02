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
        FREE,        // $0  — 500 credits one-time gift, no reset
        HOBBY,       // $0  — 500 credits/month, credit card required, max 200 users
        BUILDER,     // $19 — 2,000 credits/month  ← primary revenue driver
        PRO,         // $49 — 6,000 credits/month, 5 seats
        ENTERPRISE;  // custom — unlimited, BYOK, dedicated SLA

        /** Monthly credit allocation for this plan. */
        public int monthlyCredits() {
            return switch (this) {
                case FREE        -> 0;      // one-time gift only — no monthly reset
                case HOBBY       -> 500;
                case BUILDER     -> 2_000;
                case PRO         -> 6_000;
                case ENTERPRISE  -> Integer.MAX_VALUE;
            };
        }

        /** Parse from a Stripe price ID stored in session metadata. */
        public static Plan fromPriceId(String priceId) {
            if (priceId == null) return FREE;
            return switch (priceId) {
                case "price_hobby_monthly"   -> HOBBY;
                case "price_builder_monthly" -> BUILDER;
                case "price_pro_monthly"     -> PRO;
                default                      -> FREE;
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
