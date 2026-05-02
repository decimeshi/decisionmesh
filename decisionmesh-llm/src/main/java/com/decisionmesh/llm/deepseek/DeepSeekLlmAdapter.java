package com.decisionmesh.llm.deepseek;

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
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
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
 * DeepSeek Chat adapter.
 *
 * Supported models:  deepseek-chat (DeepSeek-V3), deepseek-reasoner (DeepSeek-R1)
 * Wire format:       OpenAI-compatible — POST /v1/chat/completions
 * Auth:              Authorization: Bearer <api-key>
 *
 * DeepSeek's API is intentionally OpenAI-compatible so the request format
 * is identical. The response adds a reasoning_content field for deepseek-reasoner
 * which we capture in the output alongside the final answer.
 *
 * Cost model: prompt_tokens × input_price + completion_tokens × output_price
 *             deepseek-reasoner bills reasoning_tokens separately at a higher rate.
 */
@ApplicationScoped
public class DeepSeekLlmAdapter implements LlmAdapter {

    private static final String PROVIDER         = "DEEPSEEK";
    private static final String DEFAULT_ENDPOINT = "https://api.deepseek.com/v1/chat/completions";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── Config ────────────────────────────────────────────────────────────────

    @ConfigProperty(name = "llm.deepseek.api.key")
    String apiKey;

    @ConfigProperty(name = "llm.deepseek.default-model",      defaultValue = "deepseek-chat")
    String defaultModel;

    @ConfigProperty(name = "llm.deepseek.default-timeout-ms", defaultValue = "60000")
    int defaultTimeoutMs;

    @ConfigProperty(name = "llm.deepseek.deepseek-chat.input-cost-per-1k",  defaultValue = "0.00014")
    BigDecimal chatInputCostPer1k;

    @ConfigProperty(name = "llm.deepseek.deepseek-chat.output-cost-per-1k", defaultValue = "0.00028")
    BigDecimal chatOutputCostPer1k;

    @ConfigProperty(name = "llm.deepseek.deepseek-reasoner.input-cost-per-1k",  defaultValue = "0.00055")
    BigDecimal reasonerInputCostPer1k;

    @ConfigProperty(name = "llm.deepseek.deepseek-reasoner.output-cost-per-1k", defaultValue = "0.00219")
    BigDecimal reasonerOutputCostPer1k;

    private final HttpClient httpClient;

    @Inject
    public DeepSeekLlmAdapter() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
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
        int maxTokens   = step.getConfigInt("max_tokens",     2048);
        double temp     = step.getConfigDouble("temperature", 0.0);
        String endpoint = step.getConfigString("endpoint",    DEFAULT_ENDPOINT);
        long timeoutMs  = step.getConfigLong("timeout_ms",    (long) defaultTimeoutMs);

        ArrayNode messages = PromptBuilder.buildMessages(intent);

        // OpenAI-compatible request format
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
                            "Failed to serialize DeepSeek request: " + e.getMessage(),
                            PROVIDER, model, attempt, 0L));
        }

        Log.debugf("DeepSeek request: model=%s, intent=%s, attempt=%d",
                model, intent.getId(), attempt);

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
                    "DeepSeek rate limit exceeded: " + response.body(),
                    PROVIDER, model, attempt, latencyMs);
        }
        if (response.statusCode() >= 400) {
            throw new LlmAdapterException("ADAPTER_ERROR",
                    "DeepSeek HTTP " + response.statusCode() + ": " + response.body(),
                    PROVIDER, model, attempt, latencyMs);
        }

        JsonNode body;
        try {
            body = MAPPER.readTree(response.body());
        } catch (Exception e) {
            throw new LlmAdapterException("INVALID_OUTPUT",
                    "DeepSeek returned unparseable JSON: " + e.getMessage(),
                    PROVIDER, model, attempt, latencyMs);
        }

        JsonNode choices = body.get("choices");
        JsonNode usage   = body.get("usage");

        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            throw new LlmAdapterException("INVALID_OUTPUT",
                    "DeepSeek returned empty choices",
                    PROVIDER, model, attempt, latencyMs);
        }

        JsonNode message = choices.get(0).path("message");

        // deepseek-reasoner returns both reasoning_content and content
        String reasoningContent = message.path("reasoning_content").asText(null);
        String finalContent     = message.path("content").asText("");

        // Combine reasoning trace + final answer if present
        String outputText = (reasoningContent != null && !reasoningContent.isBlank())
                ? "<thinking>\n" + reasoningContent + "\n</thinking>\n\n" + finalContent
                : finalContent;

        int promptTokens     = usage != null ? usage.path("prompt_tokens").asInt(0)     : 0;
        int completionTokens = usage != null ? usage.path("completion_tokens").asInt(0) : 0;
        int totalTokens      = promptTokens + completionTokens;

        // Reasoning tokens are a subset of completion tokens for billing
        int reasoningTokens = 0;
        if (usage != null) {
            JsonNode completionDetails = usage.get("completion_tokens_details");
            if (completionDetails != null && !completionDetails.isNull()) {
                reasoningTokens = completionDetails.path("reasoning_tokens").asInt(0);
            }
        }

        BigDecimal cost = computeCost(model, promptTokens, completionTokens, reasoningTokens);

        Log.infof("DeepSeek success: model=%s, intent=%s, attempt=%d, tokens=%d (reasoning=%d), cost=$%.6f, latency=%dms",
                model, intent.getId(), attempt, totalTokens, reasoningTokens, cost, latencyMs);

        return ExecutionRecord.of(
                intent.getId(),
                attempt,
                step.getAdapterId() != null ? step.getAdapterId().toString() : null,
                latencyMs,
                BigDecimal.valueOf(cost != null ? cost.doubleValue() : 0.0),
                null,   // FailureType null = SUCCESS
                null    // PlanVersion
        );
    }

    // ── Cost ─────────────────────────────────────────────────────────────────

    /**
     * DeepSeek-R1 (reasoner) bills reasoning tokens at the output rate.
     * Regular completion tokens (non-reasoning) are also billed at output rate.
     * Both are included in completion_tokens so we use a single output rate.
     */
    private BigDecimal computeCost(String model, int promptTokens,
                                   int completionTokens, int reasoningTokens) {
        boolean isReasoner = model.contains("reasoner");
        BigDecimal inRate  = isReasoner ? reasonerInputCostPer1k  : chatInputCostPer1k;
        BigDecimal outRate = isReasoner ? reasonerOutputCostPer1k : chatOutputCostPer1k;

        return inRate .multiply(BigDecimal.valueOf(promptTokens))
                .divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP)
                .add(outRate.multiply(BigDecimal.valueOf(completionTokens))
                        .divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP));
    }

    // ── Exception mapping ─────────────────────────────────────────────────────

    private RuntimeException mapException(Throwable ex, String model, int attempt, Instant startedAt) {
        long latencyMs = Duration.between(startedAt, Instant.now()).toMillis();

        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;

        if (cause instanceof LlmAdapterException lae) return lae;
        if (cause instanceof LlmTimeoutException  lte) return lte;

        if (cause.getClass().getSimpleName().toLowerCase().contains("timeout")) {
            return new LlmTimeoutException(
                    "DeepSeek timed out after " + latencyMs + "ms",
                    PROVIDER, model, attempt, latencyMs);
        }
        return new LlmAdapterException("ADAPTER_ERROR",
                "DeepSeek request failed: " + cause.getMessage(),
                PROVIDER, model, attempt, latencyMs);
    }
}