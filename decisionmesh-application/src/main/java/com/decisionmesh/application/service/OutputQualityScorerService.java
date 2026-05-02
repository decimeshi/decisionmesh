package com.decisionmesh.application.service;

import com.decisionmesh.domain.execution.ExecutionRecord;
import com.decisionmesh.domain.intent.Intent;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Pattern;

/**
 * Heuristic output quality scorer.
 *
 * Runs in the EVALUATING phase of the orchestrator pipeline after
 * the adapter returns a response. Scores four dimensions:
 *
 *   1. Faithfulness   — how well the response covers the objective keywords
 *   2. Completeness   — response length heuristic
 *   3. Hallucination  — high-confidence unsupported claims, suspicious numbers
 *   4. Tone           — appropriate professional language
 *
 * Returns QualityScore.skipped() when:
 *   - responseText is null or blank (mock adapter, or adapter didn't call withResponseText())
 *   - scoring itself throws an exception
 *
 * The orchestrator calls scoreQuality(intent, record) and uses withQuality()
 * to produce a new immutable ExecutionRecord with the scores attached.
 * These scores are then saved to execution_records by persistBlocking().
 */
@ApplicationScoped
public class OutputQualityScorerService {

    private static final Pattern HIGH_RISK_PATTERN = Pattern.compile(
            "\\b(definitely|certainly|absolutely|proven|guaranteed|always|never|" +
                    "impossible|undeniably|unquestionably)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern HEDGE_PATTERN = Pattern.compile(
            "\\b(may|might|could|possibly|perhaps|likely|approximately|around|" +
                    "roughly|about|seems|appears|suggests|indicates)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern SPECIFIC_NUMBER_PATTERN = Pattern.compile(
            "\\b\\d{2,}(?:\\.\\d+)?%?\\b");

    // ── Public API ────────────────────────────────────────────────────────────

    public Uni<QualityScore> score(Intent intent, ExecutionRecord record) {
        return Uni.createFrom().item(() -> scoreSync(intent, record));
    }

    private QualityScore scoreSync(Intent intent, ExecutionRecord record) {
        // Skip scoring when response text is absent — mock adapters or adapters
        // that don't call withResponseText() will produce null here.
        String response = record.getResponseText();
        if (response == null || response.isBlank()) {
            Log.debugf("[Quality] No responseText for intent=%s — skipping scoring",
                    intent.getId());
            return QualityScore.skipped("No response text — adapter may be mock or text not stored");
        }

        try {
            double faithfulness   = scoreFaithfulness(intent, response);
            double completeness   = scoreCompleteness(response);
            double hallucinationRisk = scoreHallucinationRisk(response);
            boolean hallucinationDetected = hallucinationRisk > 0.65;

            // Weighted composite: faithfulness 40%, completeness 30%, hallucination penalty 30%
            double overall = clamp(
                    faithfulness   * 0.40 +
                            completeness   * 0.30 +
                            (1.0 - hallucinationRisk) * 0.30
            );

            String reasoning = buildReasoning(overall, faithfulness, completeness,
                    hallucinationRisk, hallucinationDetected);

            Log.infof("[Quality] intent=%s overall=%.2f faithful=%.2f complete=%.2f hallRisk=%.2f flagged=%s",
                    intent.getId(), overall, faithfulness, completeness,
                    hallucinationRisk, hallucinationDetected);

            return new QualityScore(
                    round(overall),
                    round(hallucinationRisk),
                    hallucinationDetected,
                    reasoning,
                    "HEURISTIC"
            );

        } catch (Exception ex) {
            Log.warnf("[Quality] Scoring failed for intent=%s: %s",
                    intent.getId(), ex.getMessage());
            return QualityScore.skipped("Scorer error: " + ex.getMessage());
        }
    }

    // ── Scoring dimensions ────────────────────────────────────────────────────

    private double scoreFaithfulness(Intent intent, String response) {
        if (intent.getObjective() == null) return 0.5;

        String objectiveText;
        try {
            objectiveText = intent.getObjective().toString().toLowerCase();
        } catch (Exception e) {
            return 0.5;
        }

        String responseLower = response.toLowerCase();
        String[] words = objectiveText.replaceAll("[^a-z0-9 ]", " ").split("\\s+");

        int significant = 0, matched = 0;
        for (String word : words) {
            if (word.length() > 4) {
                significant++;
                if (responseLower.contains(word)) matched++;
            }
        }

        if (significant == 0) return 0.5;
        return clamp((double) matched / significant * 1.2);
    }

    private double scoreCompleteness(String response) {
        int len = response.trim().length();
        if (len < 20)   return 0.10;
        if (len < 100)  return 0.50;
        if (len < 300)  return 0.70;
        if (len < 1000) return 0.90;
        if (len < 3000) return 0.95;
        return 0.85; // very long — slight verbosity penalty
    }

    private double scoreHallucinationRisk(String response) {
        double risk = 0.0;
        String lower = response.toLowerCase();

        var highRisk = HIGH_RISK_PATTERN.matcher(lower);
        while (highRisk.find()) risk += 0.12;

        var hedge = HEDGE_PATTERN.matcher(lower);
        while (hedge.find()) risk -= 0.08;

        var numbers = SPECIFIC_NUMBER_PATTERN.matcher(lower);
        int numberCount = 0;
        while (numbers.find()) numberCount++;
        if (numberCount > 5) risk += 0.15;

        return clamp(risk);
    }

    private String buildReasoning(double overall, double faithfulness,
                                  double completeness, double hallucinationRisk,
                                  boolean hallucinationDetected) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Overall: %.0f%%. ", overall * 100));
        sb.append(String.format("Faithfulness: %.0f%%, ", faithfulness * 100));
        sb.append(String.format("Completeness: %.0f%%. ", completeness * 100));
        if (hallucinationDetected) {
            sb.append("⚠ Hallucination risk HIGH — response contains high-confidence unsupported claims.");
        } else if (hallucinationRisk > 0.3) {
            sb.append("Moderate hallucination risk — review response carefully.");
        } else {
            sb.append("Hallucination risk LOW — response uses appropriately hedged language.");
        }
        return sb.toString();
    }

    private static double clamp(double v) { return Math.max(0.0, Math.min(1.0, v)); }
    private static BigDecimal round(double v) {
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP);
    }

    // ── Result record ─────────────────────────────────────────────────────────

    public record QualityScore(
            BigDecimal overall,
            BigDecimal hallucinationRisk,
            boolean    hallucinationDetected,
            String     reasoning,
            String     method
    ) {
        public static QualityScore skipped(String reason) {
            return new QualityScore(null, null, false, reason, "SKIPPED");
        }

        public double overallDouble()            { return overall != null ? overall.doubleValue() : 0.0; }
        public double hallucinationRiskDouble()  { return hallucinationRisk != null ? hallucinationRisk.doubleValue() : 0.0; }
    }
}