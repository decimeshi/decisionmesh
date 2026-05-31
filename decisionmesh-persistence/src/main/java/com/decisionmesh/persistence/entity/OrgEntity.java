package com.decisionmesh.persistence.entity;


import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "organizations")
public class OrgEntity extends PanacheEntityBase {

    @Id
    @Column(name = "id")
    public UUID id;           // same as tenant_id — org is identified by tenant

    @Column(name = "name", nullable = false, length = 255)
    public String name;

    @Column(name = "plan", nullable = false, length = 20)
    public String plan;       // FREE | BUILDER | PRO | ENTERPRISE

    @Column(name = "created_at")
    public Instant createdAt;
}