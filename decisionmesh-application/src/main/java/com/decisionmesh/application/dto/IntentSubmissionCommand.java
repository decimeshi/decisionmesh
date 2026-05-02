package com.decisionmesh.application.dto;

public record IntentSubmissionCommand(
    String tenantId,
    int maxRetries,
    String idempotencyKey,
    String intentType
) {}