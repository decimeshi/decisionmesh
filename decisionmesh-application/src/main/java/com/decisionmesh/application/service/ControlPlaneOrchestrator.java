package com.decisionmesh.application.service;

import com.decisionmesh.application.exception.DuplicateSubmissionException;
import com.decisionmesh.application.exception.LockExtensionFailedException;
import com.decisionmesh.application.exception.PolicyViolationException;
import com.decisionmesh.application.exception.RateLimitExceededException;
import com.decisionmesh.application.idempotency.IdempotencyService;
import com.decisionmesh.application.lock.LockManager;
import com.decisionmesh.application.lock.LockToken;
import com.decisionmesh.application.policy.PolicyEvaluationResult;
import com.decisionmesh.application.port.*;
import com.decisionmesh.application.port.ExecutionRecordQueryPort;
import com.decisionmesh.application.ratelimit.RateLimiter;
import com.decisionmesh.application.reconciliation.ReconciliationService;
import com.decisionmesh.application.telemetry.TelemetryPublisher;
import com.decisionmesh.contracts.security.guard.GuardPromptInjectionService;
import com.decisionmesh.contracts.security.service.PiiMaskingGuardService;
import com.decisionmesh.domain.event.DomainEvent;
import com.decisionmesh.domain.execution.ExecutionRecord;
import com.decisionmesh.domain.intent.Intent;
import com.decisionmesh.domain.intent.SatisfactionState;
import com.decisionmesh.billing.service.CreditLedgerService;
import com.decisionmesh.application.port.OutboxPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.decisionmesh.domain.plan.Plan;
import com.decisionmesh.governance.service.LedgerAppendService;
import com.decisionmesh.governance.snapshot.PolicySnapshot;
import com.decisionmesh.governance.policy.PolicyDecision;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Central orchestrator for the intent processing pipeline.
 *
 * Pipeline:  idempotency → rate limit → distributed lock → workflow
 *
 * Workflow:  PRE_SUBMISSION policy
 *            → budget validation
 *            → persist (CREATED)           [+ drain events]
 *            → PLANNING                    [+ drain events]
 *            → injection guard             [blocks CRITICAL injections]
 *            → plan + persist plan
 *            → PLANNED                     [+ drain events]
 *            → EXECUTING                   [+ drain events]
 *            → execution with governance + retry
 *            → EVALUATING                  [+ drain events]
 *            → quality scoring             [scores response, detects hallucination]
 *            → post-execution policy
 *            → drift scoring               [async, replaces computeDriftScore stub]
 *            → SATISFIED | VIOLATED        [+ drain events]
 */
