package com.decisionmesh.formal.statemachine;

import com.decisionmesh.domain.intent.IntentPhase;
import java.util.Map;
import java.util.Set;

public class TransitionMatrix {

    private static final Map<IntentPhase, Map<IntentAction, IntentPhase>> transitions = Map.of(
        IntentPhase.CREATED, Map.of(
            IntentAction.START_PLANNING, IntentPhase.PLANNING
        ),
        IntentPhase.PLANNING, Map.of(
            IntentAction.MARK_PLANNED, IntentPhase.PLANNED
        ),
        IntentPhase.PLANNED, Map.of(
            IntentAction.MARK_EXECUTING, IntentPhase.EXECUTING
        ),
        IntentPhase.EXECUTING, Map.of(
            IntentAction.MARK_SATISFIED, IntentPhase.COMPLETED,
            IntentAction.MARK_VIOLATED, IntentPhase.COMPLETED,
            IntentAction.SCHEDULE_RETRY, IntentPhase.RETRY_SCHEDULED
        ),
        IntentPhase.RETRY_SCHEDULED, Map.of(
            IntentAction.MARK_EXECUTING, IntentPhase.EXECUTING
        )
    );

    public IntentPhase next(IntentPhase current, IntentAction action) {
        if (!transitions.containsKey(current) ||
            !transitions.get(current).containsKey(action)) {
            throw new IllegalStateException("Illegal transition: " + current + " -> " + action);
        }
        return transitions.get(current).get(action);
    }

    public Set<IntentAction> allowedActions(IntentPhase phase) {
        return transitions.getOrDefault(phase, Map.of()).keySet();
    }
}