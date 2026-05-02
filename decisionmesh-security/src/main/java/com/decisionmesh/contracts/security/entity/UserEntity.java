package com.decisionmesh.contracts.security.entity;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import io.smallrye.mutiny.Uni;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "users",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_users_email", columnNames = "email")
        }
)
public class UserEntity extends PanacheEntityBase {

    /**
     * userId = Keycloak subject (sub claim) parsed as UUID.
     *
     * We no longer auto-generate this — it is set explicitly from the
     * Keycloak sub before persist, so @UuidGenerator is intentionally removed.
     * This means one ID everywhere: JWT sub == DB user_id == userId in all tables.
     */
    @Id
    @Column(name = "user_id", updatable = false, nullable = false)
    public UUID userId;

    /**
     * tenantId is null for brand-new users who haven't completed onboarding.
     * It is set in OnboardingService.setupTenant() and written to Keycloak
     * attributes so it appears in the JWT on next token refresh.
     */
    @Column(name = "tenant_id")
    public UUID tenantId;

    @Column(name = "email", unique = true, length = 255)
    public String email;

    @Column(name = "name", length = 255)
    public String name;

    @Column(name = "is_active", nullable = false)
    public boolean isActive = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    public OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;

    // ── Reactive finders ──────────────────────────────────────────────────────



    /**
     * Finds a user by email address.
     */
    public static Uni<UserEntity> findByEmail(String email) {
        return find("email", email).firstResult();
    }

    // UserEntity.java — add/update this method
    public static Uni<UserEntity> findByKeycloakSub(String sub) {
        // Try UUID format first (Keycloak), fall back to name-based UUID (Zitadel)
        UUID userId;
        try {
            userId = UUID.fromString(sub);
        } catch (IllegalArgumentException e) {
            userId = UUID.nameUUIDFromBytes(sub.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return find("userId", userId).firstResult();
    }
}