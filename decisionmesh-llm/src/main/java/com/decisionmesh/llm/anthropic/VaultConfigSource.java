package com.decisionmesh.llm.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import org.eclipse.microprofile.config.spi.ConfigSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * MicroProfile ConfigSource that reads ALL secrets from OpenBao
 * before Quarkus wires any beans.
 *
 * Secret paths and their keys:
 *
 *   secret/decisionmesh/db
 *     url, username, password  → mapped to quarkus.datasource.*
 *
 *   secret/decisionmesh/razorpay
 *     razorpay.key-id
 *     razorpay.key-secret
 *     razorpay.webhook-secret
 *
 *   secret/decisionmesh/stripe
 *     stripe.secret.key
 *     stripe.webhook.secret
 *
 *   secret/decisionmesh/llm
 *     llm.openai.api-key
 *     llm.anthropic.api-key
 *     llm.gemini.api-key
 *     llm.deepseek.api-key
 *
 * Ordinal 275 > application.properties (250) — Vault values win.
 * Environment variables (ordinal 300) still override Vault — safe for prod.
 *
 * Fallback: if OpenBao is unreachable, returns no properties and
 * application.properties values take over silently.
 */
public class VaultConfigSource implements ConfigSource {

    private static final String VAULT_ADDR  =
            System.getenv().getOrDefault("VAULT_ADDR",  "http://localhost:8200");
    private static final String VAULT_TOKEN =
            System.getenv().getOrDefault("VAULT_TOKEN", "dev-root-token");

    // Static accessor — lets adapters read secrets without CDI/classloader issues.
    // Populated once during ConfigSource initialization, before any bean is created.
    private static final Map<String, String> LOADED = new HashMap<>();

    private final Map<String, String> properties = new HashMap<>();
    private final ObjectMapper        mapper      = new ObjectMapper();

    public VaultConfigSource() {
        Log.infof("[VaultConfigSource] Initialising — VAULT_ADDR=%s", VAULT_ADDR);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();

        // ── DB credentials — special mapping ──────────────────────────────────
        loadDb(client);

        // ── Direct key paths — key in OpenBao == Quarkus property name ───────
        loadDirect(client, "decisionmesh/razorpay");
        loadDirect(client, "decisionmesh/stripe");
        loadDirect(client, "decisionmesh/llm");
        loadDirect(client, "decisionmesh/auth");
        loadDirect(client, "decisionmesh/email");

        // Copy into static map so adapters can access via getSecret()
        LOADED.putAll(properties);

        if (!properties.isEmpty()) {
            Log.infof("[VaultConfigSource] Loaded %d properties from OpenBao:", properties.size());
            properties.forEach((key, value) -> {
                // Mask sensitive values — show only first 4 chars + ****
                String masked = isSensitive(key)
                        ? (value.length() > 4 ? value.substring(0, 4) + "****" : "****")
                        : value;
                Log.infof("[VaultConfigSource]   %s = %s", key, masked);
            });
        } else {
            Log.errorf("[VaultConfigSource] No properties loaded from OpenBao at %s " +
                    "— falling back to application.properties", VAULT_ADDR);
        }
    }

    // ── DB — special handling: short keys mapped to quarkus.datasource.* ─────

    private void loadDb(HttpClient client) {
        try {
            JsonNode data = fetch(client, "decisionmesh/db");
            if (data == null) {
                Log.warnf("[VaultConfigSource] Path decisionmesh/db not found in OpenBao");
                return;
            }

            putIfPresent(data, "username", "quarkus.datasource.username");
            putIfPresent(data, "password", "quarkus.datasource.password");
            putIfPresent(data, "url",      "quarkus.datasource.jdbc.url");

            // Derive reactive URL from jdbc URL if not explicitly stored
            String reactiveUrl = data.path("reactive_url").asText(null);
            if (reactiveUrl == null) {
                String jdbcUrl = data.path("url").asText(null);
                if (jdbcUrl != null) {
                    reactiveUrl = jdbcUrl
                            .replace("jdbc:postgresql://", "postgresql://")
                            .replace("jdbc:postgresql:",   "postgresql:");
                }
            }
            if (reactiveUrl != null && !reactiveUrl.isBlank()) {
                properties.put("quarkus.datasource.reactive.url", reactiveUrl);
            }
            putIfPresent(data, "username", "quarkus.datasource.reactive.username");
            putIfPresent(data, "password", "quarkus.datasource.reactive.password");

            Log.infof("[VaultConfigSource] DB credentials loaded from decisionmesh/db");

        } catch (Exception e) {
            Log.errorf("[VaultConfigSource] Failed to load decisionmesh/db: %s", e.getMessage());
        }
    }

    // ── Direct — key in OpenBao is the exact Quarkus property name ───────────

    private void loadDirect(HttpClient client, String path) {
        try {
            JsonNode data = fetch(client, path);
            if (data == null) {
                Log.warnf("[VaultConfigSource] Path %s not found in OpenBao (404) — skipping", path);
                return;
            }

            int[] count = {0};
            data.fields().forEachRemaining(entry -> {
                String key   = entry.getKey();
                String value = entry.getValue().asText(null);
                if (value != null && !value.isBlank()) {
                    properties.put(key, value);
                    count[0]++;
                } else {
                    Log.warnf("[VaultConfigSource] Skipping blank value for key=%s in path=%s", key, path);
                }
            });

            Log.infof("[VaultConfigSource] Loaded %d keys from %s", count[0], path);

        } catch (Exception e) {
            Log.errorf("[VaultConfigSource] Failed to load %s: %s", path, e.getMessage());
        }
    }

    // ── HTTP helper ───────────────────────────────────────────────────────────

    private JsonNode fetch(HttpClient client, String path) throws Exception {
        String url = VAULT_ADDR + "/v1/secret/data/" + path;
        Log.debugf("[VaultConfigSource] Fetching: %s", url);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("X-Vault-Token", VAULT_TOKEN)
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();

        HttpResponse<String> response = client.send(
                request, HttpResponse.BodyHandlers.ofString()
        );

        if (response.statusCode() == 404) return null;

        if (response.statusCode() != 200) {
            Log.errorf("[VaultConfigSource] HTTP %d for path=%s body=%s",
                    response.statusCode(), path, response.body());
            return null;
        }

        JsonNode node = mapper.readTree(response.body()).path("data").path("data");
        if (node.isMissingNode()) {
            Log.warnf("[VaultConfigSource] Response for path=%s has no data.data node: %s",
                    path, response.body());
            return null;
        }

        return node;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void putIfPresent(JsonNode data, String vaultKey, String configKey) {
        String value = data.path(vaultKey).asText(null);
        if (value != null && !value.isBlank()) {
            properties.put(configKey, value);
        }
    }

    /** Keys whose values should be masked in logs */
    private boolean isSensitive(String key) {
        String lower = key.toLowerCase();
        return lower.contains("password") || lower.contains("secret")
                || lower.contains("api-key") || lower.contains("api.key")
                || lower.contains("token")   || lower.contains("key-id")
                || lower.contains("webhook");
    }

    /** Direct static access — use in LLM adapters to avoid classloader/CDI issues. */
    public static String getSecret(String key) {
        return LOADED.getOrDefault(key, "");
    }

    @Override public Map<String, String> getProperties()    { return properties; }
    @Override public Set<String>         getPropertyNames() { return properties.keySet(); }
    @Override public String              getValue(String k) { return properties.get(k); }
    @Override public String              getName()          { return "OpenBaoVaultConfigSource"; }
    @Override public int                 getOrdinal()       { return 275; }
}