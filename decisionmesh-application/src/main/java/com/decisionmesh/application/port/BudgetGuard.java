package com.decisionmesh.application.port;

import com.decisionmesh.domain.intent.Intent;
import io.smallrye.mutiny.Uni;

public interface BudgetGuard {
    Uni<Void> validateBudget(Intent intent);
}