@ApplicationScoped
public class ControlPlaneOrchestrator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ─── Dependencies ────────────────────────────────────────────────────────

    @Inject Planner                    planner;
    @Inject ExecutionEngine            executionEngine;
    @Inject PolicyEngine               policyEngine;
    @Inject LearningEngine             learningEngine;
    @Inject BudgetGuard                budgetGuard;
    @Inject IntentCentricSLAGuard      slaGuard;
    @Inject RateLimiter                rateLimiter;
    @Inject IntentRepositoryPort       intentRepository;
    @Inject ExecutionRepositoryPort    executionRepository;
    @Inject ExecutionRecordQueryPort   executionRecordRepository;  // SQL persistence for Decision Output card
    @Inject IntentEventRepositoryPort  eventRepository;
    @Inject LockManager                lockManager;
    @Inject IdempotencyService         idempotencyService;
    @Inject TelemetryPublisher         telemetry;
    @Inject ReconciliationService      reconciliationService;
    @Inject PlanRepositoryPort         planRepository;
    @Inject ViolationHandler violationHandler;

    // ── New services ──────────────────────────────────────────────────────────
    @Inject
    GuardPromptInjectionService injectionGuard;   // decisionmesh-security
    @Inject
    PiiMaskingGuardService piiGuard;
    @Inject OutputQualityScorerService  qualityScorer;    // decisionmesh-intelligence
    @Inject CreditLedgerService         creditLedger;     // decisionmesh-billing
    @Inject LedgerAppendService         ledgerAppend;     // decisionmesh-governance
    @Inject DriftEvaluatorService       driftEvaluator;   // decisionmesh-intelligence
    @Inject OutboxPort                   outboxPort;       // transactional outbox — impl in decisionmesh-streaming

    // ─── Configuration ───────────────────────────────────────────────────────

    @ConfigProperty(name = "controlplane.lock.intent-ttl-minutes",    defaultValue = "5")
    int lockTtlMinutes;

    @ConfigProperty(name = "controlplane.lock.max-retries",           defaultValue = "5")
    int lockMaxRetries;

    @ConfigProperty(name = "controlplane.lock.initial-backoff-ms",    defaultValue = "100")
    int lockInitialBackoffMs;

    @ConfigProperty(name = "controlplane.lock.extend-threshold-ms",   defaultValue = "60000")
    long lockExtendThresholdMs;

    // ─── Public API ──────────────────────────────────────────────────────────

    /**
     * Submit a new intent to the control plane.
     * @Transactional removed: JTA transactions are thread-bound and incompatible
     * with Mutiny's event-loop threading model.
     */
    public Uni<UUID> submit(Intent intent,
                            UUID tenantId,
                            String idempotencyKey,
                            String intentType) {

        Log.infof("Intent submission: id=%s, tenant=%s, type=%s, idempotency=%s",
                intent.getId(), tenantId, intentType, idempotencyKey);

        return idempotencyService.checkAndRegister(tenantId, idempotencyKey)
                .invoke(allowed -> {
                    if (!allowed) throw new DuplicateSubmissionException(
                            "Duplicate request: " + idempotencyKey);
                })
                .flatMap(v -> rateLimiter.enforce(tenantId, intentType))
                .invoke(ok -> {
                    if (!ok) throw new RateLimitExceededException(
                            "Rate limit exceeded for tenant: " + tenantId);
                })
                .flatMap(v -> {
                    if (intent.getId() == null) intent.setId(UUID.randomUUID());
                    return processIntentWithLock(intent, tenantId);
                })
                .replaceWith(intent.getId());
    }


    /**
     * Retrieve intent by ID, scoped to tenant.
     */
    public Uni<Intent> getById(UUID tenantId, UUID intentId) {
        Log.debugf("Fetching intent: id=%s, tenant=%s", intentId, tenantId);
        return intentRepository.findById(tenantId, intentId);
    }

    // ─── Lock coordination ───────────────────────────────────────────────────

    private Uni<Void> processIntentWithLock(Intent intent, UUID tenantId) {

        String partitionKey = buildPartitionKey(intent, tenantId);

        Log.infof("Acquiring lock: intent=%s, tenant=%s, partition=%s",
                intent.getId(), tenantId, partitionKey);

        return lockManager.exists(partitionKey)
                .invoke(exists -> Log.infof("Lock pre-check: partition=%s, alreadyExists=%s", partitionKey, exists))
                .flatMap(ignored -> lockManager.acquireWithRetry(partitionKey,
                        Duration.ofMinutes(lockTtlMinutes), lockMaxRetries,
                        Duration.ofMillis(lockInitialBackoffMs)
                ))
                .flatMap(lockToken -> {
                    Log.infof("Lock acquired: partition=%s, intent=%s",
                            partitionKey, intent.getId());
                    return processIntentWorkflow(intent, lockToken)
                            .onTermination().call(() -> {
                                Log.infof("onTermination fired — releasing lock: partition=%s",
                                        partitionKey);
                                return releaseLockSafely(lockToken);
                            });
                })
                .onFailure().invoke(ex ->
                        Log.errorf(ex, "Intent processing failed: id=%s, partition=%s",
                                intent.getId(), partitionKey));
    }

    // ─── Workflow — full state machine ───────────────────────────────────────

    private Uni<Void> processIntentWorkflow(Intent intent, LockToken lockToken) {

        // Capture event loop context before the pipeline starts.
        // executionEngine.execute() uses runSubscriptionOn(workerPool) internally.
        // After it completes all subsequent operators run on the worker thread.
        // We use this context to switch back to the event loop after execution.
        final io.vertx.core.Context eventLoopCtx = io.vertx.core.Vertx.currentContext();
        final java.util.concurrent.Executor eventLoopExec = runnable -> {
            if (eventLoopCtx != null) {
                eventLoopCtx.runOnContext(v -> runnable.run());
            } else {
                runnable.run();
            }
        };

        // Capture the scored ExecutionRecord after quality scoring so it can
        // be persisted to execution_records inside persistFinalState.
        final ExecutionRecord[] recordHolder = new ExecutionRecord[1];

        return policyEngine.evaluatePreSubmission(intent)
                .invoke(this::assertPolicyAllowed)
                .flatMap(decision -> ledgerAppend.appendDecision(
                        intent.getId(),
                        intent.getTenantId().toString(),
                        "PRE_SUBMISSION_POLICY",
                        toSnapshot(decision), null, null).replaceWith(decision))

                // Budget validation
                .flatMap(v -> budgetGuard.validateBudget(intent))

                // ── CREATED ──────────────────────────────────────────────────
                .flatMap(v -> intentRepository.save(intent))
                .flatMap(v -> drainEvents(intent))

                // ── PLANNING ──────────────────────────────────────────────────
                .flatMap(v -> {
                    intent.startPlanning();
                    return intentRepository.save(intent);
                })
                .flatMap(v -> drainEvents(intent))

                // ── INJECTION GUARD ───────────────────────────────────────────
                .flatMap(v -> runInjectionGuard(intent))

                // Plan
                .flatMap(v -> planner.plan(intent))
                .flatMap(plan -> planRepository.save(plan).replaceWith(plan))

                // ── PLANNED ───────────────────────────────────────────────────
                .flatMap(plan -> {
                    intent.markPlanned();
                    return intentRepository.save(intent)
                            .flatMap(v -> drainEvents(intent))
                            .replaceWith(plan);
                })

                // ── EXECUTING ─────────────────────────────────────────────────
                .flatMap(plan -> {
                    intent.markExecuting();
                    return intentRepository.save(intent)
                            .flatMap(v -> drainEvents(intent))
                            .replaceWith(plan);
                })

                // Execute
                .flatMap(plan -> executeWithGovernanceAndRetry(intent, plan, lockToken, 1, eventLoopCtx))

                // ── RETURN TO EVENT LOOP ──────────────────────────────────────
                // executionEngine.execute() uses runSubscriptionOn(workerPool) for
                // the blocking HTTP call to the LLM. After it completes, the pipeline
                // is on a worker thread. Switch back to the event loop so all
                // subsequent Hibernate Reactive calls (intentRepository.save) work.
                .emitOn(eventLoopExec)

                // ── EVALUATING ────────────────────────────────────────────────
                .flatMap(record -> {
                    intent.markEvaluating();
                    return intentRepository.save(intent)
                            .flatMap(v -> drainEvents(intent))
                            .replaceWith(record);
                })

                // ── QUALITY SCORING ───────────────────────────────────────────
                .flatMap(record -> scoreQuality(intent, record))

                // ── CAPTURE RECORD ────────────────────────────────────────────
                // Store the scored record so persistFinalState can save it to
                // execution_records via JDBC after the intent is written to DB.
                .invoke(record -> recordHolder[0] = record)

                // Post-execution policy (receives the scored record)
                .flatMap(record ->
                        policyEngine.evaluatePostExecution(intent, record)
                                .invoke(this::assertPolicyAllowed)
                                .flatMap(decision -> ledgerAppend.appendDecision(
                                        intent.getId(),
                                        intent.getTenantId().toString(),
                                        "POST_EXECUTION_POLICY",
                                        toSnapshot(decision), null, null).replaceWith(decision))
                                .replaceWith(record))

                // ── SATISFIED | VIOLATED (with async drift) ───────────────────
                .flatMap(record -> finalizeIntent(intent, record))

                // Persist final state + execution record + remaining events
                .flatMap(v -> persistFinalState(intent, recordHolder[0]))

                // ── VIOLATION HANDLER ─────────────────────────────────────────
                .onFailure(ViolationHandler::isViolation)
                .recoverWithUni(ex -> violationHandler.handleViolation(intent, ex));
    }

    // ─── Injection guard ─────────────────────────────────────────────────────

    /**
     * Scan the intent payload for prompt injection patterns.
     * Returns voidItem on clean or high-risk (high-risk only flags the intent).
     * Throws PolicyViolationException on CRITICAL — stops the pipeline and
     * marks the intent VIOLATED via the existing failure handler.
     */
    private Uni<Void> runInjectionGuard(Intent intent) {
        GuardPromptInjectionService.ScanResult scan = injectionGuard.scan(intent);

        if (scan.isCritical()) {
            Log.warnf("[InjectionGuard] CRITICAL injection blocked: intent=%s risk=%.2f matches=%d",
                    intent.getId(), scan.injectionRisk(), scan.matches().size());
            // Reuse PolicyViolationException — caught by the existing failure handler
            // which marks the intent VIOLATED and drains events
            throw new PolicyViolationException(
                    "INJECTION_GUARD",
                    String.format("Prompt injection blocked (risk=%.2f, severity=%s)",
                            scan.injectionRisk(), scan.severity()));
        }

        // ── PII MASKING ──────────────────────────────────────────────────────────
// Runs after injection guard, before planning.
// If PII is detected, the masked payload is stored on the intent via
// flagPiiDetected(). The planner and execution engine must call
// intent.getMaskedObjective() instead of intent.getObjective() when
// building the LLM prompt — see Change 4 below.
        PiiMaskingGuardService.MaskingResult pii = piiGuard.scanAndMask(intent);
        if (pii.piiDetected()) {
            intent.flagPiiDetected(pii.maskedPayload());
            Log.infof("[PiiGuard] %d PII match(es) masked in intent %s before planning",
                    pii.matches().size(), intent.getId());
            if (pii.hasHighRisk()) {
                // HIGH severity: Aadhaar / PAN / card number detected.
                // Log at WARN — the policy engine's requireHumanReview constraint
                // on the intent already handles routing to review queue if configured.
                // No separate action needed here unless you want to force
                // human review regardless of the intent's own constraints.
                Log.warnf("[PiiGuard] HIGH severity PII in intent %s — Aadhaar/PAN/card detected",
                        intent.getId());
            }
        }

        if (scan.isHighRisk()) {
            Log.warnf("[InjectionGuard] HIGH-RISK injection flagged: intent=%s risk=%.2f — continuing",
                    intent.getId(), scan.injectionRisk());
            // Store on intent so the policy engine can evaluate injectionRisk rules
            intent.flagInjectionRisk(BigDecimal.valueOf(scan.injectionRisk()));
        }

        return Uni.createFrom().voidItem();
    }




    // ─── Quality scoring ──────────────────────────────────────────────────────

    /**
     * Score the quality of the adapter response in the EVALUATING phase.
     * Returns a new ExecutionRecord with quality fields populated via withQuality().
     * The original record is not mutated (ExecutionRecord is immutable).
     *
     * If scoring fails or response text is missing, the original record is
     * returned unchanged — quality scoring is non-blocking for the pipeline.
     */
    private Uni<ExecutionRecord> scoreQuality(Intent intent, ExecutionRecord record) {
        Uni<OutputQualityScorerService.QualityScore> scoreUni = qualityScorer.score(intent, record);
        return scoreUni
                .onFailure().invoke(ex ->
                        Log.warnf("[Quality] Scoring failed for intent=%s — using unscored record: %s",
                                intent.getId(), ex.getClass().getSimpleName()))
                .onFailure().recoverWithItem(
                        OutputQualityScorerService.QualityScore.skipped("Scorer error — fallback to unscored"))
                .map(quality -> {
                    if ("SKIPPED".equals(quality.method()) || "ERROR".equals(quality.method())) {
                        return record;  // pass through unchanged on error
                    }
                    ExecutionRecord scored = record.withQuality(
                            BigDecimal.valueOf(quality.overallDouble()),
                            BigDecimal.valueOf(quality.hallucinationRiskDouble()),
                            quality.hallucinationDetected(),
                            quality.reasoning()
                    );
                    Log.infof("[Quality] intent=%s overall=%.2f hallucination=%.2f flagged=%s method=%s",
                            intent.getId(),
                            quality.overallDouble(),
                            quality.hallucinationRiskDouble(),
                            quality.hallucinationDetected(),
                            quality.method());
                    return scored;
                });
    }

    // ─── Execution with governance, SLA, and retry ───────────────────────────

    private Uni<ExecutionRecord> executeWithGovernanceAndRetry(
            Intent intent,
            Plan plan,
            LockToken lockToken,
            int attemptNumber,
            io.vertx.core.Context eventLoopCtx) {

        Log.debugf("Execution attempt %d/%d: intent=%s, plan=%s",
                attemptNumber, intent.getMaxRetries(), intent.getId(), plan.getPlanId());

        return extendLockIfNeeded(lockToken)

                .flatMap(v -> slaGuard.validateBeforeExecution(intent))

                .flatMap(v -> policyEngine.evaluatePreExecution(intent))
                .invoke(this::assertPolicyAllowed)

                .flatMap(v -> executionEngine.execute(plan, attemptNumber))

                .flatMap(record ->
                        executionRepository.append(record)
                                .replaceWith(record))

                // ── PERSIST TO SQL IMMEDIATELY AFTER ADAPTER RETURNS ──────────
                // Must happen BEFORE slaGuard.validateAfterExecution() which can
                // throw SLAException (latency exceeded, retry budget exhausted).
                // If we wait until persistFinalState(), VIOLATED intents never
                // reach it — recordHolder[0] stays null — and no row is written.
                // Saving here guarantees execution_records has a row regardless
                // of whether the intent ultimately SATISFIES or VIOLATES.
                .invoke(record -> {
                    final ExecutionRecord r = record;
                    executionRecordRepository.persistBlocking(r, intent);
                    Log.infof("[ExecRecord] Saved after execution: intent=%s adapter=%s status=%s", intent.getId(), r.getAdapterId(), r.isSuccess() ? "SUCCESS" : "FAILED");
                })

                .flatMap(record -> slaGuard.validateAfterExecution(intent, record).replaceWith(record))

                .onFailure().recoverWithUni(ex ->
                        handleExecutionFailure(intent, plan, lockToken, attemptNumber, ex, eventLoopCtx));
    }

    private Uni<ExecutionRecord> handleExecutionFailure(
            Intent intent,
            Plan plan,
            LockToken lockToken,
            int attemptNumber,
            Throwable ex,
            io.vertx.core.Context eventLoopCtx) {

        Log.warnf(ex, "Execution attempt %d failed: intent=%s", attemptNumber, intent.getId());

        // eventLoopCtx was captured at HTTP request start — it IS a duplicated context,
        // safe for Hibernate Reactive @WithTransaction.
        final java.util.concurrent.Executor eventLoopExec = runnable -> {
            if (eventLoopCtx != null) {
                eventLoopCtx.runOnContext(v -> runnable.run());
            } else {
                runnable.run();
            }
        };

        if (attemptNumber >= intent.getMaxRetries()) {
            Log.errorf("Retries exhausted (%d/%d): intent=%s",
                    attemptNumber, intent.getMaxRetries(), intent.getId());
            intent.markViolated();
            return Uni.createFrom().voidItem()
                    .emitOn(eventLoopExec)
                    .flatMap(v -> intentRepository.save(intent))
                    .flatMap(v -> drainEvents(intent))
                    .flatMap(v -> Uni.createFrom().failure(ex));
        }

        intent.scheduleRetry();
        intent.resumeExecution();

        // Debit 1 credit — fire-and-forget, non-fatal.
        // Must run on the duplicated event loop context for @WithTransaction.
        // Schedule via runOnContext to guarantee the correct context.
        if (eventLoopCtx != null) {
            eventLoopCtx.runOnContext(v ->
                    creditLedger.debitRetry(intent.getTenantId(), intent.getId(), 1)
                            .onFailure().invoke(ce ->
                                    Log.warnf("[Credits] Retry debit failed for intent=%s — non-fatal",
                                            intent.getId()))
                            .onFailure().recoverWithNull()
                            .subscribe().with(
                                    ok  -> Log.debugf("[Credits] Retry debit ok: intent=%s", intent.getId()),
                                    err -> Log.warnf("[Credits] Retry debit error: intent=%s", intent.getId()))
            );
        }

        return Uni.createFrom().voidItem()
                .emitOn(eventLoopExec)
                .flatMap(v -> intentRepository.save(intent))
                .flatMap(v -> drainEvents(intent))
                .flatMap(v -> executeWithGovernanceAndRetry(
                        intent, plan, lockToken, attemptNumber + 1, eventLoopCtx));
    }

    // ─── Lock lifecycle ───────────────────────────────────────────────────────

    private Uni<Void> extendLockIfNeeded(LockToken lockToken) {
        long remainingMs = lockToken.remainingMillis();

        if (remainingMs >= lockExtendThresholdMs) {
            return Uni.createFrom().voidItem();
        }

        Log.infof("Extending lock: partition=%s, remaining=%dms",
                lockToken.partitionKey(), remainingMs);

        return lockManager.extend(lockToken, Duration.ofMinutes(2))
                .flatMap(extended -> {
                    if (!extended) {
                        return Uni.createFrom().failure(
                                new LockExtensionFailedException(
                                        "Failed to extend lock: " + lockToken.partitionKey()));
                    }
                    Log.infof("Lock extended: partition=%s", lockToken.partitionKey());
                    return Uni.createFrom().voidItem();
                });
    }

    private Uni<Void> releaseLockSafely(LockToken lockToken) {
        return lockManager.release(lockToken)
                .invoke(released -> {
                    if (released) {
                        Log.infof("Lock released: partition=%s, held_for=%dms",
                                lockToken.partitionKey(),
                                System.currentTimeMillis() - lockToken.acquiredAt().toEpochMilli());
                    } else {
                        Log.warnf("Lock already expired or stolen: partition=%s",
                                lockToken.partitionKey());
                    }
                })
                .onFailure().invoke(ex ->
                        Log.errorf(ex, "Lock release error (non-fatal): partition=%s",
                                lockToken.partitionKey()))
                .onFailure().recoverWithItem(false)
                .replaceWithVoid();
    }

    // ─── Finalization ─────────────────────────────────────────────────────────

    /**
     * Transition to SATISFIED or VIOLATED.
     * intent.phase must be EVALUATING when this is called.
     *
     * Drift scoring is now async via DriftEvaluatorService — the stub
     * computeDriftScore() returning BigDecimal.ZERO is replaced.
     * Drift is non-blocking: a failure in drift scoring logs a warning
     * and falls back to 0.0 rather than failing the pipeline.
     */
    private Uni<Void> finalizeIntent(Intent intent, ExecutionRecord executionRecord) {

        // ── VIOLATED — no drift scoring needed ───────────────────────────────
        if (!executionRecord.isSuccess()) {
            intent.markViolated();
            Log.warnf("Intent VIOLATED: id=%s, reason=%s",
                    intent.getId(), executionRecord.getFailureReason());
            return Uni.createFrom().voidItem();
        }

        // ── SATISFIED — compute drift via Ollama then finalize ────────────────
        return driftEvaluator.computeDriftScore(intent, executionRecord)
                .onFailure().invoke(ex ->
                        Log.warnf("[Drift] Ollama embedding failed for intent=%s — using 0.0: %s",
                                intent.getId(), ex.getMessage()))
                .onFailure().recoverWithItem(BigDecimal.ZERO)
                .flatMap(driftScore -> {

                    intent.updateDriftScore(driftScore, executionRecord.getExecutionId());

                    // ── HUMAN REVIEW INTERCEPTION ─────────────────────────────
                    // If the intent payload has "requireHumanReview":true,
                    // park the intent in PENDING_REVIEW state instead of SATISFIED.
                    // The human reviewer will approve/reject via ReviewQueueResource.
                    if (requiresHumanReview(intent)) {
                        Log.infof("[ReviewQueue] Intent parked for human review: id=%s",
                                intent.getId());
                        intent.markPendingReview();    // phase=REVIEWING, satisfactionState=PENDING_REVIEW, terminal=false
                        return Uni.createFrom().voidItem();
                    }

                    intent.markSatisfied();

                    // ── Log summary ───────────────────────────────────────────
                    double costVal    = executionRecord.getCost().doubleValue();
                    double driftVal   = driftScore.doubleValue();
                    int    attempts   = executionRecord.getAttemptNumber();
                    String qualityStr = executionRecord.isQualityScored()
                            ? String.format("%.2f", executionRecord.getQualityScore().doubleValue())
                            : "unscored";
                    boolean cacheHit  = executionRecord.isCacheHit();       // from fixed ExecutionRecord
                    int cacheRead     = executionRecord.getCacheReadTokens(); // from fixed ExecutionRecord

                    Log.infof("Intent SATISFIED: id=%s cost=$%.6f drift=%.4f attempts=%d quality=%s cache=%s(%dtokens)",
                            intent.getId(), costVal, driftVal, attempts, qualityStr,
                            cacheHit ? "HIT" : "MISS", cacheRead);

                    // ── Fire drift alert if threshold exceeded ────────────────
                    // IntentConstraints is a record — use maxDriftThreshold() NOT getMaxDriftThreshold()
                    boolean driftExceeded = intent.getConstraints() != null
                            && intent.getConstraints().hasDriftLimit()   // convenience method on record
                            && driftScore.compareTo(
                            BigDecimal.valueOf(
                                    intent.getConstraints().maxDriftThreshold())) > 0; // ← record accessor

                    if (driftExceeded) {
                        Log.warnf("[Drift] THRESHOLD EXCEEDED: intent=%s drift=%.4f threshold=%.4f",
                                intent.getId(), driftVal,
                                intent.getConstraints().maxDriftThreshold()); // ← record accessor

                        return telemetry.publishDriftAlert(
                                        intent.getTenantId(),
                                        intent.getId(),
                                        executionRecord.getAdapterId(),
                                        driftScore)
                                .onFailure().invoke(ex ->
                                        Log.warnf("[Drift] Alert publish failed for intent=%s — non-fatal",
                                                intent.getId()))
                                .onFailure().recoverWithNull()
                                .replaceWithVoid();
                    }

                    return Uni.createFrom().voidItem();
                });
    }

    /**
     * Persist terminal state: intent + execution record + domain events + learning + telemetry.
     *
     * The execution record is saved via blocking JDBC on the worker pool AFTER
     * the intent is saved via Hibernate Reactive. Keeping them sequential avoids
     * the HR000068 thread conflict — intent goes first (event loop), then execution
     * record (worker pool via persistBlocking).
     */
    private Uni<Void> persistFinalState(Intent intent, ExecutionRecord executionRecord) {
        return intentRepository.save(intent)
                .flatMap(v -> drainEvents(intent))
                .flatMap(v -> learningEngine.updateProfiles(intent.getId()))
                .flatMap(v -> publishTelemetry(intent))
                // ── DEBIT CREDITS ─────────────────────────────────────────────
                // 1 credit per execution attempt (Economy tier default).
                // Runs after intent is saved so failure here doesn't roll back
                // the intent state. Fire-and-forget on failure — credit debit
                // failing should never block the intent completion response.
                .flatMap(v -> creditLedger.debitExecution(
                                intent.getTenantId(),
                                intent.getId(),
                                resolveTierCredits(intent))
                        .onFailure().invoke(ex ->
                                Log.errorf(ex, "[Credits] Debit failed for intent=%s — non-fatal",
                                        intent.getId()))
                        .onFailure().recoverWithNull())
                // ── UPDATE QUALITY SCORE IN DB ────────────────────────
                // ExecutionRecordRepository.persistBlocking() saves the record BEFORE
                // quality scoring runs. Update the quality fields now via JDBC.
                .flatMap(v -> {
                    if (executionRecord != null && executionRecord.getQualityScore() != null) {
                        Log.debugf("[Quality] Updating quality score for intent=%s score=%s",
                                intent.getId(), executionRecord.getQualityScore());
                        return executionRecordRepository.updateQualityScore(
                                        executionRecord.getExecutionId(),
                                        intent.getTenantId(),
                                        executionRecord.getQualityScore(),
                                        executionRecord.getHallucinationRisk(),
                                        Boolean.TRUE.equals(executionRecord.getHallucinationDetected()),
                                        executionRecord.getQualityReasoning())
                                .onFailure().invoke(qex ->
                                        Log.warnf(qex, "[Quality] Failed to update quality score for intent=%s — non-fatal",
                                                intent.getId()))
                                .onFailure().recoverWithNull()
                                .replaceWithVoid();
                    }
                    Log.debugf("[Quality] No quality score to update for intent=%s (record=%s)",
                            intent.getId(), executionRecord != null ? "present but unscored" : "null");
                    return Uni.createFrom().voidItem();
                })
                // ── APPEND TERMINAL LEDGER ENTRY ──────────────────────────────
                // Records the final SATISFIED or VIOLATED decision in the governance
                // ledger so Replay shows the terminal outcome alongside the policy
                // evaluations. Non-fatal — a failure here must not roll back the
                // already-persisted intent state.
                .flatMap(v -> {
                    String eventType = intent.getSatisfactionState() == SatisfactionState.VIOLATED
                            ? "INTENT_VIOLATED" : "INTENT_SATISFIED";
                    Log.debugf("[Ledger] Appending terminal entry: intent=%s event=%s",
                            intent.getId(), eventType);
                    return ledgerAppend.appendDecision(
                                    intent.getId(),
                                    intent.getTenantId().toString(),
                                    eventType,
                                    null, null, null)
                            .onFailure().invoke(ex ->
                                    Log.warnf("[Ledger] Terminal append failed for intent=%s — non-fatal: %s",
                                            intent.getId(), ex.getMessage()))
                            .onFailure().recoverWithNull()
                            .replaceWithVoid();
                });
    }

    /**
     * Converts a PolicyEvaluationResult to a PolicySnapshot for ledger storage.
     * Maps PolicyEvaluation.Decision → PolicyDecision (allowed/denied + reason)
     * so Replay can show which policy ran and what decision was made.
     */
    private PolicySnapshot toSnapshot(PolicyEvaluationResult result) {
        if (result == null) return null;
        PolicyDecision decision = result.isBlocking()
                ? PolicyDecision.deny(result.getBlockReason())
                : PolicyDecision.allow();
        return new PolicySnapshot(
                decision,
                result.getPolicyId(),   // policyVersion
                "ENFORCE",              // enforcementMode
                null,                   // evaluatedContextJson
                null                    // plan — not available at orchestrator level
        );
    }

    /**
     * Determines how many credits to debit per execution based on the adapter
     * and model used. Economy=1, Standard=5, Premium=25.
     * Defaults to 1 (Economy) — the cheapest tier for mock/unknown adapters.
     */
    private int resolveTierCredits(Intent intent) {
        String tier = intent.getModelTier();
        if (tier == null) return 1;
        return switch (tier.toLowerCase()) {
            case "standard" -> 5;
            case "premium"  -> 25;
            default         -> 1; // economy / byok / byom
        };
    }

    // ─── Utilities ────────────────────────────────────────────────────────────

    /**
     * Drain domain events from the intent aggregate root.
     *
     * Transactional Outbox pattern — two-phase write:
     *   Phase 1: intent_events via Hibernate Reactive (drives UI execution timeline)
     *   Phase 2: event_outbox  via OutboxPort → ReactiveOutboxRepository (async Kafka relay)
     *
     * Outbox failure is non-fatal — intent_events is the source of truth for the UI.
     * KafkaOutboxPublisher polls event_outbox every 5s and relays to projection workers.
     */
    private Uni<Void> drainEvents(Intent intent) {
        List<DomainEvent> events = intent.pullDomainEvents();
        if (events.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        return eventRepository.appendAll(events)
                .flatMap(v -> outboxPort.publish(events)
                        .onFailure().invoke(ex ->
                                Log.warnf("[Outbox] Write failed for intent=%s (non-fatal): %s",
                                        intent.getId(), ex.getMessage()))
                        .onFailure().recoverWithNull()
                        .replaceWithVoid());
    }

    private String buildPartitionKey(Intent intent, UUID tenantId) {
        return String.format("%s:%s", tenantId, intent.getId());
    }

    private void assertPolicyAllowed(PolicyEvaluationResult evaluation) {
        if (evaluation.isBlocking()) {
            throw new PolicyViolationException(
                    evaluation.getPolicyId(),
                    evaluation.getBlockReason());
        }
        if (evaluation.isWarning()) {
            Log.warnf("Policy warning: policy=%s, detail=%s",
                    evaluation.getPolicyId(),
                    evaluation.getEvaluationDetail());
        }
    }

    private Uni<Void> publishTelemetry(Intent intent) {
        return telemetry.publish(
                intent.getPhase(),
                intent.getTenantId(),
                intent.getId(),
                intent.getVersion());
    }
    // ── Human review helper ───────────────────────────────────────────────────

    /**
     * Returns true if the intent payload contains "requireHumanReview":true.
     * Checked after quality scoring, before markSatisfied().
     */
    private boolean requiresHumanReview(Intent intent) {
        return intent.getConstraints() != null
                && intent.getConstraints().requireHumanReview();
    }
}