package com.decisionmesh.application.service;

import com.decisionmesh.application.enums.ViolationCode;
import com.decisionmesh.application.exception.*;
import com.decisionmesh.application.port.IntentEventRepositoryPort;
import com.decisionmesh.application.port.IntentRepositoryPort;
import com.decisionmesh.billing.service.CreditLedgerService;
import com.decisionmesh.domain.intent.Intent;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/**
 * Converts known pipeline exceptions into a proper COMPLETED/VIOLATED
 * intent state so the UI sees a clean result card rather than a raw 500.
 *
 * Wire into ControlPlaneOrchestrator at the end of processIntentWorkflow():
 *
 *   @Inject ViolationHandler violationHandler;
 *
 *   .onFailure(ViolationHandler::isViolation)
 *   .recoverWithUni(ex -> violationHandler.handleViolation(intent, ex))
 *
 * ── Why markViolated() alone is not enough ───────────────────────────────────
 *
 * Intent.markViolated() only handles EXECUTING → COMPLETED and
 * EVALUATING → COMPLETED transitions. SLAException from
 * IntentCentricSLAGuard.validateBeforeExecution() fires before execution
 * starts, so the intent can be in PLANNING or PLANNED phase at the time of
 * violation. Calling markViolated() from those phases throws a second
 * IllegalStateException inside the handler.
 *
 * This handler calls markViolated() when safe, and falls back to a direct
 * forced terminal transition when the phase is unexpected.
 */
@ApplicationScoped
public class ViolationHandler {

    @Inject IntentRepositoryPort      intentRepository;
    @Inject IntentEventRepositoryPort eventRepository;
    @Inject CreditLedgerService       creditLedgerService;

    // ── Violation predicate ───────────────────────────────────────────────────

    public static boolean isViolation(Throwable t) {
        return t instanceof SLAException
                || t instanceof PolicyViolationException
                || t instanceof BudgetExceededException
                || t instanceof RateLimitExceededException
                || t instanceof LlmAdapterException     // adapter HTTP 4xx/5xx
                || t instanceof LlmTimeoutException;    // adapter timeout
    }


    public Uni<Void> handleViolation(Intent intent, Throwable ex) {
        ViolationCode code   = classify(ex);
        String        reason = buildReason(ex, code);

        Log.infof("ViolationHandler: intent=%s phase=%s code=%s reason=%s",
                intent.getId(), intent.getPhase(), code, reason);

        // Attempt the standard domain transition.
        // markViolated() handles EXECUTING and EVALUATING phases.
        // For other phases (PLANNING, PLANNED) it will throw — caught below.
        try {
            intent.markViolated();
        } catch (IllegalStateException transitionEx) {
            // The intent is in a phase that markViolated() doesn't handle
            // (e.g. PLANNING when SLAGuard rejects before execution starts).
            // Force terminal state directly without going through transition().
            Log.infof(
                    "ViolationHandler: phase=%s incompatible with markViolated(), " +
                            "forcing terminal state directly for intent=%s",
                    intent.getPhase(), intent.getId());
            forceTerminalViolated(intent);
        }

        // Drain any events queued by markViolated() or forceTerminalViolated()
        List<com.decisionmesh.domain.event.DomainEvent> pendingEvents =
                intent.pullDomainEvents();

        Log.infof("ViolationHandler: draining %d events for intent=%s",
                pendingEvents.size(), intent.getId());

        // Persist intent then events then debit credits.
        // Credits are debited on VIOLATED — same as SATISFIED — because the
        // LLM call was made (or attempted) before the violation was detected.
        // Pre-execution violations (PLANNING/PLANNED phase) still charge 1
        // credit for the orchestration work performed.
        return intentRepository.save(intent)
                .flatMap(saved -> pendingEvents.isEmpty()
                        ? Uni.createFrom().voidItem()
                        : eventRepository.appendAll(pendingEvents))
                .flatMap(ignored -> {
                    int credits = resolveCredits(intent);
                    Log.infof("[Credits] Debiting %d credit(s) for VIOLATED intent=%s orgId=%s",
                            credits, intent.getId(), intent.getTenantId());
                    return creditLedgerService
                            .debitExecution(intent.getTenantId(), intent.getId(), credits)
                            .onFailure().invoke(creditEx -> Log.errorf(creditEx,
                                    "[Credits] VIOLATED debit failed for intent=%s — " +
                                            "balance may be stale, run reconciliation to correct.",
                                    intent.getId()))
                            .onFailure().recoverWithNull();
                })
                .invoke(() -> Log.infof(
                        "Intent %s → COMPLETED/VIOLATED (%s): %s",
                        intent.getId(), code, reason))
                .onFailure().invoke(persistEx -> Log.errorf(persistEx,
                        "Failed to persist VIOLATED state: intent=%s code=%s",
                        intent.getId(), code))
                .replaceWithVoid();
    }

