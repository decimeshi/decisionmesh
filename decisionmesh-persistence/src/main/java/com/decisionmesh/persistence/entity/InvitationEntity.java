package com.decisionmesh.persistence.entity;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import io.smallrye.mutiny.Uni;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "invitations",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_invitations_token", columnNames = "token")
        }
)
public class InvitationEntity extends PanacheEntityBase {

    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    public UUID id;

    @Column(name = "tenant_id", nullable = false)
    public UUID tenantId;

    @Column(name = "project_id")
    public UUID projectId;

    @Column(name = "email", nullable = false, length = 255)
    public String email;

    @Column(name = "role", nullable = false, length = 20)
    public String role = "VIEWER";

    @Column(name = "status", nullable = false, length = 20)
    public String status = "PENDING";

    @Column(name = "token", nullable = false, unique = true, length = 64)
    public String token;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    public OffsetDateTime createdAt;

    @Column(name = "expires_at")
    public OffsetDateTime expiresAt;

    // ── Reactive finders ─────────────────────────────────────────

    /**
     * Find invitation by token
     */
    public static Uni<InvitationEntity> findByToken(String token) {
        return find("token", token).firstResult();
    }

    /**
     * List invitations for a tenant
     */
    public static Uni<java.util.List<InvitationEntity>> findByTenant(UUID tenantId) {
        return find("tenantId", tenantId).list();
    }
}