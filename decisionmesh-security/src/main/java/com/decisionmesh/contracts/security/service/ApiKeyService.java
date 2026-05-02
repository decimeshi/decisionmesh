package com.decisionmesh.contracts.security.service;

import com.decisionmesh.contracts.security.entity.ApiKeyEntity;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Service for API key generation, validation, and management.
 * Fully reactive — all methods return Uni<T>.
 */
@ApplicationScoped
public class ApiKeyService {

    private static final Logger LOG = Logger.getLogger(ApiKeyService.class);

    private static final String KEY_PREFIX_LIVE = "sk_live_";
    private static final String KEY_PREFIX_TEST = "sk_test_";
    private static final int KEY_LENGTH = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    // ── Key generation ────────────────────────────────────────────────────────

    /**
     * Create a new API key.
     * Returns the full plaintext key in ApiKeyResult — only returned once, never stored.
     */
    public Uni<ApiKeyResult> createApiKey(
            UUID organizationId,
            UUID tenantId,
            UUID createdByUserId,
            String name,
            boolean isTest,
            Integer expiresInDays) {

        byte[] keyBytes = new byte[KEY_LENGTH];
        SECURE_RANDOM.nextBytes(keyBytes);
        String randomPart = Base64.getUrlEncoder().withoutPadding().encodeToString(keyBytes);

        String prefix  = isTest ? KEY_PREFIX_TEST : KEY_PREFIX_LIVE;
        String fullKey = prefix + randomPart;
        String keyHash = hashKey(fullKey);

        ApiKeyEntity apiKey        = new ApiKeyEntity();
        apiKey.organizationId      = organizationId;
        apiKey.tenantId            = tenantId;
        apiKey.createdByUserId     = createdByUserId;
        apiKey.keyHash             = keyHash;
        apiKey.keyPrefix           = fullKey.substring(0, Math.min(20, fullKey.length()));
        apiKey.name                = name;
        apiKey.scopes              = List.of("*");   // full access by default
        apiKey.active              = true;
        apiKey.createdBy           = createdByUserId.toString();
        apiKey.usageCount          = 0L;

        if (expiresInDays != null && expiresInDays > 0) {
            apiKey.expiresAt = OffsetDateTime.now().plus(expiresInDays, ChronoUnit.DAYS);
        }

        return Panache.withTransaction(() ->
                apiKey.<ApiKeyEntity>persist()
                        .map(saved -> {
                            LOG.infof("Created API key for tenant %s: %s... (expires: %s)",
                                    tenantId, saved.keyPrefix, saved.expiresAt);
                            return new ApiKeyResult(
                                    saved.keyId, fullKey,
                                    saved.keyPrefix, saved.createdAt, saved.expiresAt);
                        })
        );
    }

    // ── Key validation ────────────────────────────────────────────────────────

    /**
     * Validate an API key and return the entity if valid.
     * Returns Uni<null> if the key is missing, malformed, or invalid.
     */
    public Uni<ApiKeyEntity> validateAndGetKey(String providedKey) {
        if (providedKey == null || providedKey.isBlank()) {
            return Uni.createFrom().nullItem();
        }

        if (!providedKey.startsWith(KEY_PREFIX_LIVE) &&
                !providedKey.startsWith(KEY_PREFIX_TEST)) {
            LOG.debugf("Invalid key format: %s",
                    providedKey.substring(0, Math.min(10, providedKey.length())));
            return Uni.createFrom().nullItem();
        }

        String keyHash = hashKey(providedKey);

        return Panache.withTransaction(() ->
                ApiKeyEntity.findByHash(keyHash)
                        .flatMap(apiKey -> {
                            if (apiKey == null) {
                                LOG.debugf("API key not found: %s...",
                                        providedKey.substring(0, Math.min(15, providedKey.length())));
                                return Uni.createFrom().nullItem();
                            }
                            if (!apiKey.isValid()) {
                                LOG.warnf("Invalid/expired API key: %s (tenant: %s)",
                                        apiKey.keyPrefix, apiKey.tenantId);
                                return Uni.createFrom().nullItem();
                            }
                            apiKey.recordUsage();
                            return apiKey.<ApiKeyEntity>persist()
                                    .map(saved -> {
                                        LOG.debugf("Valid API key: %s (tenant: %s, usage: %d)",
                                                saved.keyPrefix, saved.tenantId, saved.usageCount);
                                        return saved;
                                    });
                        })
        );
    }

    // ── Key management ────────────────────────────────────────────────────────

    /**
     * Revoke an API key scoped to a tenant.
     * Returns true if revoked, false if not found or wrong tenant.
     */
    public Uni<Boolean> revokeKeyForTenant(UUID keyId, UUID tenantId) {
        return Panache.withTransaction(() ->
                ApiKeyEntity.<ApiKeyEntity>findById(keyId)
                        .flatMap(apiKey -> {
                            if (apiKey == null) {
                                return Uni.createFrom().item(false);
                            }
                            if (!apiKey.tenantId.equals(tenantId)) {
                                LOG.warnf("Cross-tenant revocation attempt: key=%s tenant=%s",
                                        keyId, tenantId);
                                return Uni.createFrom().item(false);
                            }
                            apiKey.revoke("api");
                            return apiKey.<ApiKeyEntity>persist()
                                    .map(saved -> {
                                        LOG.infof("Revoked API key %s for tenant %s",
                                                saved.keyPrefix, tenantId);
                                        return true;
                                    });
                        })
        );
    }

    /**
     * List API keys for a tenant.
     */
    public Uni<List<ApiKeyEntity>> listKeys(UUID tenantId, boolean activeOnly) {
        return Panache.withSession(() ->
                activeOnly
                        ? ApiKeyEntity.findActiveTenantKeys(tenantId)
                        : ApiKeyEntity.findByTenant(tenantId)
        );
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private String hashKey(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(key.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    // ── Result DTO ────────────────────────────────────────────────────────────

    public static class ApiKeyResult {
        public UUID keyId;
        public String key;          // plaintext — returned ONCE, never stored
        public String keyPrefix;
        public OffsetDateTime createdAt;
        public OffsetDateTime expiresAt;

        public ApiKeyResult(UUID keyId, String key, String keyPrefix,
                            OffsetDateTime createdAt, OffsetDateTime expiresAt) {
            this.keyId     = keyId;
            this.key       = key;
            this.keyPrefix = keyPrefix;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
        }
    }
}