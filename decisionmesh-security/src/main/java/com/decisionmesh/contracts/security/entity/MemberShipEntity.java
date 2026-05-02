package com.decisionmesh.contracts.security.entity;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "membership", uniqueConstraints = {
        @UniqueConstraint(name = "uq_tenant_user_project", columnNames = {"tenant_id", "user_id", "project_id"})
})
public class MemberShipEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue // Let Postgres handle the UUID generation
    public UUID id;

    @Column(name = "tenant_id", nullable = false)
    public UUID tenantId;

    @Column(name = "user_id", nullable = false)
    public UUID userId;

    @Column(name = "project_id")
    public UUID projectId;

    @Column(nullable = false, length = 20)
    public String role = "VIEWER";

    @CreationTimestamp
    @Column(name = "joined_at", updatable = false)
    public Instant joinedAt;

    // This was the missing symbol causing your error
    @Column(name = "last_active_at")
    public Instant lastActiveAt;
}