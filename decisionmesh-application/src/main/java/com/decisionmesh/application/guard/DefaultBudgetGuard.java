package com.decisionmesh.application.guard;

import com.decisionmesh.application.port.BudgetGuard;
import com.decisionmesh.domain.intent.Intent;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;


@ApplicationScoped
public class DefaultBudgetGuard implements BudgetGuard {

    @Override
    public Uni<Void> validateBudget(Intent intent) {

        if (intent == null)
            return Uni.createFrom().failure(
                    new IllegalArgumentException("Intent cannot be null"));

        if (intent.getBudget() == null)
            return Uni.createFrom().failure(
                    new IllegalStateException("Intent has no budget defined"));

        if (intent.getBudget().isExceeded())
            return Uni.createFrom().failure(
                    new IllegalStateException(
                            "Budget exceeded for intent " + intent.getId()));

        return Uni.createFrom().voidItem();
    }
}