-- ============================================================
-- V2 — Governance Ledger (Deterministic Replay)
-- Immutable, cryptographically chained ledger of every
-- policy decision made against an intent execution.
--
-- Table: ledger_entry (matches LedgerEntryEntity)
-- ============================================================

CREATE TABLE IF NOT EXISTS ledger_entry (
    id                   UUID         PRIMARY KEY,
    intent_id            UUID         NOT NULL,
    tenant_id            VARCHAR(100) NOT NULL,
    aggregate_version    BIGINT       NOT NULL,
    event_id             UUID,
    event_type           VARCHAR(120),
    policy_snapshot_json TEXT,
    budget_snapshot_json TEXT,
    sla_snapshot_json    TEXT,
    previous_hash        VARCHAR(64),
    current_hash         VARCHAR(64)  NOT NULL,
    timestamp            TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Fast lookup by intent
CREATE INDEX IF NOT EXISTS idx_ledger_entry_intent_id
    ON ledger_entry (intent_id);

-- Tenant + intent scoped queries (replay isolation)
CREATE INDEX IF NOT EXISTS idx_ledger_entry_tenant_intent
    ON ledger_entry (tenant_id, intent_id);

-- Chain ordering — unique version per intent
CREATE UNIQUE INDEX IF NOT EXISTS uq_ledger_entry_intent_version
    ON ledger_entry (intent_id, aggregate_version);
