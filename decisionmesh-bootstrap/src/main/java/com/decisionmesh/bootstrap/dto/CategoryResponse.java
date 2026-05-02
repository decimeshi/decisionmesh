package com.decisionmesh.bootstrap.dto;

public record CategoryResponse(
        String category,
        String categoryLabel,
        int intentCount,
        String defaultRiskLevel,
        String regulatoryRef
) {}
