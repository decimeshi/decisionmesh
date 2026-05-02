package com.decisionmesh.formal.checker;

import com.decisionmesh.domain.intent.Intent;
import com.decisionmesh.domain.intent.IntentPhase;
import com.decisionmesh.formal.result.ModelCheckResult;

public class LifecycleInvariantChecker {

    public ModelCheckResult validate(Intent intent) {

        if (intent.getRetryCount() > intent.getMaxRetries()) {
            return ModelCheckResult.failure("Retry count exceeded max retries");
        }

        if (intent.isTerminal() &&
                intent.getPhase() == IntentPhase.EXECUTING) {
            return ModelCheckResult.failure("Terminal state cannot be EXECUTING");
        }

        if (intent.getPhase().name().equals("COMPLETED") &&
            intent.getSatisfactionState().name().equals("UNKNOWN")) {
            return ModelCheckResult.failure("Completed intent must have satisfaction");
        }

        return ModelCheckResult.success();
    }
}