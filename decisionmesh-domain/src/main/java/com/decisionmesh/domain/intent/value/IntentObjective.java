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
        String         context,

        // ── User message (V2) ──────────────────────────────────────────────────
        // The actual user input to process — e.g. the question to answer, the
        // transaction to analyse, the document to summarise.
        //
        // Distinct from description (the task instruction) so adapters can
        // structure the LLM call correctly:
        //   system prompt  ← governance + task framing
        //   user message   ← description (task) + userMessage (data to act on)
        //
        // When null, PromptBuilder falls back to description-only prompt.
        // Backward-compatible: all existing intents without userMessage still work.
        String         userMessage
) {

    // ── Compact constructor — defensive copy of list ──────────────────────────

    public IntentObjective {
        successCriteria = successCriteria != null
                ? List.copyOf(successCriteria)
                : List.of();
        // userMessage is String — immutable, no defensive copy needed
    }

    // ── Original factory (backward-compatible) ────────────────────────────────

    public static IntentObjective of(ObjectiveType type,
                                     double targetThreshold,
                                     double tolerance) {
        return new IntentObjective(type, targetThreshold, tolerance,
                null, null, List.of(), null, null);
    }

    // ── Convenience factories ─────────────────────────────────────────────────

    /**
     * Minimal factory — just a description (prompt text) and objective type.
     * Used in tests and simple cases where full metadata is not needed.
     */
    public static IntentObjective of(String description, ObjectiveType type) {
        return new IntentObjective(type, 0.0, 0.0,
                description, null, List.of(), null, null);
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
                description, taskType, successCriteria, context, null);
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

    /**
     * The actual user input to process — e.g. the question to answer,
     * the transaction to analyse, the document to summarise.
     *
     * When present, PromptBuilder appends this as "Input:" after the
     * task description so the LLM receives both the instruction AND
     * the data to act on. Returns null if not set.
     */
    public String getUserMessage() {
        return userMessage;
    }
}