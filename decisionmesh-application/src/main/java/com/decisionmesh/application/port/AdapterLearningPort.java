package com.decisionmesh.application.port;


import io.smallrye.mutiny.Uni;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface AdapterLearningPort {
    Uni<Map<String, AdapterStats>> getStats(UUID intentId, List<String> adapters);
    Uni<Map<String, AdapterStats>> getStatsForIntentType(UUID tenantId, String intentType);
}
