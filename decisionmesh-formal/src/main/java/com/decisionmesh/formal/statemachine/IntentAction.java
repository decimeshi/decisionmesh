package com.decisionmesh.formal.statemachine;

public enum IntentAction {
    START_PLANNING,
    MARK_PLANNED,
    MARK_EXECUTING,
    MARK_SATISFIED,
    MARK_VIOLATED,
    SCHEDULE_RETRY,
    CANCEL
}