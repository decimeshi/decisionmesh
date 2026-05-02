package com.decisionmesh.bootstrap.dto;

import com.decisionmesh.persistence.entity.PolicyEntity;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * DTO for GET /api/policies (list) and POST/PUT /api/policies (create/update).
 *
 * <h3>Field mapping: entity ↔ UI</h3>
 * <pre>
 * PolicyEntity.id       → PolicyDto.policyId   (UI uses policy.policyId everywhere —
 *                                                 savePolicy path, delete call, PUT body)
 * PolicyEntity.ruleDsl  → PolicyDto.rules       (entity stores {rules:[...]} map;
 *                                                 UI reads/writes a flat rules array)
 * </pre>
 *
 * <h3>ruleDsl storage format</h3>
 * {@code PolicyEntity.ruleDsl} is {@code Map<String,Object>} backed by a jsonb column.
 * We store {@code {"rules": [{id, metric, operator, value, action}, ...]}} so the
 * full DSL map is extensible for future fields without schema changes.
 *
 * <h3>Request body shape (what PolicyBuilder.jsx sends)</h3>
 * <pre>
 * POST  { name, rules: [{id, metric, operator, value, action}] }
 * PUT   { policyId, name, rules: [{id, metric, operator, value, action}] }
 * </pre>
 *
 * <h3>Response shape (what PolicyBuilder.jsx reads)</h3>
 * <pre>
 * { policyId, name, rules: [{id, metric, operator, value, action}] }
 * </pre>
 * PolicyCard reads {@code policy.policyId} for the delete button and PUT path.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PolicyResponse {

    /**
     * Maps from {@code PolicyEntity.id}.
     * The UI uses {@code policyId} everywhere — NOT {@code id}:
     * <ul>
     *   <li>{@code savePolicy}: {@code body.policyId ? PUT : POST}</li>
     *   <li>{@code deletePolicy}: {@code /policies/${policy.policyId}}</li>
     *   <li>{@code PolicyCard}: {@code onDelete(policy.policyId)}</li>
     * </ul>
     */
    public UUID policyId;

    public String name;

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public String description;

    /**
     * Rules extracted from {@code PolicyEntity.ruleDsl["rules"]}.
     * Each rule: {@code {id, metric, operator, value, action}}.
     * The UI reads and writes this directly as a flat array.
     */
    public List<Map<String, Object>> rules;

    // Expose phase, enforcementMode, priority so the UI can extend later
    public String  phase;
    public String  enforcementMode;
    public int     priority;
    public boolean isActive;

    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;

    // ── Factory — entity → DTO ────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public static PolicyResponse from(PolicyEntity e) {
        PolicyResponse dto        = new PolicyResponse();
        dto.policyId         = e.id;           // entity PK → policyId
        dto.name             = e.name;
        dto.description      = e.description;
        dto.phase            = e.phase;
        dto.enforcementMode  = e.enforcementMode;
        dto.priority         = e.priority;
        dto.isActive         = e.isActive;
        dto.createdAt        = e.createdAt;
        dto.updatedAt        = e.updatedAt;

        // Extract rules array from ruleDsl map: { "rules": [...] }
        if (e.ruleDsl != null && e.ruleDsl.get("rules") instanceof List<?> rawList) {
            dto.rules = (List<Map<String, Object>>) rawList;
        } else {
            dto.rules = new ArrayList<>();
        }
        return dto;
    }

    // ── Request body (what savePolicy sends) ──────────────────────────────────

    /**
     * Deserialised from the POST/PUT request body.
     * Jackson maps the JSON directly to this class via the same field names.
     * {@code policyId} will be null on POST (new policy) and set on PUT (update).
     */
    public static class SavePolicyRequest {
        public UUID   policyId;       // null = create, non-null = update
        public String name;
        public String description;
        public String phase;
        public String enforcementMode;
        public Integer priority;
        public List<Map<String, Object>> rules;
    }
}