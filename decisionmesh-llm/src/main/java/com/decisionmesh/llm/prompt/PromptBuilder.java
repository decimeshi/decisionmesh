package com.decisionmesh.llm.prompt;

import com.decisionmesh.domain.intent.Intent;
import com.decisionmesh.domain.intent.value.IntentObjective;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Builds a structured messages array from an Intent for LLM providers.
 *
 * Instead of passing a raw string, we construct:
 *   [
 *     { "role": "system", "content": "<governance context>" },
 *     { "role": "user",   "content": "<user prompt>" }
 *   ]
 *
 * The system prompt encodes:
 *   - Task type framing (so the model knows what kind of output is expected)
 *   - Success criteria (guides the model toward what satisfies the intent)
 *   - Budget and SLA context (model can self-moderate response length/depth)
 *   - Tenant context (if allowed by compliance policy)
 *
 * This is injected into each provider adapter — adapters call
 * PromptBuilder.buildMessages(intent) rather than reading
 * objective.getDescription() directly.
 */
public final class PromptBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PromptBuilder() {}

    /**
     * Build the full messages array for Chat-style APIs
     * (OpenAI, Anthropic, DeepSeek, Azure).
     *
     * @return ArrayNode of {role, content} objects
     */
    public static ArrayNode buildMessages(Intent intent) {
        String systemPrompt = buildSystemPrompt(intent);
        String userPrompt   = buildUserPrompt(intent.getObjective());

        ArrayNode messages = MAPPER.createArrayNode();
        messages.add(messageNode("system", systemPrompt));
        messages.add(messageNode("user",   userPrompt));
        return messages;
    }

    /**
     * Build Gemini-style contents array (parts-based structure).
     *
     * Gemini does not support a dedicated system role in basic API calls,
     * so we prepend the system context inside the user content block.
     *
     * @return ArrayNode of Gemini content objects
     */
    public static ArrayNode buildGeminiContents(Intent intent) {
        String systemPrompt = buildSystemPrompt(intent);
        String userPrompt   = buildUserPrompt(intent.getObjective());

        String combined = systemPrompt + "\n\n---\n\n" + userPrompt;

        ArrayNode parts = MAPPER.createArrayNode();
        parts.add(MAPPER.createObjectNode().put("text", combined));

        ObjectNode contentNode = MAPPER.createObjectNode();
        contentNode.set("parts", parts);

        ArrayNode contents = MAPPER.createArrayNode();
        contents.add(contentNode);
        return contents;
    }

    // ── Internal builders ─────────────────────────────────────────────────────

    private static String buildSystemPrompt(Intent intent) {
        IntentObjective objective = intent.getObjective();
        StringBuilder   sb       = new StringBuilder();

        sb.append("You are a precise AI assistant operating within a governed AI control plane.\n\n");

        // Task type framing
        String taskType = objective.getTaskType();
        if (taskType != null && !taskType.isBlank()) {
            sb.append("Task type: ").append(taskType).append("\n");
        }

        // Success criteria
        List<String> criteria = objective.getSuccessCriteria();
        if (criteria != null && !criteria.isEmpty()) {
            sb.append("\nSuccess criteria — your response must satisfy ALL of the following:\n");
            criteria.forEach(c -> sb.append("  - ").append(c).append("\n"));
        }

        // Budget context — encourage conciseness when budget is tight
        if (intent.getBudget() != null) {
            double remaining = intent.getBudget().remaining();
            if (remaining < 0.02) {
                sb.append("\nIMPORTANT: Remaining budget is very limited. Be concise and direct.\n");
            }
        }

        // SLA context — if constraints have a timeout, guide the model
        if (intent.getConstraints() != null) {
            long slaMs = intent.getConstraints().timeoutSeconds() * 1000L;
            if (slaMs > 0 && slaMs < 5000) {
                sb.append("\nIMPORTANT: This request requires a very fast response. Keep your answer brief.\n");
            }
        }

        sb.append("\nRespond only with what is asked. Do not add unsolicited commentary.");
        return sb.toString().trim();
    }

    private static String buildUserPrompt(IntentObjective objective) {
        String description = objective.getDescription();
        String context     = objective.getContext();

        if (context != null && !context.isBlank()) {
            return "Context:\n" + context + "\n\nRequest:\n" + description;
        }
        return description;
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private static ObjectNode messageNode(String role, String content) {
        return MAPPER.createObjectNode()
                .put("role",    role)
                .put("content", content);
    }
}