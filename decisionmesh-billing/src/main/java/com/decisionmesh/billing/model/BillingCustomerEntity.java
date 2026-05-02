
package com.decisionmesh.billing.model;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "billing_customer")
public class BillingCustomerEntity extends PanacheEntityBase {

    @Id
    public UUID orgId;

    @Column(unique = true)
    public String stripeCustomerId;
}