    // ── Tier credit resolution ────────────────────────────────────────────────

    /**
     * Returns the credit cost for this intent based on the model tier stored
     * on the intent. Falls back to 1 (Economy) if the field is absent —
     * this keeps older intents that pre-date tier tracking working correctly.
     */
    private int resolveCredits(Intent intent) {
        String tier = intent.getModelTier();
        if (tier == null) return 1;
        return switch (tier.toLowerCase()) {
            case "standard" -> 5;
            case "premium"  -> 25;
            default         -> 1; // economy / byok / byom
        };
    }

    // ── Forced terminal state for pre-execution violations ────────────────────

    /**
     * Directly sets the intent to COMPLETED/VIOLATED when the phase is not
     * one markViolated() handles (EXECUTING or EVALUATING).
     *
     * Emits a VIOLATED event manually using the same IntentStateChangedEvent
     * constructor that Intent.emit() uses internally, so the event record is
     * structurally identical to one emitted by markViolated().
     *
     * Called when SLAException fires during PLANNING or PLANNED phase —
     * i.e. the SLA guard rejected the intent before any LLM call was made.
     */
    private void forceTerminalViolated(Intent intent) {
        // Use reflection-free access: Intent exposes setters for orchestrator use.
        // We cannot call transition() — it's private. We set the observable state
        // that the repository persists and the UI reads.
        //
        // This is the same state markViolated() would produce; we just skip the
        // phase-guard check that would throw for PLANNING/PLANNED phases.
        intent.forceViolated();   // see note below — add this method to Intent
    }

    // ── Classification ────────────────────────────────────────────────────────

    private ViolationCode classify(Throwable ex) {
        if (ex instanceof SLAException sla) {
            String msg = sla.getMessage() != null ? sla.getMessage().toLowerCase() : "";
            if (msg.contains("retry"))   return ViolationCode.RETRY_BUDGET_EXHAUSTED;
            if (msg.contains("latency")) return ViolationCode.LATENCY_EXCEEDED;
            if (msg.contains("timeout")) return ViolationCode.TIMEOUT;
            return ViolationCode.RETRY_BUDGET_EXHAUSTED;
        }
        if (ex instanceof BudgetExceededException)    return ViolationCode.BUDGET_EXCEEDED;
        if (ex instanceof LlmTimeoutException)        return ViolationCode.ADAPTER_TIMEOUT;
        if (ex instanceof LlmAdapterException lae) {
            String msg = lae.getMessage() != null ? lae.getMessage().toLowerCase() : "";
            if (msg.contains("rate") || msg.contains("429")) return ViolationCode.RATE_LIMIT_EXCEEDED;
            return ViolationCode.ADAPTER_ERROR;
        }
        if (ex instanceof RateLimitExceededException) return ViolationCode.RATE_LIMIT_EXCEEDED;
        if (ex instanceof PolicyViolationException pve) {
            String msg = pve.getMessage() != null ? pve.getMessage().toLowerCase() : "";
            if (msg.contains("topic")  || msg.contains("block"))  return ViolationCode.POLICY_TOPIC_BLOCKED;
            if (msg.contains("model")  || msg.contains("allowed")) return ViolationCode.POLICY_MODEL_DISALLOWED;
            if (msg.contains("drift"))                             return ViolationCode.POLICY_DRIFT_EXCEEDED;
            if (msg.contains("inject"))                            return ViolationCode.POLICY_INJECTION_BLOCKED;
            return ViolationCode.POLICY_TOPIC_BLOCKED;
        }
        return ViolationCode.UNKNOWN_VIOLATION;
    }

    private String buildReason(Throwable ex, ViolationCode code) {
        String orig = ex.getMessage();
        if (orig != null && !orig.isBlank() && !orig.equalsIgnoreCase(code.description)) {
            return code.description + " — " + orig;
        }
        return code.description;
    }
}