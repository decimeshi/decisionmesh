package com.decisionmesh.common.dto;


/**
 * Request body for PATCH /api/org/branding
 */
public record BrandingRequest(
        String orgName,
        String primaryColor,   // hex e.g. #2563eb
        String logoUrl,
        String favicon
) {}
