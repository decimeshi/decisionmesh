package com.decisionmesh.contracts.security.entity;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "projects", indexes = @Index(name = "idx_projects_tenant", columnList = "tenant_id"))
public class ProjectEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue // Matches DEFAULT gen_random_uuid()
    public UUID id;

    @Column(name = "tenant_id", nullable = false)
    public UUID tenantId;

    @Column(nullable = false, length = 255)
    public String name;

    @Column(columnDefinition = "TEXT") // Matches TEXT type in DDL
    public String description;

    @Column(nullable = false, length = 50)
    public String environment = "Production";

    @Column(name = "is_default", nullable = false)
    public boolean isDefault = false;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    public Instant createdAt;
}