package com.decisionmesh.bootstrap.service;

import io.agroal.api.AgroalDataSource;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Data Retention Policy Enforcement — DM-POL-003
 *
 * Runs nightly at 02:00 UTC and purges records exceeding their
 * defined retention periods per the Data Retention Policy.
 *
 * Retention schedule (verified against V1__decision_mesh.sql):
 *   intents                   created_at    2 years
 *   execution_records         executed_at   2 years
 *   intent_plans              created_at    2 years
 *   policy_evaluations        evaluated_at  2 years  ← no created_at
 *   api_keys (revoked)        revoked_at    90 days
 *   invitations (expired)     created_at    30 days
 *   intent_drift_evaluations  NO timestamp  — skip (join via intent)
 *   audit_log                 immutable     — anonymise only
 */
@ApplicationScoped
public class DataRetentionService {

    @Inject
    AgroalDataSource dataSource;

    @Scheduled(cron = "0 0 2 * * ?", identity = "data-retention-cleanup")
    public void enforceRetentionPolicy() {
        Log.info("[Retention] Starting nightly data retention enforcement");
        long startMs = System.currentTimeMillis();
        int totalDeleted = 0;

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {

                // ── 2-year retention ──────────────────────────────────────────
                totalDeleted += purge(conn,
                        "intents",
                        "created_at < ?",
                        Instant.now().minus(730, ChronoUnit.DAYS),
                        "intents older than 2 years");

                totalDeleted += purge(conn,
                        "execution_records",
                        "executed_at < ?",
                        Instant.now().minus(730, ChronoUnit.DAYS),
                        "execution_records older than 2 years");

                totalDeleted += purge(conn,
                        "intent_plans",
                        "created_at < ?",
                        Instant.now().minus(730, ChronoUnit.DAYS),
                        "intent_plans older than 2 years");

                // policy_evaluations uses evaluated_at (no created_at column)
                totalDeleted += purge(conn,
                        "policy_evaluations",
                        "evaluated_at < ?",
                        Instant.now().minus(730, ChronoUnit.DAYS),
                        "policy_evaluations older than 2 years");

                // ── 90-day retention (revoked API keys only) ──────────────────
                totalDeleted += purge(conn,
                        "api_keys",
                        "revoked_at IS NOT NULL AND revoked_at < ?",
                        Instant.now().minus(90, ChronoUnit.DAYS),
                        "revoked api_keys older than 90 days");

                // ── 30-day retention (expired/declined invitations only) ───────
                totalDeleted += purge(conn,
                        "invitations",
                        "status IN ('EXPIRED', 'DECLINED') AND created_at < ?",
                        Instant.now().minus(30, ChronoUnit.DAYS),
                        "expired/declined invitations older than 30 days");

                // ── intent_drift_evaluations — cascade deleted with intents ────
                // No timestamp column — rows are deleted automatically when
                // parent intent is deleted (ON DELETE CASCADE). No action needed.

                // ── Anonymise audit_log older than 7 years ────────────────────
                // Cannot DELETE — immutable trigger blocks it.
                // Anonymise PII fields instead.
                int anonymised = anonymiseAuditLog(conn,
                        Instant.now().minus(2555, ChronoUnit.DAYS));
                if (anonymised > 0) {
                    Log.infof("[Retention] Anonymised audit_log: %d rows", anonymised);
                }

                conn.commit();

                long elapsed = System.currentTimeMillis() - startMs;
                Log.infof("[Retention] Completed: deleted=%d elapsed=%dms", totalDeleted, elapsed);

            } catch (Exception e) {
                conn.rollback();
                Log.errorf(e, "[Retention] Failed — rolled back all changes");
            }
        } catch (Exception e) {
            Log.errorf(e, "[Retention] Cannot get DB connection");
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private int purge(Connection conn, String table, String condition,
                      Instant cutoff, String description) throws Exception {
        String sql = "DELETE FROM " + table + " WHERE " + condition;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (condition.contains("?")) {
                ps.setObject(1, cutoff.atOffset(java.time.ZoneOffset.UTC).toLocalDateTime());
            }
            int deleted = ps.executeUpdate();
            if (deleted > 0) {
                Log.infof("[Retention] Purged %s: %d rows (%s)", table, deleted, description);
            }
            return deleted;
        }
    }

    private int anonymiseAuditLog(Connection conn, Instant cutoff) throws Exception {
        String sql = """
            UPDATE audit_log
            SET user_id = '[RETAINED-ANONYMISED]',
                detail  = '[RETAINED-ANONYMISED - 7yr retention]'
            WHERE occurred_at < ?
              AND user_id != '[RETAINED-ANONYMISED]'
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, cutoff.atOffset(java.time.ZoneOffset.UTC).toLocalDateTime());
            return ps.executeUpdate();
        }
    }

    /**
     * Dry run — returns row counts that WOULD be deleted. No actual deletion.
     * Call via: GET /api/admin/retention/dry-run
     */
    public String dryRun() {
        StringBuilder sb = new StringBuilder();
        sb.append("Data Retention Dry Run — ").append(Instant.now()).append("\n\n");

        try (Connection conn = dataSource.getConnection()) {
            appendCount(conn, sb, "intents > 2yr",
                    "SELECT COUNT(*) FROM intents WHERE created_at < ?",
                    Instant.now().minus(730, ChronoUnit.DAYS));
            appendCount(conn, sb, "execution_records > 2yr",
                    "SELECT COUNT(*) FROM execution_records WHERE executed_at < ?",
                    Instant.now().minus(730, ChronoUnit.DAYS));
            appendCount(conn, sb, "intent_plans > 2yr",
                    "SELECT COUNT(*) FROM intent_plans WHERE created_at < ?",
                    Instant.now().minus(730, ChronoUnit.DAYS));
            appendCount(conn, sb, "policy_evaluations > 2yr",
                    "SELECT COUNT(*) FROM policy_evaluations WHERE evaluated_at < ?",
                    Instant.now().minus(730, ChronoUnit.DAYS));
            appendCount(conn, sb, "revoked api_keys > 90d",
                    "SELECT COUNT(*) FROM api_keys WHERE revoked_at IS NOT NULL AND revoked_at < ?",
                    Instant.now().minus(90, ChronoUnit.DAYS));
            appendCount(conn, sb, "expired invitations > 30d",
                    "SELECT COUNT(*) FROM invitations WHERE status IN ('EXPIRED','DECLINED') AND created_at < ?",
                    Instant.now().minus(30, ChronoUnit.DAYS));
        } catch (Exception e) {
            sb.append("ERROR: ").append(e.getMessage());
        }

        return sb.toString();
    }

    private void appendCount(Connection conn, StringBuilder sb,
                              String label, String sql, Instant cutoff) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, cutoff.atOffset(java.time.ZoneOffset.UTC).toLocalDateTime());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    sb.append(String.format("  %-35s %d rows\n", label + ":", rs.getInt(1)));
                }
            }
        }
    }
}
