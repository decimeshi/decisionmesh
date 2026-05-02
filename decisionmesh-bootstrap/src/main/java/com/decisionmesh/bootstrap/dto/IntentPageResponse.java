package com.decisionmesh.bootstrap.dto;

import java.util.List;

public record IntentPageResponse(
        List<IntentSummaryResponse> content,
        long                   totalElements,
        int                    totalPages,
        int                    size,
        int                    number
) {}