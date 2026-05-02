package com.decisionmesh.llm.dto;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Request DTO for creating/updating adapters.
 *
 * Uses JsonNode for JSONB fields (config, capabilityFlags) so Jackson accepts
 * any JSON shape — object, array, string, null — without type mismatch errors.
 * The service converts JsonNode → String for storage in AdapterEntity.
 *
 * Replaces using AdapterEntity directly as the JAX-RS request body, which
 * caused "value:null" Bean Validation errors because Jackson cannot deserialize
 * a JSON object/array into a String field without a custom deserializer.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AdapterRequest(
        String      name,
        String      provider,
        String      adapterType,
        String      modelId,
        String      region,
        Boolean     isActive,

        // JsonNode accepts any JSON value: {}, [], "string", null
        JsonNode    config,
        JsonNode    capabilityFlags,

        // allowedIntentTypes: frontend sends [] or ["CHAT","SUMMARIZATION"]
        List<String> allowedIntentTypes
) {
    /** Safe JSON string for config — defaults to {} if null */
    public String configJson() {
        if (config == null || config.isNull()) return "{}";
        return config.toString();
    }

    /** Safe JSON string for capabilityFlags — defaults to {} if null */
    public String capabilityFlagsJson() {
        if (capabilityFlags == null || capabilityFlags.isNull()) return "{}";
        return capabilityFlags.toString();
    }

    /** Safe JSON string for allowedIntentTypes — defaults to [] if null */
    public String allowedIntentTypesJson() {
        if (allowedIntentTypes == null || allowedIntentTypes.isEmpty()) return "[]";
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(allowedIntentTypes);
        } catch (Exception e) { return "[]"; }
    }
}
