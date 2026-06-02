-- ============================================================
-- GDPR RIGHT TO ERASURE — Request tracking table
-- V2: Tracks deletion requests for audit and compliance
-- ============================================================

CREATE TABLE erasure_requests
(
    id           UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    tenant_id    UUID        NOT NULL,
    requested_by VARCHAR(255) NOT NULL,           -- user email or user_id
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    status       VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- PENDING | COMPLETED | FAILED
    reason       TEXT,
    error        TEXT,

    CONSTRAINT chk_erasure_status CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED'))
);

CREATE INDEX idx_erasure_tenant ON erasure_requests (tenant_id);
CREATE INDEX idx_erasure_status ON erasure_requests (status, requested_at DESC);
