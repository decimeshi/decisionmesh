package com.decisionmesh.persistence.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "org_branding")
public class OrgBrandingEntity extends PanacheEntityBase {

    @Id
    @Column(name = "tenant_id")
    @JsonProperty("tenantId")
    public UUID tenantId;

    @JsonProperty("orgName")          // ← was missing, may serialize as org_name
    @Column(name = "org_name", length = 255)
    public String orgName;

    @JsonProperty("primaryColor")     // ← already correct ✅
    @Column(name = "primary_color", length = 7)
    public String primaryColor;

    @JsonProperty("logoUrl")          // ← was missing, may serialize as logo_url
    @Column(name = "logo_url")
    public String logoUrl;

    @JsonProperty("favicon")
    @Column(name = "favicon")
    public String favicon;

    @JsonProperty("updatedAt")
    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;
}