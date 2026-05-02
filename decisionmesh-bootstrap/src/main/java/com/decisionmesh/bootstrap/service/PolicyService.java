package com.decisionmesh.bootstrap.service;

import com.decisionmesh.bootstrap.dto.PolicyResponse;
import com.decisionmesh.bootstrap.dto.PolicyResponse.SavePolicyRequest;
import com.decisionmesh.persistence.entity.PolicyEntity;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.NotFoundException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Business logic for policy CRUD.
 *
 * <h3>ruleDsl storage format</h3>
 * Rules from the UI arrive as a flat {@code List<Map<String,Object>>}.
 * They are stored in {@code PolicyEntity.ruleDsl} as:
 * <pre>{@code {"rules": [{id, metric, operator, value, action}, ...]}}</pre>
 *
 * This keeps the JSONB column extensible (future DSL fields go alongside "rules")
 * while giving the UI a clean flat array to read and write.
 */
@ApplicationScoped
public class PolicyService {

    // ── List ──────────────────────────────────────────────────────────────────

    /**
     * All policies for a tenant — active and inactive — ordered by priority.
     * PolicyBuilder shows all so the user can toggle/delete them.
     */
    public Uni<List<PolicyResponse>> list(UUID tenantId) {
        return PolicyEntity.findActiveByTenant(tenantId)
                .map(entities -> entities.stream()
                        .map(PolicyResponse::from)
                        .toList());
    }

    // ── Create ────────────────────────────────────────────────────────────────

    /**
     * Creates a new policy and persists it.
     *
     * Called by POST /api/policies when {@code body.policyId} is null.
     * Returns the full {@link PolicyResponse} so the UI gets the server-assigned
     * {@code policyId} and can switch from unsaved→saved state immediately.
     */
    public Uni<PolicyResponse> create(UUID tenantId, SavePolicyRequest req) {
        PolicyEntity entity = new PolicyEntity();
        entity.tenantId  = tenantId;
        entity.isActive  = true;   // explicit — don't rely on field default alone
        applyRequest(entity, req);

        Log.infof("Creating policy: tenant=%s name=%s rules=%d",
                tenantId, entity.name, rulesSize(entity));

        return entity.<PolicyEntity>persistAndFlush()
                .map(saved -> {
                    Log.infof("Policy created: id=%s tenant=%s", saved.id, tenantId);
                    return PolicyResponse.from(saved);
                });
    }

    // ── Update ────────────────────────────────────────────────────────────────

    /**
     * Updates an existing policy.
     *
     * Called by PUT /api/policies/{policyId} when {@code body.policyId} is set.
     * Uses optimistic locking via {@code @Version} — the entity's version field
     * is managed by Hibernate and incremented on every successful update.
     *
     * @throws NotFoundException if the policy doesn't exist for the tenant
     */
    public Uni<PolicyResponse> update(UUID tenantId, UUID policyId, SavePolicyRequest req) {
        return PolicyEntity.<PolicyEntity>findById(policyId)
                .onItem().ifNull().failWith(() ->
                        new NotFoundException("Policy not found: " + policyId))
                .flatMap(entity -> {
                    // Tenant check — prevent cross-tenant updates
                    if (!tenantId.equals(entity.tenantId)) {
                        return Uni.createFrom().failure(
                                new NotFoundException("Policy not found: " + policyId));
                    }
                    applyRequest(entity, req);
                    Log.infof("Updating policy: id=%s tenant=%s rules=%d",
                            policyId, tenantId, rulesSize(entity));
                    return entity.<PolicyEntity>persistAndFlush();
                })
                .map(PolicyResponse::from);
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    /**
     * Hard-deletes a policy. Soft-delete (setting isActive=false) is not used
     * here because PolicyBuilder.jsx calls DELETE and then removes the card —
     * it expects the policy to vanish from the list on reload.
     *
     * @throws NotFoundException if the policy doesn't exist for the tenant
     */
    public Uni<Void> delete(UUID tenantId, UUID policyId) {
        return PolicyEntity.<PolicyEntity>findById(policyId)
                .onItem().ifNull().failWith(() ->
                        new NotFoundException("Policy not found: " + policyId))
                .flatMap(entity -> {
                    if (!tenantId.equals(entity.tenantId)) {
                        return Uni.createFrom().failure(
                                new NotFoundException("Policy not found: " + policyId));
                    }
                    Log.infof("Deleting policy: id=%s tenant=%s", policyId, tenantId);
                    return entity.delete();
                });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Applies fields from the save request onto the entity.
     * Called for both create and update so the mapping logic is in one place.
     *
     * <p>Rules are wrapped in a {@code {"rules": [...]}} map and stored in
     * {@code ruleDsl} so the JSONB column can hold future DSL fields too.
     */
    private void applyRequest(PolicyEntity entity, SavePolicyRequest req) {
        if (req.name         != null) entity.name            = req.name.trim();
        if (req.description  != null) entity.description     = req.description;
        if (req.phase        != null) entity.phase           = req.phase;
        if (req.enforcementMode != null) entity.enforcementMode = req.enforcementMode;
        if (req.priority     != null) entity.priority        = req.priority;


        // Wrap the flat rules array into the ruleDsl map
        List<Map<String, Object>> rules =
                req.rules != null ? req.rules : new ArrayList<>();
        Map<String, Object> dsl = new HashMap<>();
        dsl.put("rules", rules);
        entity.ruleDsl = dsl;
    }

    private int rulesSize(PolicyEntity entity) {
        if (entity.ruleDsl == null) return 0;
        Object r = entity.ruleDsl.get("rules");
        return r instanceof List<?> l ? l.size() : 0;
    }
}