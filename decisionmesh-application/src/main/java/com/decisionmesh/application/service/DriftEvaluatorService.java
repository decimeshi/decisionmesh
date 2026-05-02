package com.decisionmesh.application.service;

import com.decisionmesh.application.port.ExecutionRecordQueryPort;
import com.decisionmesh.application.port.ExecutionRecordQueryPort.DriftRow;
import com.decisionmesh.domain.execution.ExecutionRecord;
import com.decisionmesh.domain.intent.Intent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Drift evaluator — compares the current execution against the 30-day adapter baseline.
 *
 * ── Self-healing baseline ─────────────────────────────────────────────────────
 * Returns drift=0.0 when:
 *   - Fewer than MIN_BASELINE_RECORDS (5) executions exist for this adapter
 *   - The adapter has never been used (cold start)
 *
 * This is correct behaviour — drift only has meaning relative to a known baseline.
 * After 5+ executions the baseline builds up automatically and drift scoring activates.
 *
 * ── Drift dimensions (weighted) ──────────────────────────────────────────────
 *   cost drift    30% — actual cost vs baseline average
 *   latency drift 25% — actual latency vs baseline average
 *   quality drift 30% — actual quality vs baseline average
 *   failure rate  10% — rolling failure rate
 *   semantic drift 25% — cosine distance between response text embeddings via Ollama
 *
 * Ollama nomic-embed-text (274MB, CPU-only, ~45ms/embed) computes dense vector
 * representations of the response text. Cosine distance measures how far the
 * current response has semantically drifted from the baseline average embedding.
 *
 * Drift score 0.0 = identical to baseline. 1.0 = maximum deviation.
 * Stored on the intent via intent.updateDriftScore() and used by
 * policy rules (e.g. alertOnDrift=true, driftThreshold=0.20).
 */
@ApplicationScoped
public class DriftEvaluatorService {

    private static final int    MIN_BASELINE_RECORDS = 5;
    private static final int    BASELINE_DAYS        = 30;

    // Drift dimension weights — must sum to 1.0
    private static final double COST_WEIGHT     = 0.25;
    private static final double LATENCY_WEIGHT  = 0.20;
    private static final double QUALITY_WEIGHT  = 0.20;
    private static final double FAILURE_WEIGHT  = 0.10;
    private static final double SEMANTIC_WEIGHT = 0.25; // cosine distance via Ollama embeddings

    @Inject
    ExecutionRecordQueryPort executionRecordQueryPort;

    @ConfigProperty(name = "embedding.url",   defaultValue = "http://localhost:11434")
    String ollamaUrl;

    @ConfigProperty(name = "embedding.model", defaultValue = "nomic-embed-text")
    String embeddingModel;

    private static final ObjectMapper MAPPER     = new ObjectMapper();
    private static final HttpClient   HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    // ── Public API ────────────────────────────────────────────────────────────

    public Uni<BigDecimal> computeDriftScore(Intent intent, ExecutionRecord current) {
        String adapterId = current.getAdapterId();

        if (adapterId == null || adapterId.isBlank()) {
            Log.debugf("[Drift] No adapterId on execution record for intent=%s — drift=0",
                    intent.getId());
            return Uni.createFrom().item(BigDecimal.ZERO);
        }

        Instant since = Instant.now().minus(BASELINE_DAYS, ChronoUnit.DAYS);

        return executionRecordQueryPort.findRecentByAdapter(adapterId, since)
                .map(baseline -> computeDrift(current, baseline, adapterId, intent.getId().toString()))
                .onFailure().invoke(ex ->
                        Log.warnf("[Drift] Failed to load baseline for adapter=%s: %s",
                                adapterId, ex.getMessage()))
                .onFailure().recoverWithItem(BigDecimal.ZERO);
    }

    // ── Drift calculation ─────────────────────────────────────────────────────

