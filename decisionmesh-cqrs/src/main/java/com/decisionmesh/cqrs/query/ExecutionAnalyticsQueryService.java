package com.decisionmesh.cqrs.query;

import com.decisionmesh.cqrs.readmodel.ExecutionAnalyticsView;
import io.smallrye.mutiny.Uni;
import java.util.UUID;

public interface ExecutionAnalyticsQueryService {

    Uni<ExecutionAnalyticsView> findByIntentId(UUID intentId);

}