package com.decisionmesh.domain.intent;

public enum IntentPhase {

    CREATED,          // Submitted but not yet planned

    PLANNING,         // Planner evaluating adapters

    PLANNED,          // Immutable plan created

    EXECUTING,        // Execution in progress

    EVALUATING,       // Post-execution policy + satisfaction evaluation

    RETRY_SCHEDULED,  // Waiting for retry window

    COMPLETED,        // Terminal (satisfied or violated)

    CANCELLED         // Terminal (manually cancelled)
}