    private BigDecimal computeDrift(ExecutionRecord current,
                                    List<DriftRow> baseline,
                                    String adapterId,
                                    String intentId) {

        if (baseline.size() < MIN_BASELINE_RECORDS) {
            Log.debugf("[Drift] Insufficient baseline for adapter=%s (%d records, need %d) → drift=0",
                    adapterId, baseline.size(), MIN_BASELINE_RECORDS);
            return BigDecimal.ZERO;
        }

        // ── Statistical dimensions ────────────────────────────────────────────
        double avgCost    = baseline.stream()
                .mapToDouble(r -> r.costUsd() != null ? r.costUsd().doubleValue() : 0.0)
                .average().orElse(0.0);
        double avgLatency = baseline.stream()
                .mapToLong(DriftRow::latencyMs)
                .average().orElse(0.0);
        double avgQuality = baseline.stream()
                .filter(r -> r.qualityScore() != null)
                .mapToDouble(r -> r.qualityScore().doubleValue())
                .average().orElse(-1.0);
        double failureRate = baseline.stream()
                .mapToDouble(r -> r.isFailed() ? 1.0 : 0.0)
                .average().orElse(0.0);

        double currentCost    = current.getCost() != null ? current.getCost().doubleValue() : 0.0;
        double currentLatency = current.getLatencyMs();
        boolean currentFailed = current.getFailureType() != null;

        double costDrift    = avgCost    > 0 ? normalisedDiff(currentCost,    avgCost)    : 0.0;
        double latencyDrift = avgLatency > 0 ? normalisedDiff(currentLatency, avgLatency) : 0.0;
        double failureDrift = Math.abs((currentFailed ? 1.0 : 0.0) - failureRate);

        double qualityDrift = 0.0;
        if (avgQuality >= 0 && current.isQualityScored()) {
            double currentQuality = current.getQualityScore() != null
                    ? current.getQualityScore().doubleValue() : 0.0;
            qualityDrift = normalisedDiff(currentQuality, avgQuality);
        }

        // ── Semantic dimension via Ollama nomic-embed-text ────────────────────
        // Compare cosine distance between the current response embedding and the
        // average of baseline response embeddings. Higher distance = more drift.
        // Falls back to 0.0 if Ollama is unavailable or text is missing.
        double semanticDrift = computeSemanticDrift(current, baseline, adapterId);

        double composite = clamp(
                costDrift     * COST_WEIGHT     +
                        latencyDrift  * LATENCY_WEIGHT  +
                        qualityDrift  * QUALITY_WEIGHT  +
                        failureDrift  * FAILURE_WEIGHT  +
                        semanticDrift * SEMANTIC_WEIGHT
        );

        Log.infof("[Drift] adapter=%s baseline=%d cost=%.4f lat=%.4f qual=%.4f fail=%.4f semantic=%.4f → drift=%.4f",
                adapterId, baseline.size(), costDrift, latencyDrift, qualityDrift, failureDrift,
                semanticDrift, composite);

        return BigDecimal.valueOf(composite).setScale(4, RoundingMode.HALF_UP);
    }

    // ── Semantic drift via Ollama embeddings ──────────────────────────────────

