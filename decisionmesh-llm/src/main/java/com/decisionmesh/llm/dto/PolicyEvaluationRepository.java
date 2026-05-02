package com.decisionmesh.llm.dto;


import com.decisionmesh.llm.PolicyEvaluationResponse;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;


import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reads policy_evaluations by intent — serves PolicyOutcomeCard in IntentDetail.jsx.
 *
 * Uses blocking JDBC (not Hibernate Reactive) so it runs on a worker thread
 * via runSubscriptionOn and does not interfere with the Vert.x event loop.
 * No RLS SET LOCAL — tenant_id is in the WHERE clause.
 */
@ApplicationScoped
public class PolicyEvaluationRepository {

    private static final String SELECT_BY_INTENT = """
            SELECT
                pe.id,
                pe.policy_id,
                pe.intent_id,
                pe.adapter_id,
                pe.result,
                pe.phase,
                pe.enforcement_mode,
                pe.block_reason,
                pe.attempt_number,
                pe.evaluated_at
            FROM policy_evaluations pe
            WHERE pe.intent_id  = ?
              AND pe.tenant_id  = ?
            ORDER BY pe.evaluated_at ASC
            """;

    @Inject
    DataSource dataSource;

    public Uni<List<PolicyEvaluationResponse>> findByIntent(UUID tenantId, UUID intentId) {
        return Uni.createFrom().item(() -> queryByIntent(tenantId, intentId))
                .runSubscriptionOn(
                        io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool())
                .onFailure().invoke(ex ->
                        Log.errorf(ex, "PolicyEvaluationRepository.findByIntent failed: intent=%s",
                                intentId));
    }

    private List<PolicyEvaluationResponse> queryByIntent(UUID tenantId, UUID intentId) {
        List<PolicyEvaluationResponse> rows = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_BY_INTENT)) {

            ps.setObject(1, intentId);
            ps.setObject(2, tenantId);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new PolicyEvaluationResponse(
                            rs.getObject("id",          UUID.class),
                            rs.getObject("policy_id",   UUID.class),
                            rs.getObject("intent_id",   UUID.class),
                            rs.getObject("adapter_id",  UUID.class),
                            rs.getString("result"),
                            rs.getString("phase"),
                            rs.getString("enforcement_mode"),
                            rs.getString("block_reason"),
                            rs.getObject("attempt_number") != null
                                    ? rs.getInt("attempt_number") : null,
                            rs.getTimestamp("evaluated_at") != null
                                    ? rs.getTimestamp("evaluated_at").toInstant() : null
                    ));
                }
            }
        } catch (Exception ex) {
            Log.errorf(ex, "DB error querying policy_evaluations: intent=%s", intentId);
        }

        return rows;
    }
}