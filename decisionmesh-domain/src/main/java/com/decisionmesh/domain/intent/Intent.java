package com.decisionmesh.domain.intent;

import com.decisionmesh.domain.event.DomainEvent;
import com.decisionmesh.domain.event.IntentEventType;
import com.decisionmesh.domain.event.IntentStateChangedEvent;
import com.decisionmesh.domain.intent.value.IntentConstraints;
import com.decisionmesh.domain.intent.value.IntentObjective;
import com.decisionmesh.domain.value.Budget;
import com.decisionmesh.domain.value.DriftScore;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Intent aggregate root.
 *
 * Two deserialization paths — intentionally separate:
 *
 *   fromRequest()          — REST inbound via JAX-RS body deserialization.
 *   fromJson(json, mapper) — Redis rehydration.
 *
 * injectionRisk (added V5):
 *   Set during PLANNING phase by PromptInjectionGuardService via flagInjectionRisk().
 *   Nullable — null means not yet scanned or risk = 0.
 *   Named flagInjectionRisk() rather than setInjectionRisk() to match the domain
 *   verb pattern (markSatisfied, scheduleRetry, updateDriftScore).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class Intent {

    private UUID id;
    private UUID tenantId;
    private UUID userId;

    private final String             intentType;
    private final IntentObjective    objective;
    private final IntentConstraints  constraints;

    private IntentPhase       phase;
    private SatisfactionState satisfactionState;
    private int               retryCount;
    private int               maxRetries;

    private Budget     budget;
    private DriftScore driftScore;

    // V5 — injection risk score from PromptInjectionGuardService (nullable)
    private BigDecimal injectionRisk;

    private boolean terminal;
    private long    version;

    private final Instant createdAt;
    private       Instant updatedAt;

    @JsonIgnore
    private final List<DomainEvent> events = new ArrayList<>();

    @JsonIgnore
    private final boolean rehydrated;

    private String violationReason;

    // Model tier sent by the frontend (_modelTier in request body).
    // Stored so credit deduction uses the correct tier cost.
    private String modelTier;

    // =========================================================================
    // FULL CONSTRUCTOR
    // =========================================================================

    private Intent(UUID id,
                   UUID tenantId,
                   UUID userId,
                   String intentType,
                   IntentObjective objective,
                   IntentConstraints constraints,
                   IntentPhase phase,
                   SatisfactionState satisfactionState,
                   int retryCount,
                   int maxRetries,
                   Budget budget,
                   DriftScore driftScore,
                   BigDecimal injectionRisk,
                   boolean terminal,
                   long version,
                   Instant createdAt,
                   Instant updatedAt,
                   boolean rehydrated) {

        this.id                = id;
        this.tenantId          = tenantId;
        this.userId            = userId;
        this.intentType        = intentType;
        this.objective         = objective;
        this.constraints       = constraints;
        this.phase             = phase;
        this.satisfactionState = satisfactionState;
        this.retryCount        = retryCount;
        this.maxRetries        = maxRetries;
        this.budget            = budget;
        this.driftScore        = driftScore;
        this.injectionRisk     = injectionRisk;
        this.terminal          = terminal;
        this.version           = version;
        this.createdAt         = createdAt;
        this.updatedAt         = updatedAt;
        this.rehydrated        = rehydrated;

        if (!rehydrated) {
            emit(IntentEventType.CREATED);
        }
    }

    // =========================================================================
    // FACTORY — programmatic creation by orchestrator
    // =========================================================================

    public static Intent create(UUID tenantId,
                                UUID userId,
                                String intentType,
                                IntentObjective objective,
                                IntentConstraints constraints,
                                Budget budget) {

        Objects.requireNonNull(tenantId,    "tenantId is required");
        Objects.requireNonNull(userId,      "userId is required");
        Objects.requireNonNull(intentType,  "intentType is required");
        Objects.requireNonNull(objective,   "objective is required");
        Objects.requireNonNull(constraints, "constraints is required");
        Objects.requireNonNull(budget,      "budget is required");

        return new Intent(
                UUID.randomUUID(), tenantId, userId,
                intentType, objective, constraints,
                IntentPhase.CREATED, SatisfactionState.UNKNOWN,
                0, constraints.maxRetries(),
                budget, DriftScore.of(0.0),
                null,   // injectionRisk — not yet scanned
                false, 0L,
                Instant.now(), Instant.now(),
                false
        );
    }

    // =========================================================================
    // REST FACTORY — JAX-RS request body deserialization
    // =========================================================================

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public static Intent fromRequest(
            @JsonProperty("intentType")  String intentType,
            @JsonProperty("objective")   IntentObjective objective,
            @JsonProperty("constraints") IntentConstraints constraints,
            @JsonProperty("budget")      Budget budget) {

        Objects.requireNonNull(intentType,  "intentType is required");
        Objects.requireNonNull(objective,   "objective is required");
        Objects.requireNonNull(constraints, "constraints is required");
        Objects.requireNonNull(budget,      "budget is required");

        return new Intent(
                UUID.randomUUID(),
                null, null,
                intentType, objective, constraints,
                IntentPhase.CREATED, SatisfactionState.UNKNOWN,
                0, constraints.maxRetries(),
                budget, DriftScore.of(0.0),
                null,   // injectionRisk — not yet scanned
                false, 0L,
                Instant.now(), Instant.now(),
                false
        );
    }

    // =========================================================================
    // SERIALIZATION — Redis storage
    // =========================================================================

    public String toJson(ObjectMapper mapper) {
        try {
            return mapper.writeValueAsString(this);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to serialise Intent id=" + id + " to JSON", e);
        }
    }

    public static Intent fromJson(String json, ObjectMapper mapper) {
        try {
            JsonNode root = mapper.readTree(json);

            UUID id       = requiredUUID(root, "id");
            UUID tenantId = safeUUID(root, "tenantId");
            UUID userId   = safeUUID(root, "userId");

            String intentType = safeText(root, "intentType");

            IntentObjective objective = root.hasNonNull("objective")
                    ? mapper.treeToValue(root.get("objective"), IntentObjective.class)
                    : null;

            IntentConstraints constraints = root.hasNonNull("constraints")
                    ? mapper.treeToValue(root.get("constraints"), IntentConstraints.class)
                    : null;

            IntentPhase phase = root.hasNonNull("phase")
                    ? IntentPhase.valueOf(root.get("phase").asText())
                    : IntentPhase.CREATED;

            SatisfactionState satisfactionState = root.hasNonNull("satisfactionState")
                    ? SatisfactionState.valueOf(root.get("satisfactionState").asText())
                    : SatisfactionState.UNKNOWN;

            int retryCount = root.hasNonNull("retryCount")  ? root.get("retryCount").asInt(0)  : 0;
            int maxRetries = root.hasNonNull("maxRetries")   ? root.get("maxRetries").asInt(0)  : 0;

            Budget budget = root.hasNonNull("budget")
                    ? mapper.treeToValue(root.get("budget"), Budget.class)
                    : null;

            double rawDrift = root.hasNonNull("driftScore")
                    ? root.get("driftScore").asDouble(0.0) : 0.0;
            DriftScore driftScore = DriftScore.of(rawDrift);

            // injectionRisk — nullable, only present in V5+ records
            BigDecimal injectionRisk = root.hasNonNull("injectionRisk")
                    ? BigDecimal.valueOf(root.get("injectionRisk").asDouble(0.0))
                    : null;

            boolean terminal = root.hasNonNull("terminal") && root.get("terminal").asBoolean(false);
            long version     = root.hasNonNull("version")  ? root.get("version").asLong(0L) : 0L;

            Instant createdAt = root.hasNonNull("createdAt") ? parseTimestamp(root, "createdAt") : Instant.now();
            Instant updatedAt = root.hasNonNull("updatedAt") ? parseTimestamp(root, "updatedAt") : Instant.now();

            // modelTier — present in V6+ records (added when tier-based billing landed)
            String modelTier = safeText(root, "modelTier");

            Intent rehydrated = new Intent(
                    id, tenantId, userId,
                    intentType, objective, constraints,
                    phase, satisfactionState,
                    retryCount, maxRetries,
                    budget, driftScore,
                    injectionRisk,
                    terminal, version,
                    createdAt, updatedAt,
                    true
            );
            if (modelTier != null) rehydrated.setModelTier(modelTier);
            return rehydrated;

        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialise Intent from JSON", e);
        }
    }

    // ── Timestamp parser ──────────────────────────────────────────────────────

    private static Instant parseTimestamp(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value != null && value.isNumber()) {
            return Instant.ofEpochSecond((long) value.doubleValue());
        }
        return Instant.parse(value.asText());
    }

    private static UUID safeUUID(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull() || node.asText().isBlank()) return null;
        return UUID.fromString(node.asText());
    }

    private static UUID requiredUUID(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull() || node.asText().isBlank())
            throw new IllegalArgumentException(field + " is required");
        return UUID.fromString(node.asText());
    }

    private static String safeText(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return (node == null || node.isNull()) ? null : node.asText();
    }

    // =========================================================================
    // LIFECYCLE TRANSITIONS
    // =========================================================================

    public void startPlanning() {
        if (phase == IntentPhase.RETRY_SCHEDULED) {
            transition(IntentPhase.RETRY_SCHEDULED, IntentPhase.PLANNING, IntentEventType.PLANNING_STARTED);
        } else {
            transition(IntentPhase.CREATED, IntentPhase.PLANNING, IntentEventType.PLANNING_STARTED);
        }
    }

    public void markPlanned()    { transition(IntentPhase.PLANNING,  IntentPhase.PLANNED,    IntentEventType.PLANNED); }
    public void markExecuting()  { transition(IntentPhase.PLANNED,   IntentPhase.EXECUTING,  IntentEventType.EXECUTION_STARTED); }
    public void markEvaluating() { transition(IntentPhase.EXECUTING, IntentPhase.EVALUATING, IntentEventType.EVALUATION_STARTED); }

    /**
     * Parks the intent for human review.
     * Called by ControlPlaneOrchestrator when requireHumanReview=true in policy.
     * Intent stays NON-terminal so it appears in ReviewQueueResource.
     * Human reviewer calls approve (→ SATISFIED) or reject (→ VIOLATED).
     */
    public void markPendingReview() {
        // Set directly — bypass transition() phase guard since EVALUATING→REVIEWING
        // is not a standard transition but a governance interception.
        this.phase             = IntentPhase.COMPLETED;  // visually complete
        this.satisfactionState = SatisfactionState.UNKNOWN; // awaiting human decision
        this.terminal          = true;   // FIX: terminal=true so UI polling stops immediately.
        // The intent stays in the review queue because
        // ReviewQueueResource.hasReviewFlag() filters on
        // satisfactionState=UNKNOWN, not on terminal=false.
        // Without this, Playground/IntentDetails/Replay all
        // poll indefinitely waiting for terminal=true.
        this.version++;
        touch();
        // Emit SATISFIED event for now — will update to PENDING_REVIEW when enum is extended
        emit(IntentEventType.EVALUATION_STARTED);
    }

    public void markSatisfied() {
        transition(IntentPhase.EVALUATING, IntentPhase.COMPLETED, IntentEventType.SATISFIED);
        this.satisfactionState = SatisfactionState.SATISFIED;
        this.terminal = true;
        enforceInvariant();
    }

    public void forceViolated() {
        // Skip transition() phase guard — set state directly
        this.phase             = IntentPhase.COMPLETED;
        this.satisfactionState = SatisfactionState.VIOLATED;
        this.terminal          = true;
        this.version++;
        touch();

        // Emit VIOLATED event so intent_events table records the transition
        // and ExecutionTimeline + PolicyOutcomeCard show the correct state.
        emit(IntentEventType.VIOLATED);
    }

    public void markViolated() {
        if (phase == IntentPhase.EXECUTING) {
            transition(IntentPhase.EXECUTING,  IntentPhase.COMPLETED, IntentEventType.VIOLATED);
        } else {
            transition(IntentPhase.EVALUATING, IntentPhase.COMPLETED, IntentEventType.VIOLATED);
        }
        this.satisfactionState = SatisfactionState.VIOLATED;
        this.terminal = true;
        enforceInvariant();
    }

    public void scheduleRetry() {
        if (phase != IntentPhase.EXECUTING)
            throw new IllegalStateException("Retry only allowed from EXECUTING phase, current: " + phase);
        if (retryCount >= this.maxRetries)
            throw new IllegalStateException("Max retries exceeded: " + retryCount + "/" + maxRetries);
        retryCount++;
        phase = IntentPhase.RETRY_SCHEDULED;
        version++;
        touch();
        emit(IntentEventType.RETRY_SCHEDULED);
    }

    public void resumeExecution() {
        if (phase != IntentPhase.RETRY_SCHEDULED)
            throw new IllegalStateException("Can only resume from RETRY_SCHEDULED, current: " + phase);
        phase = IntentPhase.EXECUTING;
        version++;
        touch();
        emit(IntentEventType.EXECUTION_STARTED);
    }

    public void updateDrift(double score) {
        this.driftScore = DriftScore.of(score);
        version++;
        touch();
        emit(IntentEventType.DRIFT_UPDATED);
    }

    public void consumeBudget(double amount) {
        if (terminal) throw new IllegalStateException("Cannot consume budget on terminal intent");
        this.budget = this.budget.consume(amount);
        version++;
        touch();
        emit(IntentEventType.BUDGET_CONSUMED);
    }

    public void updateDriftScore(BigDecimal score, UUID executionId) {
        if (score == null) return;
        this.driftScore = DriftScore.of(score.doubleValue());
        version++;
        touch();
        emit(IntentEventType.DRIFT_UPDATED);
    }

    /**
     * Flag the intent with an injection risk score detected during PLANNING.
     *
     * Named flagInjectionRisk() rather than setInjectionRisk() to match the
     * domain verb pattern (markSatisfied, scheduleRetry, updateDriftScore).
     * Does not emit a domain event — this is a security annotation, not a
     * lifecycle transition.
     */
    public void flagInjectionRisk(BigDecimal risk) {
        if (risk == null || risk.compareTo(BigDecimal.ZERO) < 0) return;
        this.injectionRisk = risk;
        // No version bump or event — injection risk is a read-only annotation
        // for the policy engine, not a state machine transition.
    }

    // =========================================================================
    // INTERNAL
    // =========================================================================

    private void transition(IntentPhase expected, IntentPhase next, IntentEventType eventType) {
        if (terminal)
            throw new IllegalStateException("Cannot transition terminal intent: id=" + id);
        if (phase != expected)
            throw new IllegalStateException(String.format(
                    "Invalid transition: expected=%s, actual=%s, next=%s, id=%s",
                    expected, phase, next, id));
        phase = next;
        version++;
        touch();
        emit(eventType);
    }

    private void enforceInvariant() {
        // UNKNOWN is valid for COMPLETED intents parked in the review queue.
        // Only throw if UNKNOWN has no explanation (i.e. not a review queue intent).
        // Review queue intents: phase=COMPLETED, satisfactionState=UNKNOWN, terminal=true.
        if (phase == IntentPhase.COMPLETED
                && satisfactionState == SatisfactionState.UNKNOWN
                && !terminal) {
            throw new IllegalStateException(
                    "Completed non-terminal intent must have satisfaction state set: id=" + id);
        }
    }
    public String getViolationReason() { return violationReason; }
    public void setViolationReason(String reason) {
        this.violationReason = reason;
    }

    @JsonProperty("_modelTier")
    public void setModelTier(String tier) {
        this.modelTier = tier;
    }

    private void touch() { this.updatedAt = Instant.now(); }

    private void emit(IntentEventType type) {
        if (this.tenantId == null) return;
        events.add(new IntentStateChangedEvent(
                UUID.randomUUID(), this.id, this.tenantId,
                this.version, type, Instant.now()
        ));
    }

    // =========================================================================
    // SETTERS — orchestrator / resource use only
    // =========================================================================

    public void setTenantId(UUID tenantId) {
        if (tenantId == null) throw new IllegalArgumentException("tenantId must not be null");
        boolean firstTime = this.tenantId == null;
        this.tenantId = tenantId;
        if (firstTime && !rehydrated) {
            emit(IntentEventType.CREATED);
        }
    }

    public void setUserId(UUID userId) {
        if (userId == null) throw new IllegalArgumentException("userId must not be null");
        this.userId = userId;
    }

    public void setId(UUID id) {
        if (id == null) throw new IllegalArgumentException("id must not be null");
        this.id = id;
    }

    // =========================================================================
    // GETTERS
    // =========================================================================

    public UUID               getId()                { return id; }
    public UUID               getTenantId()          { return tenantId; }
    public UUID               getUserId()            { return userId; }
    public String             getIntentType()        { return intentType; }
    public IntentObjective    getObjective()         { return objective; }
    public IntentConstraints  getConstraints()       { return constraints; }
    public IntentPhase        getPhase()             { return phase; }
    public SatisfactionState  getSatisfactionState() { return satisfactionState; }
    public int                getRetryCount()        { return retryCount; }
    public int                getMaxRetries()        { return maxRetries; }
    public Budget             getBudget()            { return budget; }
    public DriftScore         getDriftScore()        { return driftScore; }
    public BigDecimal         getInjectionRisk()     { return injectionRisk; }
    public boolean            isTerminal()           { return terminal; }
    public long               getVersion()           { return version; }
    public Instant            getCreatedAt()         { return createdAt; }
    public Instant            getUpdatedAt()         { return updatedAt; }
    @JsonProperty("modelTier")
    public String             getModelTier()         { return modelTier; }

    public Double getCeilingUsd() {
        return budget != null ? budget.getCeilingUsd() : null;
    }

    @JsonIgnore
    public List<DomainEvent> getEvents() {
        return Collections.unmodifiableList(events);
    }

    @JsonIgnore
    public List<DomainEvent> pullDomainEvents() {
        List<DomainEvent> copy = List.copyOf(events);
        events.clear();
        return copy;
    }
}