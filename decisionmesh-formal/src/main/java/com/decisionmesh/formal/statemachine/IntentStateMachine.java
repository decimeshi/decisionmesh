package com.decisionmesh.formal.statemachine;

import com.decisionmesh.domain.intent.IntentPhase;

public class IntentStateMachine {

    private final TransitionMatrix matrix = new TransitionMatrix();

    public IntentPhase transition(IntentPhase current, IntentAction action) {
        return matrix.next(current, action);
    }

    public boolean isTerminal(IntentPhase phase) {
        return phase == IntentPhase.COMPLETED ||
               phase == IntentPhase.CANCELLED;
    }
}