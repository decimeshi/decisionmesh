package com.decisionmesh.llm.openai;

import com.decisionmesh.domain.execution.ExecutionRecord;
import com.decisionmesh.domain.intent.Intent;
import com.decisionmesh.domain.plan.PlanStep;
import com.decisionmesh.llm.LlmAdapter;
import com.decisionmesh.application.exception.LlmAdapterException;
import com.decisionmesh.application.exception.LlmTimeoutException;
import com.decisionmesh.llm.prompt.PromptBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * OpenAI ChatCompletion adapter.
 *
 * Supported models:  gpt-4o, gpt-4o-mini, gpt-4-turbo, gpt-3.5-turbo
 * Wire format:       POST /v1/chat/completions
 * Auth:              Authorization: Bearer <api-key>
 * Cost model:        prompt_tokens × input_price + completion_tokens × output_price
 *                    (prices are configurable per model in application.properties)
 */
@ApplicationScoped
public class OpenAILlmAdapter implements LlmAdapter {

    private static final String PROVIDER         = "OPENAI";
    private static final String DEFAULT_ENDPOINT = "https://api.openai.com/v1/chat/completions";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── Config ────────────────────────────────────────────────────────────────

    @ConfigProperty(name = "llm.openai.api-key")
    String apiKey;

    @ConfigProperty(name = "llm.openai.default-model",      defaultValue = "gpt-4o")
    String defaultModel;

    @ConfigProperty(name = "llm.openai.default-timeout-ms", defaultValue = "30000")
    int defaultTimeoutMs;

    @ConfigProperty(name = "llm.openai.gpt-4o.input-cost-per-1k",       defaultValue = "0.005")
    BigDecimal gpt4oInputCostPer1k;

    @ConfigProperty(name = "llm.openai.gpt-4o.output-cost-per-1k",      defaultValue = "0.015")
    BigDecimal gpt4oOutputCostPer1k;

    @ConfigProperty(name = "llm.openai.gpt-4o-mini.input-cost-per-1k",  defaultValue = "0.00015")
    BigDecimal gpt4oMiniInputCostPer1k;

    @ConfigProperty(name = "llm.openai.gpt-4o-mini.output-cost-per-1k", defaultValue = "0.0006")
    BigDecimal gpt4oMiniOutputCostPer1k;

    private final HttpClient httpClient;

    @Inject
    public OpenAILlmAdapter() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    void onStart(@Observes StartupEvent ev) {
        Log.infof("[OpenAIAdapter] Starting — apiKey present=%s length=%d model=%s timeout=%dms",
                apiKey != null && !apiKey.isBlank(),
                apiKey != null ? apiKey.length() : 0,
                defaultModel,
                defaultTimeoutMs);
    }

