package com.decisionmesh.domain.policy;

import java.util.Objects;

public record PolicyEvaluation(Decision decision, EnforcementMode enforcementMode, String reason) {

    public enum Decision {ALLOWED, WARNING, VIOLATION}

    public enum EnforcementMode {HARD_STOP, WARN_ONLY, LOG_ONLY}

    public PolicyEvaluation(Decision decision,
                            EnforcementMode enforcementMode,
                            String reason) {
        this.decision = Objects.requireNonNull(decision);
        this.enforcementMode = Objects.requireNonNull(enforcementMode);
        this.reason = reason;
    }

    public boolean isBlocking() {
        return decision == Decision.VIOLATION &&
                enforcementMode == EnforcementMode.HARD_STOP;
    }

    public boolean isWarning() {
        return decision == Decision.WARNING ||
                (decision == Decision.VIOLATION &&
                        enforcementMode == EnforcementMode.WARN_ONLY);
    }
}
