package com.decisionmesh.billing.model;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Immutable append-only credit ledger row.
 *
 * Positive amount = credit earned (REGISTRATION_GIFT, SUBSCRIPTION, PURCHASE, REFERRAL, REFUND)
 * Negative amount = credit consumed (INTENT_EXECUTION, RETRY)
 *
 * Current balance = SELECT SUM(amount) FROM credit_ledger WHERE org_id = ?
 */
@Entity
@Table(name = "credit_ledger",
        indexes = {
                @Index(name = "idx_credit_ledger_org_id",    columnList = "org_id"),
                @Index(name = "idx_credit_ledger_created_at", columnList = "created_at"),
        })
public class CreditLedgerEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue
    @Column(name = "id")
    public UUID id;

    @Column(name = "org_id", nullable = false)
    public UUID orgId;

    /** Positive = credit, Negative = debit */
    @Column(name = "amount", nullable = false)
    public int amount;

    /**
     * REGISTRATION_GIFT | SUBSCRIPTION | PURCHASE | REFERRAL
     * INTENT_EXECUTION  | RETRY        | REFUND   | ADMIN_ADJUSTMENT
     */
    @Column(name = "reason", nullable = false, length = 30)
    public String reason;

    /** intent_id for executions, stripe session_id for purchases — nullable */
    @Column(name = "reference_id", length = 255)
    public String referenceId;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
}