    // ── LlmAdapter ────────────────────────────────────────────────────────────

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public Uni<ExecutionRecord> execute(Intent intent, PlanStep step, int attempt) {
        Instant startedAt = Instant.now();

        // configSnapshot is Map<String, Object> — use PlanStep convenience getters
        String model    = step.getConfigString("model",       defaultModel);
        int maxTokens   = step.getConfigInt("max_tokens",     1024);
        double temp     = step.getConfigDouble("temperature", 0.2);
        String endpoint = step.getConfigString("endpoint",    DEFAULT_ENDPOINT);
        long timeoutMs  = step.getConfigLong("timeout_ms",    (long) defaultTimeoutMs);

        ArrayNode messages = PromptBuilder.buildMessages(intent);

        ObjectNode requestBody = MAPPER.createObjectNode()
                .put("model",       model)
                .put("max_tokens",  maxTokens)
                .put("temperature", temp);
        requestBody.set("messages", messages);

        String requestBodyStr;
        try {
            requestBodyStr = MAPPER.writeValueAsString(requestBody);
        } catch (Exception e) {
            return Uni.createFrom().failure(
                    new LlmAdapterException("ADAPTER_ERROR",
                            "Failed to serialize OpenAI request: " + e.getMessage(),
                            PROVIDER, model, attempt, 0L));
        }

        Log.debugf("OpenAI request: model=%s, intent=%s, attempt=%d, endpoint=%s",
                model, intent.getId(), attempt, endpoint);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type",  "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBodyStr))
                .build();

        CompletableFuture<ExecutionRecord> future =
                httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                        .thenApply(response ->
                                parseResponse(response, intent, step, attempt, model, startedAt))
                        .exceptionally(ex -> { throw mapException(ex, model, attempt, startedAt); });

        return Uni.createFrom().completionStage(future);
    }

    // ── Response parsing ──────────────────────────────────────────────────────

    private ExecutionRecord parseResponse(
            HttpResponse<String> response,
            Intent intent, PlanStep step, int attempt,
            String model, Instant startedAt) {

        long latencyMs = Duration.between(startedAt, Instant.now()).toMillis();

        if (response.statusCode() == 429) {
            throw new LlmAdapterException("RATE_LIMITED",
                    "OpenAI rate limit exceeded: " + response.body(),
                    PROVIDER, model, attempt, latencyMs);
        }
        if (response.statusCode() >= 400) {
            throw new LlmAdapterException("ADAPTER_ERROR",
                    "OpenAI HTTP " + response.statusCode() + ": " + response.body(),
                    PROVIDER, model, attempt, latencyMs);
        }

        JsonNode body;
        try {
            body = MAPPER.readTree(response.body());
        } catch (Exception e) {
            throw new LlmAdapterException("INVALID_OUTPUT",
                    "OpenAI returned unparseable JSON: " + e.getMessage(),
                    PROVIDER, model, attempt, latencyMs);
        }

        JsonNode choices = body.get("choices");
        JsonNode usage   = body.get("usage");

        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            throw new LlmAdapterException("INVALID_OUTPUT",
                    "OpenAI returned empty choices",
                    PROVIDER, model, attempt, latencyMs);
        }

        String outputText = choices.get(0)
                .path("message")
                .path("content")
                .asText("");

        int promptTokens     = usage != null ? usage.path("prompt_tokens").asInt(0)     : 0;
        int completionTokens = usage != null ? usage.path("completion_tokens").asInt(0) : 0;
        int totalTokens      = promptTokens + completionTokens;
        BigDecimal cost      = computeCost(model, promptTokens, completionTokens);

        Log.infof("OpenAI success: model=%s, intent=%s, attempt=%d, tokens=%d, cost=$%.6f, latency=%dms",
                model, intent.getId(), attempt, totalTokens, cost, latencyMs);

        return ExecutionRecord.of(
                intent.getId(),
                attempt,
                step.getAdapterId() != null ? step.getAdapterId().toString() : null,
                latencyMs,
                cost != null ? cost : BigDecimal.ZERO,
                null,   // FailureType null = SUCCESS
                null    // PlanVersion
        ).withResponseText(outputText);
    }

    // ── Cost ─────────────────────────────────────────────────────────────────

    private BigDecimal computeCost(String model, int promptTokens, int completionTokens) {
        BigDecimal inRate  = model.startsWith("gpt-4o-mini") ? gpt4oMiniInputCostPer1k  : gpt4oInputCostPer1k;
        BigDecimal outRate = model.startsWith("gpt-4o-mini") ? gpt4oMiniOutputCostPer1k : gpt4oOutputCostPer1k;

        return inRate .multiply(BigDecimal.valueOf(promptTokens))
                .divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP)
                .add(outRate.multiply(BigDecimal.valueOf(completionTokens))
                        .divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP));
    }

    // ── Exception mapping ─────────────────────────────────────────────────────

    private RuntimeException mapException(Throwable ex, String model, int attempt, Instant startedAt) {
        long latencyMs = Duration.between(startedAt, Instant.now()).toMillis();

        // thenApply() wraps exceptions from parseResponse() in CompletionException — unwrap it
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;

        if (cause instanceof LlmAdapterException lae) return lae;
        if (cause instanceof LlmTimeoutException  lte) return lte;

        if (cause.getClass().getSimpleName().toLowerCase().contains("timeout")) {
            return new LlmTimeoutException(
                    "OpenAI timed out after " + latencyMs + "ms",
                    PROVIDER, model, attempt, latencyMs);
        }
        return new LlmAdapterException("ADAPTER_ERROR",
                "OpenAI request failed: " + cause.getMessage(),
                PROVIDER, model, attempt, latencyMs);
    }
}