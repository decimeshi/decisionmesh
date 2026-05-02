package com.decisionmesh.domain.intent.value;

import java.util.List;

/**
 * Describes what an intent is trying to achieve.
 *
 * @param objectiveType    COST, LATENCY, RISK, QUALITY — drives adapter scoring in planner
 * @param targetThreshold  Numeric target (e.g. max cost in USD, max latency in ms)
 * @param tolerance        Acceptable deviation from targetThreshold (0.0 = strict)
 * @param description      The user prompt / request text sent to the LLM
 * @param taskType         Optional task framing for PromptBuilder system prompt
 *                         (e.g. "MEDICAL_SUMMARISATION", "CODE_REVIEW", "TRANSLATION")
 * @param successCriteria  Optional list of criteria the LLM output must satisfy
 *                         (included in system prompt by PromptBuilder)
 * @param context          Optional background context prepended before the user prompt
 */
public record IntentObjective(
        ObjectiveType  objectiveType,
        double         targetThreshold,
        double         tolerance,
        String         description,
        String         taskType,
        List<String>   successCriteria,
        String         context
) {

    // ── Compact constructor — defensive copy of list ──────────────────────────

    public IntentObjective {
        successCriteria = successCriteria != null
                ? List.copyOf(successCriteria)
                : List.of();
    }

    // ── Original factory (backward-compatible) ────────────────────────────────

    public static IntentObjective of(ObjectiveType type,
                                     double targetThreshold,
                                     double tolerance) {
        return new IntentObjective(type, targetThreshold, tolerance,
                null, null, List.of(), null);
    }

    // ── Convenience factories ─────────────────────────────────────────────────

    /**
     * Minimal factory — just a description (prompt text) and objective type.
     * Used in tests and simple cases where full metadata is not needed.
     */
    public static IntentObjective of(String description, ObjectiveType type) {
        return new IntentObjective(type, 0.0, 0.0,
                description, null, List.of(), null);
    }

    /**
     * Full factory — all fields.
     */
    public static IntentObjective of(ObjectiveType type,
                                     double targetThreshold,
                                     double tolerance,
                                     String description,
                                     String taskType,
                                     List<String> successCriteria,
                                     String context) {
        return new IntentObjective(type, targetThreshold, tolerance,
                description, taskType, successCriteria, context);
    }

    // ── Getters for PromptBuilder ─────────────────────────────────────────────

    /**
     * The user prompt text sent to the LLM.
     * Used by PromptBuilder.buildUserPrompt() and all LlmAdapter implementations.
     */
    public String getDescription() {
        return description;
    }

    /**
     * Optional task type for system prompt framing.
     * Used by PromptBuilder.buildSystemPrompt() to orient the model.
     * Returns null if not set — PromptBuilder skips the task framing line.
     */
    public String getTaskType() {
        return taskType;
    }

    /**
     * Optional success criteria for system prompt.
     * Used by PromptBuilder.buildSystemPrompt() to enumerate requirements.
     * Returns empty list (never null) if not set.
     */
    public List<String> getSuccessCriteria() {
        return successCriteria;
    }

    /**
     * Optional context block prepended before the user prompt.
     * Used by PromptBuilder.buildUserPrompt() when context is provided.
     * Returns null if not set — PromptBuilder uses description only.
     */
    public String getContext() {
        return context;
    }
}