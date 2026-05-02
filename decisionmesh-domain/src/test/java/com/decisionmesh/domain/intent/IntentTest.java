package com.decisionmesh.domain.intent;

import com.decisionmesh.domain.intent.value.IntentConstraints;
import com.decisionmesh.domain.intent.value.IntentObjective;
import com.decisionmesh.domain.intent.value.ObjectiveType;
import com.decisionmesh.domain.value.Budget;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class IntentTest {

    // ── Factory ───────────────────────────────────────────────────────────────

    private Intent createIntent() {
        return Intent.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "COST_OPTIMIZATION",
                IntentObjective.of(ObjectiveType.COST, 0.8, 0.1),
                IntentConstraints.of(3, 30),
                Budget.of(100.0)
        );
    }

    // ── State machine transition tests ────────────────────────────────────────

    @Test
    void shouldNotAllowInvalidTransition() {
        Intent intent = createIntent();
        // Phase is CREATED — markExecuting() requires PLANNED
        assertThrows(IllegalStateException.class, intent::markExecuting);
    }

    @Test
    void shouldTransitionThroughFullLifecycle() {
        Intent intent = createIntent();

        assertEquals(IntentPhase.CREATED, intent.getPhase());

        intent.startPlanning();
        assertEquals(IntentPhase.PLANNING, intent.getPhase());

        intent.markPlanned();
        assertEquals(IntentPhase.PLANNED, intent.getPhase());

        intent.markExecuting();
        assertEquals(IntentPhase.EXECUTING, intent.getPhase());

        intent.markEvaluating();
        assertEquals(IntentPhase.EVALUATING, intent.getPhase());

        intent.markSatisfied();
        assertEquals(IntentPhase.COMPLETED, intent.getPhase());
        assertEquals(SatisfactionState.SATISFIED, intent.getSatisfactionState());
        assertTrue(intent.isTerminal());
    }

    @Test
    void shouldTransitionToViolatedFromEvaluating() {
        Intent intent = createIntent();

        intent.startPlanning();
        intent.markPlanned();
        intent.markExecuting();
        intent.markEvaluating();
        intent.markViolated();

        assertEquals(IntentPhase.COMPLETED, intent.getPhase());
        assertEquals(SatisfactionState.VIOLATED, intent.getSatisfactionState());
        assertTrue(intent.isTerminal());
    }

    @Test
    void shouldAllowViolatedDirectlyFromExecuting() {
        // handleExecutionFailure() calls markViolated() when retries exhausted
        // before markEvaluating() has been called
        Intent intent = createIntent();

        intent.startPlanning();
        intent.markPlanned();
        intent.markExecuting();
        intent.markViolated(); // from EXECUTING — retry exhausted path

        assertEquals(IntentPhase.COMPLETED, intent.getPhase());
        assertEquals(SatisfactionState.VIOLATED, intent.getSatisfactionState());
    }

    // ── Retry tests ───────────────────────────────────────────────────────────

    @Test
    void shouldEnforceRetryFromExecutingOnly() {
        Intent intent = createIntent();
        // Phase is CREATED — scheduleRetry() only allowed from EXECUTING
        assertThrows(IllegalStateException.class, intent::scheduleRetry);
    }

    @Test
    void shouldScheduleRetryFromExecuting() {
        Intent intent = createIntent();

        intent.startPlanning();
        intent.markPlanned();
        intent.markExecuting();
        intent.scheduleRetry();

        assertEquals(IntentPhase.RETRY_SCHEDULED, intent.getPhase());
        assertEquals(1, intent.getRetryCount());
    }

    @Test
    void shouldEnforceMaxRetries() {
        // maxRetries = 3 from IntentConstraints.of(3, 30)
        // After scheduleRetry() phase = RETRY_SCHEDULED.
        // startPlanning() accepts both CREATED and RETRY_SCHEDULED (fixed in domain).
        Intent intent = createIntent();

        intent.startPlanning();
        intent.markPlanned();
        intent.markExecuting();
        intent.scheduleRetry(); // retryCount = 1
        assertEquals(1, intent.getRetryCount());

        intent.startPlanning(); // RETRY_SCHEDULED → PLANNING
        intent.markPlanned();
        intent.markExecuting();
        intent.scheduleRetry(); // retryCount = 2
        assertEquals(2, intent.getRetryCount());

        intent.startPlanning();
        intent.markPlanned();
        intent.markExecuting();
        intent.scheduleRetry(); // retryCount = 3
        assertEquals(3, intent.getRetryCount());

        // retryCount (3) >= maxRetries (3) — must throw
        intent.startPlanning();
        intent.markPlanned();
        intent.markExecuting();
        assertThrows(IllegalStateException.class, intent::scheduleRetry);
    }

    // ── Drift tests ───────────────────────────────────────────────────────────

    @Test
    void shouldUpdateDriftWithinValidRange() {
        Intent intent = createIntent();

        intent.updateDrift(0.5);

        assertEquals(0.5, intent.getDriftScore().value());
    }

    @Test
    void shouldRejectNegativeDrift() {
        Intent intent = createIntent();

        assertThrows(IllegalArgumentException.class,
                () -> intent.updateDrift(-1.0));
    }

    @Test
    void shouldAllowDriftAtBoundaryValues() {
        Intent intent = createIntent();

        intent.updateDrift(0.0);
        assertEquals(0.0, intent.getDriftScore().value());

        intent.updateDrift(1.0);
        assertEquals(1.0, intent.getDriftScore().value());
    }

    // ── Budget tests ──────────────────────────────────────────────────────────

    @Test
    void shouldNotConsumeBudgetOnTerminalIntent() {
        Intent intent = createIntent();

        intent.startPlanning();
        intent.markPlanned();
        intent.markExecuting();
        intent.markEvaluating();
        intent.markSatisfied();

        assertTrue(intent.isTerminal());
        assertThrows(IllegalStateException.class,
                () -> intent.consumeBudget(10.0));
    }

    // ── Domain events ─────────────────────────────────────────────────────────

    @Test
    void shouldEmitCreatedEventOnCreation() {
        Intent intent = createIntent();

        var events = intent.pullDomainEvents();

        assertEquals(1, events.size());
        assertFalse(events.isEmpty());
    }

    @Test
    void shouldEmitEventOnEachTransition() {
        Intent intent = createIntent();
        intent.pullDomainEvents(); // clear CREATED event

        intent.startPlanning();
        assertEquals(1, intent.pullDomainEvents().size()); // PLANNING_STARTED

        intent.markPlanned();
        assertEquals(1, intent.pullDomainEvents().size()); // PLANNED

        intent.markExecuting();
        assertEquals(1, intent.pullDomainEvents().size()); // EXECUTION_STARTED
    }

    @Test
    void shouldClearEventsAfterPull() {
        Intent intent = createIntent();

        var first = intent.pullDomainEvents();
        var second = intent.pullDomainEvents();

        assertFalse(first.isEmpty());
        assertTrue(second.isEmpty());
    }

    // ── Version / optimistic locking ─────────────────────────────────────────

    @Test
    void shouldIncrementVersionOnEachTransition() {
        Intent intent = createIntent();
        long initial = intent.getVersion();

        intent.startPlanning();
        assertEquals(initial + 1, intent.getVersion());

        intent.markPlanned();
        assertEquals(initial + 2, intent.getVersion());
    }

    // ── Tenant / user context ─────────────────────────────────────────────────

    @Test
    void shouldSetTenantId() {
        UUID tenantId = UUID.randomUUID();
        Intent intent = Intent.create(
                tenantId,
                UUID.randomUUID(),
                "chat",
                IntentObjective.of(ObjectiveType.QUALITY, 0.9, 0.05),
                IntentConstraints.none(),
                Budget.of(10.0)
        );

        assertEquals(tenantId, intent.getTenantId());
    }

    @Test
    void shouldRejectNullTenantOnSetTenantId() {
        Intent intent = createIntent();
        assertThrows(IllegalArgumentException.class,
                () -> intent.setTenantId(null));
    }
}