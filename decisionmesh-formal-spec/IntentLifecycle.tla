----------------------------- MODULE IntentLifecycle -----------------------------
EXTENDS Naturals, Sequences

CONSTANTS
    CREATED, PLANNING, PLANNED, EXECUTING, RETRY_SCHEDULED, COMPLETED, CANCELLED
    MAX_RETRIES

VARIABLES phase, retryCount, satisfaction

Init ==
    /\ phase = CREATED
    /\ retryCount = 0
    /\ satisfaction = "UNKNOWN"

StartPlanning ==
    /\ phase = CREATED
    /\ phase' = PLANNING
    /\ UNCHANGED <<retryCount, satisfaction>>

MarkPlanned ==
    /\ phase = PLANNING
    /\ phase' = PLANNED
    /\ UNCHANGED <<retryCount, satisfaction>>

MarkExecuting ==
    /\ phase \in {PLANNED, RETRY_SCHEDULED}
    /\ phase' = EXECUTING
    /\ UNCHANGED <<retryCount, satisfaction>>

ScheduleRetry ==
    /\ phase = EXECUTING
    /\ retryCount < MAX_RETRIES
    /\ retryCount' = retryCount + 1
    /\ phase' = RETRY_SCHEDULED
    /\ UNCHANGED satisfaction

MarkSatisfied ==
    /\ phase = EXECUTING
    /\ phase' = COMPLETED
    /\ satisfaction' = "SATISFIED"
    /\ UNCHANGED retryCount

MarkViolated ==
    /\ phase = EXECUTING
    /\ phase' = COMPLETED
    /\ satisfaction' = "VIOLATED"
    /\ UNCHANGED retryCount

Next ==
    \/ StartPlanning
    \/ MarkPlanned
    \/ MarkExecuting
    \/ ScheduleRetry
    \/ MarkSatisfied
    \/ MarkViolated

InvariantRetryBound == retryCount <= MAX_RETRIES
InvariantTerminalAbsorbing ==
    phase = COMPLETED => UNCHANGED phase

Spec == Init /\ [][Next]_<<phase, retryCount, satisfaction>>

=============================================================================