    /**
     * Computes cosine distance between the current response text embedding
     * and the average of baseline response embeddings.
     *
     * Steps:
     *   1. Embed current response text via Ollama (POST /api/embeddings)
     *   2. Embed each baseline response text (up to 10 most recent)
     *   3. Average baseline embeddings into a centroid vector
     *   4. Cosine distance = 1 - cosine_similarity(current, centroid)
     *
     * Returns 0.0 on any failure — semantic drift is informational only.
     */
    /**
     * NOTE: DriftRow record in ExecutionRecordQueryPort must include:
     *   String responseText()   — the LLM response text from the execution
     *
     * Add to DriftRow if not present:
     *   record DriftRow(UUID executionId, BigDecimal costUsd, long latencyMs,
     *                   BigDecimal qualityScore, boolean isFailed, String responseText) {}
     *
     * And in the SQL query for findRecentByAdapter():
     *   SELECT ..., response_text FROM execution_records WHERE adapter_id = ? AND ...
     */
    private double computeSemanticDrift(ExecutionRecord current,
                                        List<DriftRow> baseline,
                                        String adapterId) {
        String currentText = current.getResponseText();
        if (currentText == null || currentText.isBlank()) {
            Log.debugf("[Drift/Semantic] No response text on current record — skipping semantic drift");
            return 0.0;
        }

        // Collect baseline texts (up to 10 most recent with text)
        List<String> baselineTexts = baseline.stream()
                .filter(r -> r.responseText() != null && !r.responseText().isBlank())
                .limit(10)
                .map(DriftRow::responseText)
                .toList();

        if (baselineTexts.isEmpty()) {
            Log.debugf("[Drift/Semantic] No baseline response texts for adapter=%s — skipping semantic drift",
                    adapterId);
            return 0.0;
        }

        try {
            // Embed current response
            double[] currentEmbedding = embed(currentText);
            if (currentEmbedding == null) return 0.0;

            // Embed each baseline text and average into centroid
            double[] centroid = null;
            int count = 0;
            for (String text : baselineTexts) {
                double[] e = embed(text);
                if (e == null) continue;
                if (centroid == null) {
                    centroid = new double[e.length];
                }
                for (int i = 0; i < e.length; i++) centroid[i] += e[i];
                count++;
            }

            if (centroid == null || count == 0) return 0.0;

            // Normalise centroid
            for (int i = 0; i < centroid.length; i++) centroid[i] /= count;

            // Cosine distance = 1 - cosine_similarity
            double similarity = cosineSimilarity(currentEmbedding, centroid);
            double distance   = clamp(1.0 - similarity);

            Log.debugf("[Drift/Semantic] adapter=%s baseline_texts=%d similarity=%.4f distance=%.4f",
                    adapterId, count, similarity, distance);

            return distance;

        } catch (Exception ex) {
            Log.warnf("[Drift/Semantic] Embedding failed for adapter=%s: %s — using 0.0",
                    adapterId, ex.getMessage());
            return 0.0;
        }
    }

    /**
     * Calls Ollama embeddings API synchronously on the worker pool.
     * Returns null on failure (caller treats null as 0.0 drift contribution).
     *
     * API: POST {embedding.url}/api/embeddings
     *      {"model": "nomic-embed-text", "prompt": "<text>"}
     * Response: {"embedding": [0.123, -0.456, ...]}  (768 dimensions)
     */
    private double[] embed(String text) {
        try {
            ObjectNode body = MAPPER.createObjectNode()
                    .put("model",  embeddingModel)
                    .put("prompt", text.length() > 4096 ? text.substring(0, 4096) : text);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaUrl + "/api/embeddings"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                Log.warnf("[Drift/Semantic] Ollama returned HTTP %d", response.statusCode());
                return null;
            }

            JsonNode respNode = MAPPER.readTree(response.body());
            ArrayNode embNode = (ArrayNode) respNode.get("embedding");
            if (embNode == null || embNode.isEmpty()) return null;

            double[] result = new double[embNode.size()];
            for (int i = 0; i < embNode.size(); i++) {
                result[i] = embNode.get(i).asDouble();
            }
            return result;

        } catch (Exception ex) {
            Log.warnf("[Drift/Semantic] embed() failed: %s", ex.getMessage());
            return null;
        }
    }

    /**
     * Cosine similarity between two vectors.
     * Returns value in [-1, 1]; 1.0 = identical direction, 0.0 = orthogonal.
     * For text embeddings typical range is [0.3, 1.0].
     */
    private double cosineSimilarity(double[] a, double[] b) {
        if (a.length != b.length) return 0.0;
        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot   += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0.0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    // Normalised absolute difference capped at 1.0
    // e.g. if baseline=0.01 and current=0.03, diff = 200% → capped at 1.0
    private double normalisedDiff(double current, double baseline) {
        if (baseline == 0) return current > 0 ? 1.0 : 0.0;
        return clamp(Math.abs(current - baseline) / baseline);
    }

    private static double clamp(double v) { return Math.max(0.0, Math.min(1.0, v)); }
}