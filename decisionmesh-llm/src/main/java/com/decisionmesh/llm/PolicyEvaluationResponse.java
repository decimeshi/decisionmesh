package com.decisionmesh.llm;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * DTO for a single policy evaluation row.
 * Served by GET /api/intents/{id}/policy-evaluations
 * Maps directly to the policy_evaluations table.
 *
 * Used by PolicyOutcomeCard in IntentDetail.jsx to show:
 *   - Overall verdict (ALLOWED / BLOCKED / WARNING)
 *   - Which policy blocked and why (blockReason)
 *   - Which phase it was evaluated in (PRE_EXECUTION / POST_EXECUTION)
 *   - Enforcement mode (LOG_ONLY / ENFORCE / AUDIT)
 *   - Attempt number (for retry scenarios)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PolicyEvaluationResponse(
        UUID    id,
        UUID    policyId,
        UUID    intentId,
        UUID    adapterId,
        String  result,           // ALLOWED | BLOCKED | WARNING
        String  phase,            // PRE_SUBMISSION | PRE_EXECUTION | POST_EXECUTION
        String  enforcementMode,  // LOG_ONLY | ENFORCE | AUDIT
        String  blockReason,      // null when result == ALLOWED
        Integer attemptNumber,
        Instant evaluatedAt
) {}

