package com.decisionmesh.domain.event;

public enum IntentEventType {

    CREATED,

    PLANNING_STARTED,
    PLANNED,

    EXECUTION_STARTED,

    EVALUATION_STARTED,

    RETRY_SCHEDULED,

    SATISFIED,
    VIOLATED,

    BUDGET_CONSUMED,
    DRIFT_UPDATED,

    CANCELLED
}
