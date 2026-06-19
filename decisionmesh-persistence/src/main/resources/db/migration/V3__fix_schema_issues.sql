-- ============================================================
-- V3__fix_schema_issues.sql
--
-- DB-001: adapter_profile_versions missing columns
-- DB-002: execution_records duplicate PK on retry
--
-- Applied: June 2026
-- ============================================================

-- ── DB-001: adapter_profile_versions ─────────────────────────────────────────

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

-- ── DB-002: execution_records ─────────────────────────────────────────────────

-- Step 1: Add attempt_number column
ALTER TABLE execution_records
    ADD COLUMN IF NOT EXISTS attempt_number INT NOT NULL DEFAULT 1;

-- Step 2: Deduplicate existing rows that have same (intent_id, attempt_number=1)
--         Keep the row with the latest executed_at; delete the rest.
--         This is safe — duplicates are artefacts of the retry bug, not real data.
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

-- Step 3: Now safe to create unique index on clean data
CREATE UNIQUE INDEX IF NOT EXISTS uq_exec_intent_attempt
    ON execution_records (intent_id, attempt_number);