package com.decisionmesh.cqrs.readmodel;

import java.util.UUID;

public record ExecutionAnalyticsView(UUID intentId, int totalAttempts, double totalCost, long totalLatency) {

}