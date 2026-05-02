package com.decisionmesh.llm.azure;

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
 * Azure OpenAI Service adapter.
 *
 * Supported deployments: gpt-4o, gpt-4o-mini, gpt-4-turbo, o1, o3-mini
 *                        (any model deployed under an Azure OpenAI resource)
 *
 * Wire format:  POST https://{resource}.openai.azure.com/openai/deployments/{deployment}
 *                        /chat/completions?api-version={apiVersion}
 * Auth:         api-key header (NOT Bearer token — Azure uses its own key scheme)
 *
 * Key difference from OpenAI:
 *   - URL encodes the DEPLOYMENT NAME, not the model name
 *   - api-version query param is required
 *   - Auth header is "api-key" not "Authorization: Bearer"
 *   - content_filter_results in the response indicates Azure content safety blocks
 *
 * Deployment name and resource name are stored in PlanStep.configSnapshot:
 *   {
 *     "provider":         "AZURE_OPENAI",
 *     "resource_name":    "my-azure-resource",
 *     "deployment_name":  "gpt-4o-prod",
 *     "api_version":      "2024-12-01-preview",
 *     "model":            "gpt-4o",       ← for cost lookup only
 *     "max_tokens":       1024,
 *     "timeout_ms":       30000
 *   }
 */
@ApplicationScoped
public class AzureOpenAILlmAdapter implements LlmAdapter {

    private static final String PROVIDER            = "AZURE_OPENAI";
    private static final String DEFAULT_API_VERSION = "2024-12-01-preview";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── Config ────────────────────────────────────────────────────────────────

    @ConfigProperty(name = "llm.azure.api-key")
    String apiKey;

    @ConfigProperty(name = "llm.azure.resource-name")
    String defaultResourceName;

    @ConfigProperty(name = "llm.azure.default-deployment",    defaultValue = "gpt-4o")
    String defaultDeployment;

    @ConfigProperty(name = "llm.azure.default-timeout-ms",    defaultValue = "30000")
    int defaultTimeoutMs;

    @ConfigProperty(name = "llm.azure.api-version",           defaultValue = "2024-12-01-preview")
    String defaultApiVersion;

    @ConfigProperty(name = "llm.azure.gpt-4o.input-cost-per-1k",       defaultValue = "0.005")
    BigDecimal gpt4oInputCostPer1k;

    @ConfigProperty(name = "llm.azure.gpt-4o.output-cost-per-1k",      defaultValue = "0.015")
    BigDecimal gpt4oOutputCostPer1k;

    @ConfigProperty(name = "llm.azure.gpt-4o-mini.input-cost-per-1k",  defaultValue = "0.00015")
    BigDecimal gpt4oMiniInputCostPer1k;

    @ConfigProperty(name = "llm.azure.gpt-4o-mini.output-cost-per-1k", defaultValue = "0.0006")
    BigDecimal gpt4oMiniOutputCostPer1k;

    @ConfigProperty(name = "llm.azure.o1.input-cost-per-1k",           defaultValue = "0.015")
    BigDecimal o1InputCostPer1k;

    @ConfigProperty(name = "llm.azure.o1.output-cost-per-1k",          defaultValue = "0.060")
    BigDecimal o1OutputCostPer1k;

    @ConfigProperty(name = "llm.azure.o3-mini.input-cost-per-1k",      defaultValue = "0.0011")
    BigDecimal o3MiniInputCostPer1k;

    @ConfigProperty(name = "llm.azure.o3-mini.output-cost-per-1k",     defaultValue = "0.0044")
    BigDecimal o3MiniOutputCostPer1k;

    private final HttpClient httpClient;

    @Inject
    public AzureOpenAILlmAdapter() {
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
        String resourceName   = step.getConfigString("resource_name",   defaultResourceName);
        String deploymentName = step.getConfigString("deployment_name", defaultDeployment);
        String apiVersion     = step.getConfigString("api_version",     defaultApiVersion);
        String modelHint      = step.getConfigString("model",           deploymentName);
        int    maxTokens      = step.getConfigInt("max_tokens",         1024);
        double temp           = step.getConfigDouble("temperature",     0.2);
        long   timeoutMs      = step.getConfigLong("timeout_ms",        (long) defaultTimeoutMs);

        ArrayNode messages = PromptBuilder.buildMessages(intent);

        String endpoint = String.format(
                "https://%s.openai.azure.com/openai/deployments/%s/chat/completions?api-version=%s",
                resourceName, deploymentName, apiVersion);

        // Azure body: model field NOT required — deployment name encodes it
        ObjectNode requestBody = MAPPER.createObjectNode()
                .put("max_tokens",   maxTokens)
                .put("temperature",  temp);
        requestBody.set("messages", messages);

        String requestBodyStr;
        try {
            requestBodyStr = MAPPER.writeValueAsString(requestBody);
        } catch (Exception e) {
            return Uni.createFrom().failure(
                    new LlmAdapterException("ADAPTER_ERROR",
                            "Failed to serialize Azure OpenAI request: " + e.getMessage(),
                            PROVIDER, deploymentName, attempt, 0L));
        }

        Log.debugf("Azure OpenAI request: resource=%s, deployment=%s, intent=%s, attempt=%d",
                resourceName, deploymentName, intent.getId(), attempt);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/json")
                .header("api-key",      apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBodyStr))
                .build();

