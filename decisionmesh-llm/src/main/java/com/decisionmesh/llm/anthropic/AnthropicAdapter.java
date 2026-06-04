package com.decisionmesh.llm.anthropic;

import com.decisionmesh.domain.execution.ExecutionRecord;
import com.decisionmesh.domain.intent.Intent;
import com.decisionmesh.domain.plan.PlanStep;
import com.decisionmesh.llm.CacheUsage;
import com.decisionmesh.llm.CacheableAdapter;
import com.decisionmesh.llm.LlmAdapter;
import com.decisionmesh.application.exception.LlmAdapterException;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Anthropic Claude adapter with prompt caching support.
 *
 * Prompt caching behaviour:
 *   - System prompt sent as content block with cache_control: {type: ephemeral}
 *   - Beta header "anthropic-beta: prompt-caching-2024-07-31" required
 *   - Cache TTL: 5 minutes — renewed on each hit
 *   - First call:  cache_creation_input_tokens at $1.25/1M (25% premium)
 *   - Subsequent:  cache_read_input_tokens     at $0.10/1M (90% cheaper)
 *
 * Model: claude-haiku-4-5-20251001 (claude-haiku-3 DEPRECATED April 19 2026)
 *
 * FIXES:
 *   1. Removed ExecutionRecord.builder() — no builder exists
 *      Uses ExecutionRecord.of() + withResponseText() + withCacheUsage()
 *   2. Removed step.getPlanVersion() — PlanStep has no planVersion field
 *      Passes null for planVersion (not needed at execution time)
 *   3. intent.getIntentType() returns String not enum — removed .name()
 *   4. step.getAdapterId() returns UUID — use toString(), guard null
 *   5. IntentConstraints is record — maxLatency() not getMaxLatencyMs()
 *                                     maxRetries() not getMaxRetries()
 */
// Disabled: superseded by com.decisionmesh.llm.anthropic.AnthropicLlmAdapter
// which uses HttpClient directly and correctly stores responseText.
@Alternative
@ApplicationScoped
public class AnthropicAdapter implements LlmAdapter, CacheableAdapter {

    private static final String PROVIDER          = "anthropic";
    private static final String ANTHROPIC_API_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final String CACHE_BETA_HEADER = "prompt-caching-2024-07-31";

    // Haiku 4.5 pricing per 1M tokens
    private static final double PRICE_INPUT_PER_M       = 1.00;
    private static final double PRICE_CACHE_WRITE_PER_M = 1.25;
    private static final double PRICE_CACHE_READ_PER_M  = 0.10;
    private static final double PRICE_OUTPUT_PER_M      = 5.00;

    @ConfigProperty(name = "llm.anthropic.api-key")
    String apiKey;

    @ConfigProperty(name = "llm.default.model", defaultValue = "claude-haiku-4-5-20251001")
    String model;

    @ConfigProperty(name = "llm.anthropic.max-tokens", defaultValue = "1024")
    int maxTokens;

    @ConfigProperty(name = "llm.cache.enabled", defaultValue = "true")
    boolean cacheEnabled;

    @Inject
    Client httpClient;

    // ── LlmAdapter ────────────────────────────────────────────────────────────

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public Uni<ExecutionRecord> execute(Intent intent, PlanStep step, int attemptNumber) {
        Instant startedAt = Instant.now();
        return Uni.createFrom().item(() -> buildRequestBody(intent, step))
                .flatMap(body -> callAnthropicApi(body, startedAt, intent, step, attemptNumber));
    }

    // ── CacheableAdapter ──────────────────────────────────────────────────────

    @Override
    public boolean isCacheEnabled() {
        return cacheEnabled;
    }

    @Override
    public String cacheProvider() {
        return PROVIDER;
    }

    // ── Request building ──────────────────────────────────────────────────────

    private Map<String, Object> buildRequestBody(Intent intent, PlanStep step) {
        String systemPrompt = buildSystemPrompt(intent, step);
        String userPrompt   = buildUserPrompt(intent, step);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model",      model);
        body.put("max_tokens", maxTokens);

        if (cacheEnabled) {
            // System prompt as content block with cache_control
            // Identical across all intents of same type → cached 5 minutes
            Map<String, Object> systemBlock = new LinkedHashMap<>();
            systemBlock.put("type", "text");
            systemBlock.put("text", systemPrompt);
            systemBlock.put("cache_control", Map.of("type", "ephemeral"));
            body.put("system", List.of(systemBlock));
            Log.debugf("[Anthropic] Cache enabled — system prompt as content block. model=%s", model);
        } else {
            body.put("system", systemPrompt);
        }

        body.put("messages", List.of(
                Map.of("role", "user", "content", userPrompt)
        ));

        return body;
    }

    // ── HTTP call ─────────────────────────────────────────────────────────────

