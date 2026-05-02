package com.decisionmesh.application.port;

import com.decisionmesh.domain.intent.Intent;
import com.decisionmesh.domain.execution.ExecutionRecord;
import io.smallrye.mutiny.Uni;

public interface SLAGuard {
    Uni<Void> validateBeforeExecution(Intent intent);
    Uni<Void> validateAfterExecution(Intent intent, ExecutionRecord record);
}