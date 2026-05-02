package com.decisionmesh.bootstrap.dto;

import com.decisionmesh.contracts.security.entity.ApiKeyEntity;
import com.decisionmesh.contracts.security.service.ApiKeyService.ApiKeyResult;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Response DTOs for {@code GET /api/api-keys} and {@code POST /api/api-keys}.
 *
 * Two shapes are used deliberately:
 * <ul>
 *   <li>{@link ApiKeyResponse}        — safe list shape; no raw key, no hash</li>
 *   <li>{@link ApiKeyCreatedDto} — one-time create response; includes plaintext key</li>
 * </ul>
 *
 * <h3>Field mapping: entity → UI</h3>
 * <pre>
 * ApiKeyEntity.keyId      → ApiKeyDto.id       (UI reads k.id)
 * ApiKeyEntity.active     → ApiKeyDto.revokedAt (UI checks !k.revokedAt for active)
 * ApiKeyEntity.usageCount → not exposed (UI uses lastUsedAt for "used this week")
 * ApiKeyEntity.createdBy  → not exposed (internal)
 * </pre>
 *
 * <h3>ApiKeyResult → ApiKeyCreatedDto</h3>
 * {@code ApiKeyService.ApiKeyResult} has {@code keyId} and no {@code name}/{@code scopes}.
 * {@link ApiKeyCreatedDto} bridges this by accepting name/scopes from the request
 * and keyId/key/keyPrefix/timestamps from the result.
 * The {@code RevealedKey} component in {@code ApiKeys.jsx} reads:
 * {@code apiKey.id, apiKey.key, apiKey.name, apiKey.scopes, apiKey.keyPrefix}.
 */
public class ApiKeyResponse {

    // ── Fields read by KeyTable in ApiKeys.jsx ────────────────────────────────
    // k.id, k.name, k.keyPrefix, k.scopes, k.createdAt, k.expiresAt, k.revokedAt, k.lastUsedAt

    /** Maps from {@code ApiKeyEntity.keyId}. UI reads {@code k.id}. */
    public UUID id;

    public String name;
    public String keyPrefix;
    public List<String> scopes;
    public OffsetDateTime createdAt;

    /** Null = never expires. UI: {@code k.expiresAt ? formatDate(...) : "Never"} */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public OffsetDateTime expiresAt;

    /**
     * Null = active. Non-null = revoked.
     * Mapped from {@code entity.revokedAt} (set by {@code entity.revoke(...)}).
     * UI logic: {@code active = !k.revokedAt && (!k.expiresAt || expiresAt > now)}.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public OffsetDateTime revokedAt;

    /**
     * Null = never used. Mapped from {@code entity.lastUsedAt}.
     * UI: "Used this week" stat = {@code lastUsedAt && (now - lastUsedAt) < 7 days}.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public OffsetDateTime lastUsedAt;

    /**
     * Maps an {@link ApiKeyEntity} to the safe list DTO.
     * Never includes the key hash or plaintext key.
     */
    public static ApiKeyResponse from(ApiKeyEntity e) {
        ApiKeyResponse dto   = new ApiKeyResponse();
        dto.id          = e.keyId;       // entity PK is keyId, UI reads id
        dto.name        = e.name;
        dto.keyPrefix   = e.keyPrefix;
        dto.scopes      = e.scopes;
        dto.createdAt   = e.createdAt;
        dto.expiresAt   = e.expiresAt;
        dto.revokedAt   = e.revokedAt;   // null when active, set by entity.revoke()
        dto.lastUsedAt  = e.lastUsedAt;
        return dto;
    }

    // ── Create response ───────────────────────────────────────────────────────

    /**
     * Returned once by {@code POST /api/api-keys}.
     * Bridges {@link ApiKeyResult} (which has keyId/key/keyPrefix/timestamps)
     * with the request fields (name/scopes) that the service doesn't carry.
     *
     * Fields read by {@code RevealedKey} component in {@code ApiKeys.jsx}:
     * <ul>
     *   <li>{@code apiKey.id}       — for state keying</li>
     *   <li>{@code apiKey.key}      — full plaintext, shown in the copy banner</li>
     *   <li>{@code apiKey.keyPrefix}— shown as e.g. {@code sk_live_a1b2c3•••}</li>
     *   <li>{@code apiKey.name}     — shown in summary line below the key</li>
     *   <li>{@code apiKey.scopes}   — shown in summary line below the key</li>
     * </ul>
     */
    public static class ApiKeyCreatedDto {
        /** Maps from {@code ApiKeyResult.keyId}. UI reads {@code apiKey.id}. */
        public UUID id;
        /** Plaintext key — shown once in the RevealedKey banner, never retrievable again. */
        public String key;
        public String keyPrefix;
        /** Copied from the create request — not in ApiKeyResult. */
        public String name;
        /** Copied from the create request — not in ApiKeyResult. */
        public List<String> scopes;
        public OffsetDateTime createdAt;
        @JsonInclude(JsonInclude.Include.ALWAYS)
        public OffsetDateTime expiresAt;

        /**
         * Builds the one-time create response from the service result
         * plus the request fields the service doesn't echo back.
         *
         * @param result   returned by {@code ApiKeyService.createApiKey()}
         * @param name     from the create request body
         * @param scopes   from the create request body
         */
        public static ApiKeyCreatedDto from(ApiKeyResult result, String name, List<String> scopes) {
            ApiKeyCreatedDto dto = new ApiKeyCreatedDto();
            dto.id        = result.keyId;
            dto.key       = result.key;
            dto.keyPrefix = result.keyPrefix;
            dto.name      = name;
            dto.scopes    = scopes;
            dto.createdAt = result.createdAt;
            dto.expiresAt = result.expiresAt;
            return dto;
        }
    }

}