        CompletableFuture<ExecutionRecord> future =
                httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                        .thenApply(response -> parseResponse(
                                response, intent, step, attempt, modelHint, deploymentName, startedAt))
                        .exceptionally(ex -> { throw mapException(ex, modelHint, attempt, startedAt); });

        return Uni.createFrom().completionStage(future);
    }

    // ── Response parsing ──────────────────────────────────────────────────────

    private ExecutionRecord parseResponse(
            HttpResponse<String> response,
            Intent intent, PlanStep step, int attempt,
            String modelHint, String deploymentName, Instant startedAt) {

        long latencyMs = Duration.between(startedAt, Instant.now()).toMillis();

        if (response.statusCode() == 429) {
            throw new LlmAdapterException("RATE_LIMITED",
                    "Azure OpenAI rate limit exceeded: " + response.body(),
                    PROVIDER, deploymentName, attempt, latencyMs);
        }
        if (response.statusCode() >= 400) {
            throw new LlmAdapterException("ADAPTER_ERROR",
                    "Azure OpenAI HTTP " + response.statusCode() + ": " + response.body(),
                    PROVIDER, deploymentName, attempt, latencyMs);
        }

        JsonNode body;
        try {
            body = MAPPER.readTree(response.body());
        } catch (Exception e) {
            throw new LlmAdapterException("INVALID_OUTPUT",
                    "Azure OpenAI returned unparseable JSON: " + e.getMessage(),
                    PROVIDER, deploymentName, attempt, latencyMs);
        }

        JsonNode choices = body.get("choices");
        JsonNode usage   = body.get("usage");

        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            throw new LlmAdapterException("INVALID_OUTPUT",
                    "Azure OpenAI returned empty choices",
                    PROVIDER, deploymentName, attempt, latencyMs);
        }

        JsonNode choice      = choices.get(0);
        String  outputText   = choice.path("message").path("content").asText("");
        String  finishReason = choice.path("finish_reason").asText("");

        // Azure content safety: content_filter_results on the choice
        JsonNode filterResults = choice.get("content_filter_results");
        if (filterResults != null && !filterResults.isNull()) {
            checkContentFilterBlock(filterResults, deploymentName, attempt, latencyMs);
        }

        if ("content_filter".equals(finishReason)) {
            throw new LlmAdapterException("POLICY_BLOCK",
                    "Azure content safety filter blocked the response",
                    PROVIDER, deploymentName, attempt, latencyMs);
        }

        int promptTokens     = usage != null ? usage.path("prompt_tokens").asInt(0)     : 0;
        int completionTokens = usage != null ? usage.path("completion_tokens").asInt(0) : 0;
        int totalTokens      = promptTokens + completionTokens;
        BigDecimal cost      = computeCost(modelHint, promptTokens, completionTokens);

        Log.infof("Azure OpenAI success: deployment=%s, intent=%s, attempt=%d, tokens=%d, cost=$%.6f, latency=%dms",
                deploymentName, intent.getId(), attempt, totalTokens, cost, latencyMs);

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

    /**
     * Azure content filter results structure:
     * { "hate": { "filtered": false }, "sexual": { "filtered": false }, ... }
     * If any category has filtered=true, throw POLICY_BLOCK.
     */
    private void checkContentFilterBlock(JsonNode filterResults,
                                         String deployment, int attempt, long latencyMs) {
        filterResults.fields().forEachRemaining(entry -> {
            JsonNode result = entry.getValue();
            if (result != null && result.path("filtered").asBoolean(false)) {
                throw new LlmAdapterException("POLICY_BLOCK",
                        "Azure content filter triggered for category: " + entry.getKey(),
                        PROVIDER, deployment, attempt, latencyMs);
            }
        });
    }

    // ── Cost ─────────────────────────────────────────────────────────────────

    private BigDecimal computeCost(String model, int promptTokens, int completionTokens) {
        BigDecimal inRate;
        BigDecimal outRate;

        String m = model.toLowerCase();
        if (m.contains("o3-mini")) {
            inRate  = o3MiniInputCostPer1k;
            outRate = o3MiniOutputCostPer1k;
        } else if (m.contains("o1")) {
            inRate  = o1InputCostPer1k;
            outRate = o1OutputCostPer1k;
        } else if (m.contains("gpt-4o-mini") || m.contains("4o-mini")) {
            inRate  = gpt4oMiniInputCostPer1k;
            outRate = gpt4oMiniOutputCostPer1k;
        } else {
            inRate  = gpt4oInputCostPer1k;
            outRate = gpt4oOutputCostPer1k;
        }

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
                    "Azure OpenAI timed out after " + latencyMs + "ms",
                    PROVIDER, model, attempt, latencyMs);
        }
        return new LlmAdapterException("ADAPTER_ERROR",
                "Azure OpenAI request failed: " + cause.getMessage(),
                PROVIDER, model, attempt, latencyMs);
    }
}