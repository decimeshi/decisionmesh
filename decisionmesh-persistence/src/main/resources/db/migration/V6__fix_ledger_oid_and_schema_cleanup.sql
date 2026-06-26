-- ============================================================
-- V6__fix_ledger_oid_and_schema_cleanup.sql
--
-- Fixes:
--   1. ledger_entry snapshot columns created as OID in V5 —
--      OID stores a large object reference (e.g. 4294967295)
--      instead of the actual JSON text, breaking policy snapshot
--      storage and hash chain integrity on Replay.
--      Fix: convert OID → TEXT for all three snapshot columns.
--
--   2. previous_hash and current_hash were VARCHAR(255) in V5
--      but SHA-256 hashes are 64 hex chars. Widen to 64 to be
--      explicit (no data loss — values already fit).
--
--   3. Add timestamp column to ledger_entry if missing
--      (required by LedgerEntryEntity for hash payload).
--
--   4. Add GDPR erasure_requests table (from V2 standalone).
--
--   5. Fix adapter_profile_versions missing columns (from V3).
--
--   6. Add example_payload to intent_library (from V4).
--
-- Applied: June 2026
-- ============================================================

-- ── 1. Fix ledger_entry OID columns → TEXT ────────────────────────────────────
-- The three snapshot columns were accidentally created as OID in V5.
-- PostgreSQL OID stores a large object reference number, not the actual
-- content. Converting to TEXT restores proper JSON storage.

ALTER TABLE ledger_entry
ALTER COLUMN policy_snapshot_json TYPE TEXT USING NULL,
    ALTER COLUMN budget_snapshot_json  TYPE TEXT USING NULL,
    ALTER COLUMN sla_snapshot_json     TYPE TEXT USING NULL;

-- ── 2. Widen hash columns to 64 chars (SHA-256 exact length) ─────────────────
ALTER TABLE ledger_entry
ALTER COLUMN previous_hash TYPE VARCHAR(64),
    ALTER COLUMN current_hash  TYPE VARCHAR(64);

-- ── 3. Add timestamp column if missing ───────────────────────────────────────
ALTER TABLE ledger_entry
    ADD COLUMN IF NOT EXISTS timestamp TIMESTAMPTZ NOT NULL DEFAULT now();

-- ── 4. GDPR erasure_requests (V2) ────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS erasure_requests
(
    id           UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    tenant_id    UUID        NOT NULL,
    requested_by VARCHAR(255) NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    status       VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    reason       TEXT,
    error        TEXT,

    CONSTRAINT chk_erasure_status CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED'))
    );

CREATE INDEX IF NOT EXISTS idx_erasure_tenant ON erasure_requests (tenant_id);
CREATE INDEX IF NOT EXISTS idx_erasure_status ON erasure_requests (status, requested_at DESC);

-- ── 5. adapter_profile_versions missing columns (V3) ─────────────────────────
ALTER TABLE adapter_profile_versions
    ADD COLUMN IF NOT EXISTS adapter_id       UUID    REFERENCES adapters(id) ON DELETE CASCADE,
    ADD COLUMN IF NOT EXISTS ema_cost         FLOAT   NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS ema_latency_ms   FLOAT   NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS ema_success_rate FLOAT   NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS ema_risk_score   FLOAT   NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS ema_confidence   FLOAT   NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS composite_score  FLOAT   NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS execution_count  BIGINT  NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS success_count    BIGINT  NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS failure_count    BIGINT  NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS cold_start       BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS is_degraded      BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS version          INT     NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS snapshotted_at   TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE INDEX IF NOT EXISTS idx_profile_versions_adapter
    ON adapter_profile_versions (adapter_id, tenant_id, snapshotted_at DESC);

CREATE INDEX IF NOT EXISTS idx_profile_versions_profile
    ON adapter_profile_versions (profile_id, snapshotted_at DESC);

-- execution_records deduplication (V3)
ALTER TABLE execution_records
    ADD COLUMN IF NOT EXISTS attempt_number INT NOT NULL DEFAULT 1;

DELETE FROM execution_records
WHERE id IN (
    SELECT id FROM (
                       SELECT id,
                              ROW_NUMBER() OVER (
                   PARTITION BY intent_id, attempt_number
                   ORDER BY executed_at DESC, id
               ) AS rn
                       FROM execution_records
                   ) ranked
    WHERE rn > 1
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_exec_intent_attempt
    ON execution_records (intent_id, attempt_number);

-- ── 6. intent_library example_payload (V4) ───────────────────────────────────
ALTER TABLE intent_library
    ADD COLUMN IF NOT EXISTS example_payload JSONB;

-- ============================================================
-- DONE
-- ============================================================