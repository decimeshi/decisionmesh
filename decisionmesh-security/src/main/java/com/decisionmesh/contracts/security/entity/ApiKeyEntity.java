package com.decisionmesh.contracts.security.entity;

import com.decisionmesh.contracts.security.converter.StringListJsonConverter;
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import io.smallrye.mutiny.Uni;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Persistent entity for the {@code api_keys} table.
 *
 * Fields and methods are derived from {@link com.decisionmesh.contracts.security.service.ApiKeyService}:
 * <ul>
 *   <li>{@code keyId}       — primary key (service calls {@code .findById(keyId)}, result has {@code .keyId})</li>
 *   <li>{@code organizationId} — set by the service alongside tenantId</li>
 *   <li>{@code active}      — boolean flag; service calls {@code apiKey.revoke("api")} which sets this false</li>
 *   <li>{@code usageCount}  — incremented by {@link #recordUsage()}</li>
 *   <li>{@code createdBy}   — String (service sets {@code createdByUserId.toString()})</li>
 * </ul>
 *
 * {@code scopes} uses {@link StringListJsonConverter} — NOT {@code @JdbcTypeCode(SqlTypes.JSON)} —
 * because Hibernate Reactive's {@code ReactiveJsonJdbcType} wraps every JSON value in
 * {@code new JsonObject(jsonString)} which cannot represent JSON arrays ([]).
 */
@Entity
@Table(
        name = "api_keys",
        indexes = {
                @Index(name = "idx_api_keys_tenant",      columnList = "tenant_id"),
                @Index(name = "idx_api_keys_key_hash",    columnList = "key_hash", unique = true),
                @Index(name = "idx_api_keys_tenant_active", columnList = "tenant_id, active"),
        }
)
public class ApiKeyEntity extends PanacheEntityBase {

    // ── Identity ──────────────────────────────────────────────────────────────

    /**
     * Primary key — named {@code keyId} to match the service's {@code ApiKeyResult.keyId}
     * and the {@code revokeKeyForTenant(UUID keyId, ...)} parameter.
     */
    @Id
    @UuidGenerator
    @Column(name = "key_id", updatable = false, nullable = false)
    public UUID keyId;

    @Column(name = "organization_id")
    public UUID organizationId;

    @Column(name = "tenant_id", nullable = false)
    public UUID tenantId;

    /** UUID of the user who created the key. Service stores it as both UUID and String. */
    @Column(name = "created_by_user_id", nullable = false)
    public UUID createdByUserId;

    /** String representation of createdByUserId. Set by service as {@code userId.toString()}. */
    @Column(name = "created_by", length = 255)
    public String createdBy;

    // ── Key material ──────────────────────────────────────────────────────────

    /** Human-readable label supplied at creation, e.g. "Production backend". */
    @Column(name = "name", nullable = false, length = 255)
    public String name;

    /**
     * First 20 characters of the raw key (e.g. {@code sk_live_a1b2c3d4ef}).
     * Safe to display — not sufficient for authentication.
     * Service: {@code fullKey.substring(0, Math.min(20, fullKey.length()))}.
     */
    @Column(name = "key_prefix", nullable = false, length = 32)
    public String keyPrefix;

    /**
     * Base64-encoded SHA-256 digest of the full raw key.
     * Used for authentication lookup — never returned by the API.
     * Service hashes with: {@code Base64.getEncoder().encodeToString(SHA256(key.getBytes(UTF_8)))}.
     */
    @Column(name = "key_hash", nullable = false, length = 128, unique = true)
    public String keyHash;

    // ── Scopes ────────────────────────────────────────────────────────────────

    /**
     * Permission scopes. Stored as JSONB array, e.g. {@code ["intents:write","intents:read"]}.
     * Default is {@code ["*"]} (full access) set by the service.
     *
     * Uses {@link StringListJsonConverter} — NOT {@code @JdbcTypeCode} — to avoid
     * Hibernate Reactive's inability to bind JSON arrays via ReactiveJsonJdbcType.
     */
    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "scopes", columnDefinition = "jsonb", nullable = false)
    public List<String> scopes = new ArrayList<>();

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Whether this key can authenticate requests.
     * Set to {@code false} by {@link #revoke(String)}.
     * Service checks {@code apiKey.active} via {@link #isValid()}.
     */
    @Column(name = "active", nullable = false)
    public boolean active = true;

    @Column(name = "expires_at")
    public OffsetDateTime expiresAt;

    /**
     * Set when the key is revoked. Null = never revoked.
     * The UI reads {@code k.revokedAt} to distinguish active from expired/revoked.
     */
    @Column(name = "revoked_at")
    public OffsetDateTime revokedAt;

    /** Reason passed to {@link #revoke(String)}, e.g. {@code "api"} or {@code "admin"}. */
    @Column(name = "revoked_by", length = 100)
    public String revokedBy;

    // ── Rate limiting / IP whitelist ──────────────────────────────────────────
    // These columns exist in the api_keys table. Hibernate includes every mapped
    // field in every INSERT — missing mappings cause "column does not exist" errors.

    /** Requests per minute limit. Null = unlimited. */
    @Column(name = "rate_limit")
    public Integer rateLimit;

    /**
     * CIDR-notation IP whitelist stored as JSONB array, e.g. {@code ["10.0.0.0/8"]}.
     * Null or empty = no IP restriction.
     * Uses StringListJsonConverter — NOT @JdbcTypeCode — same reason as scopes.
     */
    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "ip_whitelist", columnDefinition = "jsonb")
    public List<String> ipWhitelist = new ArrayList<>();

    // ── Usage tracking ────────────────────────────────────────────────────────

    /** Incremented by {@link #recordUsage()}. Shown in UI as "Used this week" stat. */
    @Column(name = "usage_count", nullable = false)
    public Long usageCount = 0L;

    /**
     * Updated by {@link #recordUsage()} on every successful authentication.
     * The UI reads {@code k.lastUsedAt} and computes "used this week".
     */
    @Column(name = "last_used_at")
    public OffsetDateTime lastUsedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    public OffsetDateTime createdAt;

    // ── Business methods — called by ApiKeyService ────────────────────────────

    /**
     * Returns true if this key can authenticate requests.
     * A key is valid when: active=true, not expired, not revoked.
     * Called by {@code ApiKeyService.validateAndGetKey()} after hash lookup.
     */
    public boolean isValid() {
        if (!active) return false;
        if (revokedAt != null) return false;
        if (expiresAt != null && OffsetDateTime.now().isAfter(expiresAt)) return false;
        return true;
    }

    /**
     * Records a successful use of this key.
     * Called by {@code ApiKeyService.validateAndGetKey()} after a successful lookup.
     * Updates {@link #lastUsedAt} and increments {@link #usageCount}.
     */
    public void recordUsage() {
        this.lastUsedAt = OffsetDateTime.now();
        this.usageCount = this.usageCount == null ? 1L : this.usageCount + 1;
    }

    /**
     * Revokes this key — disables authentication and records the revocation timestamp.
     * Called by {@code ApiKeyService.revokeKeyForTenant()} with source {@code "api"}.
     *
     * @param revokedBySource who/what triggered the revocation, e.g. {@code "api"}, {@code "admin"}
     */
    public void revoke(String revokedBySource) {
        this.active    = false;
        this.revokedAt = OffsetDateTime.now();
        this.revokedBy = revokedBySource;
    }

    // ── Lifecycle guard ───────────────────────────────────────────────────────

    @PrePersist
    void initDefaults() {
        if (scopes      == null) scopes      = new ArrayList<>();
        if (ipWhitelist == null) ipWhitelist = new ArrayList<>();
        if (usageCount  == null) usageCount  = 0L;
    }

    // ── Reactive finders ──────────────────────────────────────────────────────

    /**
     * Lookup by Base64-SHA256 hash — used for authentication.
     * Returns the entity regardless of active/expired status;
     * callers must check {@link #isValid()}.
     * Called by: {@code ApiKeyService.validateAndGetKey()}.
     */
    public static Uni<ApiKeyEntity> findByHash(String keyHash) {
        return find("keyHash = ?1", keyHash).firstResult();
    }

    /**
     * All keys for a tenant, ordered newest first.
     * Called by: {@code ApiKeyService.listKeys(tenantId, activeOnly=false)}.
     */
    public static Uni<List<ApiKeyEntity>> findByTenant(UUID tenantId) {
        return find("tenantId = ?1 order by createdAt desc", tenantId).list();
    }

    /**
     * Active (non-revoked, non-expired) keys for a tenant.
     * Called by: {@code ApiKeyService.listKeys(tenantId, activeOnly=true)}.
     */
    public static Uni<List<ApiKeyEntity>> findActiveTenantKeys(UUID tenantId) {
        return find("tenantId = ?1 and active = true and (expiresAt is null or expiresAt > ?2)",
                tenantId, OffsetDateTime.now()).list();
    }
}
