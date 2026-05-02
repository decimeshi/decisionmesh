package com.decisionmesh.contracts.security.entity;

import com.decisionmesh.contracts.security.converter.StringListJsonConverter;
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(
        name = "user_organizations",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_user_organizations_user_org",
                        columnNames = {"user_id", "organization_id"}
                )
        }
)
public class UserOrganizationEntity extends PanacheEntityBase {

    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    public UUID id;

    @Column(name = "user_id", nullable = false)
    public UUID userId;

    @Column(name = "organization_id", nullable = false)
    public UUID organizationId;

    @Column(name = "tenant_id", nullable = false)
    public UUID tenantId;

    @Column(name = "role", nullable = false, length = 100)
    public String role;

    /**
     * Permissions stored as JSONB.
     * Uses AttributeConverter instead of @JdbcTypeCode(SqlTypes.JSON) because
     * Hibernate Reactive's ReactiveJsonJdbcType uses Vert.x JsonObject which
     * cannot bind JSON arrays — it throws DecodeException on any List field.
     */
    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "permissions", nullable = false, columnDefinition = "jsonb")
    public List<String> permissions = new ArrayList<>();

    @Column(name = "is_active", nullable = false)
    public boolean isActive = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    public OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;
}