    private Uni<ExecutionRecord> callAnthropicApi(Map<String, Object> body,
                                                  Instant startedAt,
                                                  Intent intent,
                                                  PlanStep step,
                                                  int attemptNumber) {
        return Uni.createFrom().item(() -> {
            try {
                var requestBuilder = httpClient
                        .target(ANTHROPIC_API_URL)
                        .request(MediaType.APPLICATION_JSON)
                        .header("x-api-key",         apiKey)
                        .header("anthropic-version", ANTHROPIC_VERSION);

                if (cacheEnabled) {
                    requestBuilder = requestBuilder
                            .header("anthropic-beta", CACHE_BETA_HEADER);
                }

                Response response = requestBuilder.post(Entity.json(body));
                return parseResponse(response, startedAt, intent, step, attemptNumber);

            } catch (Exception ex) {
                long latencyMs = java.time.Duration.between(startedAt, Instant.now()).toMillis();
                throw new LlmAdapterException("ADAPTER_ERROR", ex.getMessage(), PROVIDER, model, attemptNumber, latencyMs);
            }
        });
    }

    // ── Response parsing ──────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private ExecutionRecord parseResponse(Response response,
                                          Instant startedAt,
                                          Intent intent,
                                          PlanStep step,
                                          int attemptNumber) {
        long latencyMs = java.time.Duration.between(startedAt, Instant.now()).toMillis();
        int  status    = response.getStatus();

        if (status == 429) {
            throw new LlmAdapterException("RATE_LIMITED",
                    "Anthropic rate limit hit", PROVIDER, model, attemptNumber, latencyMs);
        }
        if (status == 408 || status == 524) {
            throw new LlmAdapterException("TIMEOUT",
                    "Anthropic timeout: status=" + status, PROVIDER, model, attemptNumber, latencyMs);
        }
        if (status != 200) {
            String errorBody = response.readEntity(String.class);
            throw new LlmAdapterException("ADAPTER_ERROR",
                    "Anthropic error: status=" + status + " body=" + errorBody, PROVIDER, model, attemptNumber, latencyMs);
        }

        Map<String, Object> responseBody = response.readEntity(Map.class);

        String     responseText = extractContent(responseBody);
        CacheUsage cacheUsage   = parseCacheUsage(responseBody);
        BigDecimal cost         = calculateCost(cacheUsage);

        // ── Log cache outcome ─────────────────────────────────────────────────
        if (cacheEnabled) {
            if (cacheUsage.isCacheHit()) {
                BigDecimal savings = cacheUsage.savingsUsd(PRICE_INPUT_PER_M);
                Log.infof("[Anthropic] CACHE HIT  — intent=%s cacheReadTokens=%d savings=$%.6f",
                        intent.getId(), cacheUsage.cacheReadTokens(), savings);
            } else if (cacheUsage.isCacheWrite()) {
                Log.infof("[Anthropic] CACHE WRITE — intent=%s cacheWriteTokens=%d (next call cheaper)",
                        intent.getId(), cacheUsage.cacheCreationTokens());
            } else {
                Log.debugf("[Anthropic] No cache activity — intent=%s inputTokens=%d",
                        intent.getId(), cacheUsage.inputTokens());
            }
        }

        // ── Build ExecutionRecord ─────────────────────────────────────────────
        // FIX 1: No builder — use of() + withResponseText() + withCacheUsage()
        // FIX 2: step.getAdapterId() is UUID — convert to String, guard null
        // FIX 3: planVersion = null — PlanStep has no getPlanVersion()
        UUID adapterId = step.getAdapterId();
        String adapterIdStr = adapterId != null ? adapterId.toString() : PROVIDER;

        return ExecutionRecord.of(
                        intent.getId(),
                        attemptNumber,
                        adapterIdStr,
                        latencyMs,
                        cost,
                        null,    // failureType = null → isSuccess() returns true
                        null     // planVersion — PlanStep has no planVersion field
                )
                .withResponseText(responseText)
                .withCacheUsage(
                        cacheUsage.cacheReadTokens(),
                        cacheUsage.cacheCreationTokens()
                );
    }

    // ── Cache usage parsing ───────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private CacheUsage parseCacheUsage(Map<String, Object> responseBody) {
        Map<String, Object> usage = (Map<String, Object>) responseBody.get("usage");
        if (usage == null) return CacheUsage.EMPTY;

        return new CacheUsage(
                toInt(usage.get("input_tokens")),
                toInt(usage.get("cache_creation_input_tokens")),
                toInt(usage.get("cache_read_input_tokens")),
                toInt(usage.get("output_tokens"))
        );
    }

    // ── Cost calculation ──────────────────────────────────────────────────────

    private BigDecimal calculateCost(CacheUsage usage) {
        double inputCost  = (usage.inputTokens()         / 1_000_000.0) * PRICE_INPUT_PER_M;
        double writeCost  = (usage.cacheCreationTokens() / 1_000_000.0) * PRICE_CACHE_WRITE_PER_M;
        double readCost   = (usage.cacheReadTokens()     / 1_000_000.0) * PRICE_CACHE_READ_PER_M;
        double outputCost = (usage.outputTokens()        / 1_000_000.0) * PRICE_OUTPUT_PER_M;
        return BigDecimal.valueOf(inputCost + writeCost + readCost + outputCost)
                .setScale(8, java.math.RoundingMode.HALF_UP);
    }

    // ── Prompt builders ───────────────────────────────────────────────────────

    /**
     * System prompt — IDENTICAL across all intents of same type → gets cached.
     * Keep STATIC. Per-intent data goes in buildUserPrompt() — NOT cached.
     *
     * FIX: getIntentType() returns String not enum → removed .name() call
     */
    private String buildSystemPrompt(Intent intent, PlanStep step) {
        // FIX: getIntentType() is String — use directly, NOT .name()
        String intentType = intent.getIntentType() != null
                ? intent.getIntentType()          // ← String, no .name() needed
                : "GENERAL";
        String provider = step.getProvider() != null ? step.getProvider() : "standard";

        return """
                You are an AI governance control plane executing a structured intent.
                Your role is to process the intent objective faithfully, apply the configured \
                policy constraints, and return a structured JSON response.

                Rules:
                - Never hallucinate data not present in the intent payload
                - If the objective is unclear, state your interpretation explicitly
                - Return only valid JSON matching the requested output schema
                - Do not include any text outside the JSON response
                - Flag any policy concerns in the 'policy_notes' field

                Intent type: %s
                Tenant context: %s
                """.formatted(intentType, provider);
    }

    /**
     * User prompt — per-intent data. NOT cached.
     *
     * FIX: IntentConstraints is a Java record:
     *   maxLatency() not getMaxLatencyMs()
     *   maxRetries() not getMaxRetries()
     */
    private String buildUserPrompt(Intent intent, PlanStep step) {
        // ── Extract objective description and optional userMessage ─────────────
        // The intent payload may contain:
        //   objective.description  — what the AI should do (system-level instruction)
        //   objective.userMessage  — the actual user input to process (e.g. a question,
        //                           a transaction to analyse, a document to summarise)
        //
        // If userMessage is present, it is appended as the primary content so the
        // LLM receives both the task instruction AND the data to act on.
        // Without userMessage, the LLM only sees the meta-instruction and responds
        // with "please provide the data" — which is the bug this fixes.

        String description = "No objective specified";
        String userMessage = null;

        if (intent.getObjective() != null) {
            Object obj = intent.getObjective();
            if (obj instanceof java.util.Map<?, ?> map) {
                Object desc = map.get("description");
                if (desc != null && !desc.toString().isBlank()) {
                    description = desc.toString();
                }
                Object um = map.get("userMessage");
                if (um != null && !um.toString().isBlank()) {
                    userMessage = um.toString();
                }
            } else {
                // Fallback: use toString() if Objective is a typed class
                description = obj.toString();
            }
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("Execute the following intent:\n\n");
        prompt.append("Task: ").append(description).append("\n\n");

        if (userMessage != null) {
            prompt.append("Input to process:\n").append(userMessage).append("\n\n");
        }

        prompt.append("Constraints:\n");
        prompt.append("  Budget ceiling: ").append(
                intent.getBudget() != null ? intent.getBudget().getCeilingUsd() : "unlimited"
        ).append(" USD\n");
        prompt.append("  Max latency: ").append(
                intent.getConstraints() != null ? intent.getConstraints().maxLatency() : "5000"
        ).append(" ms\n");
        prompt.append("  Max retries: ").append(
                intent.getConstraints() != null ? intent.getConstraints().maxRetries() : "3"
        ).append("\n\n");
        prompt.append("Respond with valid JSON only.");

        return prompt.toString();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private String extractContent(Map<String, Object> responseBody) {
        try {
            List<Map<String, Object>> content =
                    (List<Map<String, Object>>) responseBody.get("content");
            if (content == null || content.isEmpty()) return "";
            return content.stream()
                    .filter(c -> "text".equals(c.get("type")))
                    .map(c -> (String) c.get("text"))
                    .findFirst()
                    .orElse("");
        } catch (Exception ex) {
            Log.warnf("Failed to extract content from Anthropic response: %s", ex.getMessage());
            return "";
        }
    }

    private int toInt(Object value) {
        if (value == null) return 0;
        if (value instanceof Integer i) return i;
        if (value instanceof Number  n) return n.intValue();
        try { return Integer.parseInt(value.toString()); } catch (Exception e) { return 0; }
    }
}