-- ============================================================
-- V5__fix_ledger_entry_column_names.sql
-- V1 created ledger_entry with all-lowercase column names
-- (PostgreSQL lowercases unquoted identifiers).
-- Hibernate ORM generates snake_case: aggregate_version, intent_id etc.
-- This migration adds snake_case columns and copies existing data.
-- ============================================================

ALTER TABLE ledger_entry
    ADD COLUMN IF NOT EXISTS aggregate_version    BIGINT       NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS intent_id            UUID,
    ADD COLUMN IF NOT EXISTS tenant_id            VARCHAR(255),
    ADD COLUMN IF NOT EXISTS event_id             UUID,
    ADD COLUMN IF NOT EXISTS event_type           VARCHAR(255),
    ADD COLUMN IF NOT EXISTS policy_snapshot_json OID,
    ADD COLUMN IF NOT EXISTS budget_snapshot_json OID,
    ADD COLUMN IF NOT EXISTS sla_snapshot_json    OID,
    ADD COLUMN IF NOT EXISTS previous_hash        VARCHAR(255),
    ADD COLUMN IF NOT EXISTS current_hash         VARCHAR(255);

-- Copy data from old lowercase columns to new snake_case columns
UPDATE ledger_entry SET
    aggregate_version    = aggregateversion,
    intent_id            = intentid,
    tenant_id            = tenantid,
    event_id             = eventid,
    event_type           = eventtype,
    policy_snapshot_json = policysnapshotjson,
    budget_snapshot_json = budgetsnapshotjson,
    sla_snapshot_json    = slasnapshotjson,
    previous_hash        = previoushash,
    current_hash         = currenthash;

-- Fix index to use snake_case column
DROP INDEX IF EXISTS idx_ledger_intent;
CREATE INDEX IF NOT EXISTS idx_ledger_intent_id ON ledger_entry (intent_id);
