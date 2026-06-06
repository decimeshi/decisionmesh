-- ============================================================
-- V1__decision_mesh.sql
--
-- Complete DecisionMesh schema — single source of truth.
--
-- Incorporates:
--   • Original base schema
--   • V2: audit_log triggers (fn_audit_intents, fn_audit_api_keys,
--          fn_audit_policies, fn_audit_adapters)
--   • V3: ON DELETE CASCADE folded into every FK that references intents
--   • V4: users.user_id = Keycloak sub (no auto-generate, no external_user_id)
--
-- No ALTER TABLE anywhere — all changes are in CREATE TABLE definitions.
-- ============================================================

CREATE
EXTENSION IF NOT EXISTS pgcrypto;

CREATE
EXTENSION IF NOT EXISTS vector;


-- ============================================================
-- UTILITY FUNCTIONS
-- ============================================================

CREATE
OR REPLACE FUNCTION fn_set_updated_at()
    RETURNS TRIGGER LANGUAGE plpgsql AS
$$
BEGIN
    NEW.updated_at
= now();
RETURN NEW;
END;
$$;

CREATE
OR REPLACE FUNCTION fn_guard_immutable()
    RETURNS TRIGGER LANGUAGE plpgsql AS
$$
BEGIN
    IF
TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'Immutable record violation: table=% id=% (P0001)',
            TG_TABLE_NAME, OLD.id;
    ELSIF
TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Immutable record violation: table=% id=% (P0001)',
            TG_TABLE_NAME, OLD.id;
END IF;
RETURN NULL;
END;
$$;

-- ============================================================
-- TENANTS
-- ============================================================

CREATE TABLE tenants
(
    id                UUID PRIMARY KEY      DEFAULT gen_random_uuid(),
    external_id       VARCHAR(255) NOT NULL,
    name              VARCHAR(255) NOT NULL,
    account_type      VARCHAR(20)  NOT NULL DEFAULT 'INDIVIDUAL', -- INDIVIDUAL | ORGANIZATION
    keycloak_group_id VARCHAR(36)           DEFAULT NULL,         -- only for ORGANIZATION
    status            VARCHAR(50)  NOT NULL DEFAULT 'ACTIVE',
    config            JSONB                 DEFAULT '{}',
    created_at        TIMESTAMPTZ           DEFAULT now(),
    updated_at        TIMESTAMPTZ           DEFAULT now(),

    CONSTRAINT uq_tenants_external_id UNIQUE (external_id),
    CONSTRAINT chk_account_type CHECK (account_type IN ('INDIVIDUAL', 'ORGANIZATION'))
);

-- ============================================================
-- ORGANIZATIONS
-- ============================================================

CREATE TABLE organizations
(
    id           UUID PRIMARY KEY      DEFAULT gen_random_uuid(),
    tenant_id    UUID         NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    name         VARCHAR(255) NOT NULL,
    company_size VARCHAR(20)           DEFAULT NULL, -- e.g. "11-50", "51-200"
    description  VARCHAR(255),
    config       JSONB                 DEFAULT '{}',
    is_active    BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ           DEFAULT now(),
    updated_at   TIMESTAMPTZ           DEFAULT now()
);

-- ============================================================
-- USERS
--
-- V4: user_id is set explicitly from the Keycloak sub claim (UUID).
--     No DEFAULT gen_random_uuid() — backend sets this from JWT sub.
--     external_user_id removed — user_id IS the external identity.
-- ============================================================

CREATE TABLE users
(
    user_id    UUID PRIMARY KEY,                               -- set from Keycloak sub
    tenant_id  UUID REFERENCES tenants (id) ON DELETE CASCADE, -- null until onboarding done
    email      VARCHAR(255),
    name       VARCHAR(255),
    is_active  BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ          DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_users_email UNIQUE (email)
);

-- ============================================================
-- USER ORGANIZATIONS
-- ============================================================

CREATE TABLE user_organizations
(
    id              UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    user_id         UUID REFERENCES users (user_id) ON DELETE CASCADE,
    organization_id UUID REFERENCES organizations (id) ON DELETE CASCADE,
    tenant_id       UUID REFERENCES tenants (id) ON DELETE CASCADE,
    role            VARCHAR(100),
    permissions     JSONB       NOT NULL DEFAULT '[]',
    is_active       BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ          DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_user_organizations_user_org UNIQUE (user_id, organization_id)
);

-- ============================================================
-- ORG BRANDING
-- ============================================================

CREATE TABLE org_branding
(
    tenant_id     UUID PRIMARY KEY REFERENCES tenants (id) ON DELETE CASCADE,
    org_name      VARCHAR(255),
    primary_color VARCHAR(7)           DEFAULT '#2563eb',
    logo_url      VARCHAR(255),
    favicon       VARCHAR(255),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============================================================
-- PROJECTS
-- ============================================================

CREATE TABLE projects
(
    id          UUID PRIMARY KEY      DEFAULT gen_random_uuid(),
    tenant_id   UUID         NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    environment VARCHAR(50)  NOT NULL DEFAULT 'Production',
    is_default  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ           DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_env CHECK (environment IN ('Development', 'Staging', 'Production', 'Sandbox'))
);

CREATE INDEX idx_projects_tenant ON projects (tenant_id);

-- ============================================================
-- MEMBERSHIP
-- ============================================================

CREATE TABLE membership
(
    id             UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    tenant_id      UUID        NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    user_id        UUID        NOT NULL REFERENCES users (user_id) ON DELETE CASCADE,
    project_id     UUID REFERENCES projects (id) ON DELETE CASCADE,
    role           VARCHAR(20) NOT NULL DEFAULT 'VIEWER',
    joined_at      TIMESTAMPTZ          DEFAULT CURRENT_TIMESTAMP,
    last_active_at TIMESTAMPTZ,

    CONSTRAINT chk_member_role CHECK (role IN ('ADMIN', 'ANALYST', 'VIEWER')),
    CONSTRAINT uq_tenant_user_project UNIQUE NULLS NOT DISTINCT (tenant_id, user_id, project_id)
);

CREATE INDEX idx_members_user ON membership (user_id);
CREATE INDEX idx_members_tenant ON membership (tenant_id);
CREATE INDEX idx_members_project ON membership (project_id);

-- ============================================================
-- INVITATIONS
-- ============================================================

CREATE TABLE invitations
(
    id         UUID PRIMARY KEY,
    tenant_id  UUID         NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    project_id UUID REFERENCES projects (id) ON DELETE CASCADE,
    email      VARCHAR(255) NOT NULL,
    role       VARCHAR(20)  NOT NULL DEFAULT 'VIEWER',
    status     VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    token      VARCHAR(64)  NOT NULL UNIQUE,
    created_at TIMESTAMPTZ           DEFAULT now(),
    expires_at TIMESTAMPTZ
);

CREATE INDEX idx_invitations_tenant ON invitations (tenant_id);
CREATE INDEX idx_invitations_token ON invitations (token);
CREATE INDEX idx_invitations_email ON invitations (email);

-- ============================================================
-- API KEYS
-- ============================================================

CREATE TABLE api_keys
(
    key_id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID REFERENCES tenants (id) ON DELETE CASCADE,
    organization_id    UUID REFERENCES organizations (id) ON DELETE CASCADE,
    user_id            UUID,
    created_by_user_id UUID,
    name               VARCHAR(255),
    key_hash           VARCHAR(255) UNIQUE,
    key_prefix         VARCHAR(20),
    scopes             JSONB            DEFAULT '[]',
    active             BOOLEAN          DEFAULT TRUE,
    revoked_at         TIMESTAMPTZ,
    revoked_by         VARCHAR(255),
    last_used_at       TIMESTAMPTZ,
    usage_count        BIGINT           DEFAULT 0,
    created_at         TIMESTAMPTZ      DEFAULT now(),
    created_by         VARCHAR(255),
    expires_at         TIMESTAMPTZ,
    ip_whitelist       JSONB            DEFAULT '[]',
    rate_limit         INTEGER
);

CREATE INDEX idx_api_keys_tenant ON api_keys (tenant_id);
CREATE INDEX idx_api_keys_user_id ON api_keys (user_id);
CREATE INDEX idx_api_keys_key_hash ON api_keys (key_hash);

-- ============================================================
-- ADAPTERS
-- ============================================================
CREATE TABLE adapters
(
    id                   UUID PRIMARY KEY      DEFAULT gen_random_uuid(),
    tenant_id            UUID REFERENCES tenants (id) ON DELETE CASCADE,
    name                 VARCHAR(255) NOT NULL,
    adapter_type         VARCHAR(100)
        CHECK (adapter_type IN ('LLM', 'EMBEDDING', 'TOOL', 'RETRIEVAL',
                                'RERANKER', 'CLASSIFIER', 'CUSTOM')),
    provider             VARCHAR(100),
    model_id             VARCHAR(255),
    region               VARCHAR(100),
    base_cost_per_token  NUMERIC(18, 8),
    max_tokens_per_call  INT,
    avg_latency_ms       BIGINT,
    config               JSONB        NOT NULL DEFAULT '{}',
    capability_flags     JSONB        NOT NULL DEFAULT '{}',
    allowed_intent_types JSONB        NOT NULL DEFAULT '[]',
    is_active            BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_adapters_tenant ON adapters (tenant_id);
CREATE INDEX idx_adapters_type ON adapters (tenant_id, adapter_type);
CREATE INDEX idx_adapters_provider ON adapters (tenant_id, provider);
CREATE INDEX idx_adapters_active ON adapters (tenant_id, is_active);

CREATE TRIGGER trg_adapters_updated_at
    BEFORE UPDATE
    ON adapters
    FOR EACH ROW EXECUTE FUNCTION fn_set_updated_at();

-- ============================================================
-- INTENTS
-- ============================================================

CREATE TABLE intents
(
    id                 UUID PRIMARY KEY,
    tenant_id          UUID         NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    user_id            UUID,
    intent_type        VARCHAR(255) NOT NULL,
    phase              VARCHAR(50)  NOT NULL,
    satisfaction_state VARCHAR(50)  NOT NULL DEFAULT 'UNKNOWN',
    retry_count        INTEGER      NOT NULL DEFAULT 0,
    max_retries        INTEGER      NOT NULL DEFAULT 0,
    terminal           BOOLEAN      NOT NULL DEFAULT FALSE,
    version            BIGINT       NOT NULL DEFAULT 0,
    model_tier         VARCHAR(20),
    payload            JSONB        NOT NULL,
    injection_risk     NUMERIC(5, 4)         DEFAULT 0,
    created_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_intents_tenant ON intents (tenant_id, created_at DESC);
CREATE INDEX idx_intents_tenant_phase ON intents (tenant_id, phase, created_at DESC);
CREATE INDEX idx_intents_tenant_type ON intents (tenant_id, intent_type, created_at DESC);
CREATE INDEX idx_intents_terminal ON intents (tenant_id, terminal) WHERE terminal = FALSE;
CREATE INDEX idx_intent_injection ON intents (injection_risk) WHERE injection_risk > 0.5;


-- V5__create_intent_library_complete.sql

-- Drop and recreate if the broken version was already applied
DROP TABLE IF EXISTS intent_library;

CREATE TABLE intent_library
(
    id             UUID PRIMARY KEY      DEFAULT gen_random_uuid(),

    name           VARCHAR(255) NOT NULL,
    category       VARCHAR(100) NOT NULL,
    category_label VARCHAR(100),

    vertical       VARCHAR(50)  NOT NULL DEFAULT 'FINTECH',

    description    TEXT,

    risk_level     VARCHAR(50),
    default_policy VARCHAR(50),
    sla_ms         INTEGER,
    regulatory_ref VARCHAR(255),
    tags           TEXT, -- ← was JSONB
    is_active      BOOLEAN      NOT NULL DEFAULT true,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_intent_library_name_category_vertical
        UNIQUE (name, category, vertical)
);

CREATE INDEX idx_intent_lookup
    ON intent_library (name, category, vertical) WHERE is_active = true;

CREATE INDEX idx_intent_vertical_category
    ON intent_library (vertical, category) WHERE is_active = true;

CREATE INDEX idx_intent_risk
    ON intent_library (risk_level);

CREATE INDEX idx_intent_tags
    ON intent_library USING GIN ((tags::jsonb));
-- ← cast to jsonb for GIN
-- ============================================================
-- PLANS
-- V3: ON DELETE CASCADE folded into FK definitions
-- ============================================================

CREATE TABLE intent_plans
(
    id                UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    intent_id         UUID REFERENCES intents (id) ON DELETE CASCADE,
    tenant_id         UUID REFERENCES tenants (id) ON DELETE CASCADE,
    plan_version      INT                  DEFAULT 1,
    strategy          VARCHAR(50) NOT NULL DEFAULT 'SINGLE_ADAPTER',
    status            VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    was_exploration   BOOLEAN     NOT NULL DEFAULT FALSE,
    ranking_snapshot  JSONB       NOT NULL DEFAULT '{}',
    budget_allocation JSONB       NOT NULL DEFAULT '{}',
    planner_notes     TEXT,
    created_at        TIMESTAMPTZ          DEFAULT now()
);

CREATE INDEX idx_plans_intent ON intent_plans (intent_id);
CREATE INDEX idx_plans_tenant ON intent_plans (tenant_id);

CREATE TABLE intent_plan_steps
(
    id                   UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    plan_id              UUID REFERENCES intent_plans (id) ON DELETE CASCADE,
    intent_id            UUID REFERENCES intents (id) ON DELETE CASCADE,
    tenant_id            UUID REFERENCES tenants (id) ON DELETE CASCADE,
    adapter_id           UUID        REFERENCES adapters (id) ON DELETE SET NULL,
    step_order           INT,
    step_type            VARCHAR(50) NOT NULL DEFAULT 'LLM_CALL',
    is_conditional       BOOLEAN     NOT NULL DEFAULT FALSE,
    condition_expr       JSONB,
    config_snapshot      JSONB       NOT NULL DEFAULT '{}',
    estimated_cost_usd   NUMERIC(12, 6),
    estimated_latency_ms BIGINT,
    created_at           TIMESTAMPTZ          DEFAULT now()
);

CREATE INDEX idx_plan_steps_plan ON intent_plan_steps (plan_id);
CREATE INDEX idx_plan_steps_intent ON intent_plan_steps (intent_id);

-- ============================================================
-- EXECUTION RECORDS
-- V3: ON DELETE CASCADE folded into FK definitions
-- ============================================================

CREATE TABLE execution_records
(
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    intent_id              UUID REFERENCES intents (id) ON DELETE CASCADE,
    tenant_id              UUID REFERENCES tenants (id) ON DELETE CASCADE,
    adapter_id             UUID    REFERENCES adapters (id) ON DELETE SET NULL,
    plan_id                UUID REFERENCES intent_plans (id) ON DELETE CASCADE,
    plan_step_id           UUID REFERENCES intent_plan_steps (id) ON DELETE CASCADE,
    status                 VARCHAR(50),
    cost_usd               NUMERIC(12, 6),
    latency_ms             BIGINT,
    prompt_tokens          INT              DEFAULT 0,
    completion_tokens      INT              DEFAULT 0,
    total_tokens           INT              DEFAULT 0,
    risk_score             NUMERIC(5, 4)    DEFAULT 0,
    failure_reason         VARCHAR(255),
    response_text          TEXT,
    quality_score          NUMERIC(5, 4),
    hallucination_risk     NUMERIC(5, 4),
    hallucination_detected BOOLEAN          DEFAULT FALSE,
    quality_reasoning      VARCHAR(500),
    metadata               JSONB            DEFAULT '{}',
    executed_at            TIMESTAMPTZ      DEFAULT now(),
    cache_read_tokens      INT     NOT NULL DEFAULT 0,
    cache_write_tokens     INT     NOT NULL DEFAULT 0,
    cache_hit              BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_exec_tenant_time ON execution_records (tenant_id, executed_at DESC);
CREATE INDEX idx_exec_adapter_status ON execution_records (adapter_id, status, executed_at DESC);
CREATE INDEX idx_exec_hallucination ON execution_records (hallucination_detected, adapter_id) WHERE hallucination_detected = TRUE;
CREATE INDEX idx_exec_quality ON execution_records (quality_score, adapter_id) WHERE quality_score IS NOT NULL;

-- ============================================================
-- SPEND RECORDS
-- V3: ON DELETE CASCADE folded into FK definitions
-- ============================================================

CREATE TABLE spend_records
(
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    intent_id          UUID REFERENCES intents (id) ON DELETE CASCADE,
    execution_id       UUID REFERENCES execution_records (id) ON DELETE CASCADE,
    tenant_id          UUID REFERENCES tenants (id) ON DELETE CASCADE,
    adapter_id         UUID REFERENCES adapters (id) ON DELETE SET NULL,
    amount_usd         NUMERIC(12, 6),
    token_count        INT              DEFAULT 0,
    budget_ceiling_usd NUMERIC(12, 6),
    recorded_at        TIMESTAMPTZ      DEFAULT now()
);

CREATE INDEX idx_spend_tenant_time ON spend_records (tenant_id, recorded_at DESC);
CREATE INDEX idx_spend_adapter_tenant ON spend_records (adapter_id, tenant_id);
CREATE INDEX idx_spend_intent ON spend_records (intent_id);
CREATE INDEX idx_spend_tenant ON spend_records (tenant_id);

-- ============================================================
-- SLA / DRIFT
-- V3: ON DELETE CASCADE folded into FK definitions
-- ============================================================

CREATE TABLE sla_windows
(
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    intent_id   UUID REFERENCES intents (id) ON DELETE CASCADE,
    tenant_id   UUID REFERENCES tenants (id) ON DELETE CASCADE,
    deadline_ms BIGINT
);

CREATE TABLE intent_drift_evaluations
(
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    intent_id    UUID REFERENCES intents (id) ON DELETE CASCADE,
    execution_id UUID REFERENCES execution_records (id) ON DELETE CASCADE,
    tenant_id    UUID REFERENCES tenants (id) ON DELETE CASCADE,
    drift_score  NUMERIC(5, 4)
);

-- ============================================================
-- POLICIES
-- V3: ON DELETE CASCADE folded into FK definitions
-- ============================================================

CREATE TABLE policies
(
    id               UUID PRIMARY KEY      DEFAULT gen_random_uuid(),
    tenant_id        UUID REFERENCES tenants (id) ON DELETE CASCADE,
    name             VARCHAR(255),
    description      TEXT,
    scope            VARCHAR(50)  NOT NULL DEFAULT 'TENANT',
    scope_ref_id     UUID,
    phase            VARCHAR(50)  NOT NULL DEFAULT 'PRE_EXECUTION',
    enforcement_mode VARCHAR(50)  NOT NULL DEFAULT 'LOG_ONLY',
    policy_type      VARCHAR(100) NOT NULL DEFAULT 'CUSTOM_DSL',
    rule_dsl         JSONB        NOT NULL DEFAULT '{}',
    priority         INTEGER      NOT NULL DEFAULT 100,
    is_active        BOOLEAN      NOT NULL DEFAULT TRUE,
    version          INTEGER      NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ           DEFAULT now(),
    updated_at       TIMESTAMPTZ           DEFAULT now()
);

CREATE INDEX idx_policies_tenant ON policies (tenant_id);
CREATE INDEX idx_policies_phase ON policies (tenant_id, phase, is_active);

CREATE TABLE policy_evaluations
(
    id               UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    intent_id        UUID REFERENCES intents (id) ON DELETE CASCADE,
    policy_id        UUID REFERENCES policies (id) ON DELETE CASCADE,
    tenant_id        UUID REFERENCES tenants (id) ON DELETE CASCADE,
    adapter_id       UUID        REFERENCES adapters (id) ON DELETE SET NULL,
    result           VARCHAR(50),
    phase            VARCHAR(50) NOT NULL DEFAULT 'PRE_EXECUTION',
    enforcement_mode VARCHAR(50) NOT NULL DEFAULT 'LOG_ONLY',
    block_reason     VARCHAR(512),
    attempt_number   INTEGER,
    context_snapshot JSONB       NOT NULL DEFAULT '{}',
    evaluated_at     TIMESTAMPTZ          DEFAULT now()
);

CREATE INDEX idx_poleval_intent ON policy_evaluations (intent_id);
CREATE INDEX idx_poleval_tenant ON policy_evaluations (tenant_id);

-- ============================================================
-- ADAPTER PERFORMANCE PROFILES
-- ============================================================

CREATE TABLE adapter_performance_profiles
(
    id                   UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    adapter_id           UUID        NOT NULL REFERENCES adapters (id) ON DELETE CASCADE,
    tenant_id            UUID        NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    ema_cost             float(53)   NOT NULL DEFAULT 0,
    ema_latency_ms       float(53)   NOT NULL DEFAULT 0,
    ema_success_rate     float(53)   NOT NULL DEFAULT 1,
    ema_risk_score       float(53)   NOT NULL DEFAULT 0,
    ema_confidence       float(53)   NOT NULL DEFAULT 0,
    composite_score      float(53)   NOT NULL DEFAULT 0,
    execution_count      BIGINT      NOT NULL DEFAULT 0,
    success_count        BIGINT      NOT NULL DEFAULT 0,
    failure_count        BIGINT      NOT NULL DEFAULT 0,
    cold_start           BOOLEAN     NOT NULL DEFAULT TRUE,
    cold_start_threshold INT         NOT NULL DEFAULT 10,
    is_degraded          BOOLEAN     NOT NULL DEFAULT FALSE,
    degraded_since       TIMESTAMPTZ,
    degraded_reason      VARCHAR(255),
    last_executed_at     TIMESTAMPTZ,
    version              INT         NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_profile_adapter_tenant UNIQUE (adapter_id, tenant_id)
);

CREATE INDEX idx_profile_tenant ON adapter_performance_profiles (tenant_id);
CREATE INDEX idx_profile_composite ON adapter_performance_profiles (tenant_id, composite_score DESC) WHERE is_degraded = FALSE;
CREATE INDEX idx_profile_degraded ON adapter_performance_profiles (tenant_id, is_degraded) WHERE is_degraded = TRUE;

CREATE TRIGGER trg_profile_updated_at
    BEFORE UPDATE
    ON adapter_performance_profiles
    FOR EACH ROW EXECUTE FUNCTION fn_set_updated_at();

CREATE TABLE adapter_profile_versions
(
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    profile_id UUID REFERENCES adapter_performance_profiles (id) ON DELETE CASCADE,
    tenant_id  UUID REFERENCES tenants (id) ON DELETE CASCADE
);

-- ============================================================
-- RATE LIMITING
-- ============================================================

CREATE TABLE rate_limit_configs
(
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID REFERENCES tenants (id) ON DELETE CASCADE
);

CREATE TABLE rate_limit_counters
(
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    config_id UUID REFERENCES rate_limit_configs (id) ON DELETE CASCADE,
    tenant_id UUID REFERENCES tenants (id) ON DELETE CASCADE
);

-- ============================================================
-- INTENT EVENTS (immutable append-only)
-- ============================================================

CREATE TABLE intent_events
(
    id                   UUID PRIMARY KEY      DEFAULT gen_random_uuid(),
    event_id             UUID         NOT NULL,
    intent_id            UUID         NOT NULL,
    tenant_id            UUID         NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    version              BIGINT       NOT NULL,
    event_type           VARCHAR(255) NOT NULL,
    aggregate_type       VARCHAR(255) NOT NULL DEFAULT 'Intent',
    occurred_at          TIMESTAMPTZ  NOT NULL,
    payload              JSONB        NOT NULL,
    phase_from           VARCHAR(50),
    phase_to             VARCHAR(50),
    actor_id             UUID,
    actor_type           VARCHAR(100),
    plan_id              UUID,
    plan_version         INTEGER,
    execution_id         UUID,
    attempt_number       INTEGER,
    adapter_id           UUID,
    policy_id            UUID,
    drift_score_snapshot NUMERIC(5, 4),
    cost_usd_snapshot    NUMERIC(12, 6),
    risk_score_snapshot  NUMERIC(5, 4),
    trace_id             VARCHAR(64),
    span_id              VARCHAR(64),
    parent_span_id       VARCHAR(64),

    CONSTRAINT uq_intent_events_event_id UNIQUE (event_id),
    CONSTRAINT uq_intent_events_version UNIQUE (intent_id, version)
);

CREATE INDEX idx_events_intent ON intent_events (intent_id, occurred_at ASC);
CREATE INDEX idx_events_intent_version ON intent_events (intent_id, version);
CREATE INDEX idx_events_tenant_time ON intent_events (tenant_id, occurred_at DESC);
CREATE INDEX idx_events_tenant ON intent_events (tenant_id);
CREATE INDEX idx_events_type ON intent_events (tenant_id, event_type, occurred_at DESC);
CREATE INDEX idx_events_trace ON intent_events (tenant_id, trace_id) WHERE trace_id IS NOT NULL;

CREATE TRIGGER trg_intent_events_no_update
    BEFORE UPDATE
    ON intent_events
    FOR EACH ROW EXECUTE FUNCTION fn_guard_immutable();

CREATE TRIGGER trg_intent_events_no_delete
    BEFORE DELETE
    ON intent_events
    FOR EACH ROW EXECUTE FUNCTION fn_guard_immutable();

-- ============================================================
-- AUDIT LOG
-- ============================================================

CREATE TABLE audit_log
(
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID REFERENCES tenants (id) ON DELETE CASCADE,
    user_id       VARCHAR(255),
    entity_type   VARCHAR(100),
    entity_id     UUID,
    resource_type VARCHAR(100),
    resource_id   VARCHAR(255),
    action        VARCHAR(100),
    outcome       VARCHAR(20)      DEFAULT 'SUCCESS',
    detail        TEXT,
    occurred_at   TIMESTAMPTZ      DEFAULT now()
);

CREATE INDEX idx_audit_tenant ON audit_log (tenant_id);
CREATE INDEX idx_audit_occurred_at ON audit_log (occurred_at DESC);
CREATE INDEX idx_audit_resource ON audit_log (resource_type, resource_id);

-- ============================================================
-- IDEMPOTENCY
-- ============================================================

CREATE TABLE tenant_idempotency
(
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID         NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    idempotency_key VARCHAR(255) NOT NULL,
    created_at      TIMESTAMPTZ      DEFAULT now(),

    CONSTRAINT uq_tenant_idempotency UNIQUE (tenant_id, idempotency_key)
);

CREATE INDEX idx_tenant_idempotency_key ON tenant_idempotency (idempotency_key);
CREATE INDEX idx_tenant_idempotency_tenant ON tenant_idempotency (tenant_id);

-- ============================================================
-- GOVERNANCE: LEDGER / POLICY SNAPSHOT / PROCESSED EVENTS
-- ============================================================

CREATE TABLE ledger_entry
(
    id                 UUID PRIMARY KEY,
    intentId           UUID,
    tenantId           VARCHAR(255),
    aggregateVersion   BIGINT NOT NULL DEFAULT 0,
    eventId            UUID,
    eventType          VARCHAR(255),
    policySnapshotJson OID,
    budgetSnapshotJson OID,
    slaSnapshotJson    OID,
    previousHash       VARCHAR(255),
    currentHash        VARCHAR(255),
    timestamp          TIMESTAMPTZ
);

CREATE INDEX idx_ledger_intent ON ledger_entry (intentId);

CREATE TABLE policy_snapshot
(
    id           UUID PRIMARY KEY,
    intentId     UUID,
    version      BIGINT,
    snapshotJson OID
);

CREATE TABLE processed_events
(
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id     VARCHAR(255) NOT NULL UNIQUE,
    processed_at TIMESTAMPTZ      DEFAULT now()
);

CREATE INDEX idx_processed_events_event_id ON processed_events (event_id);

-- ============================================================
-- BILLING
-- ============================================================

CREATE TABLE credit_ledger
(
    id           UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    org_id       UUID        NOT NULL,
    amount       INTEGER     NOT NULL,
    reason       VARCHAR(30) NOT NULL,
    reference_id VARCHAR(255),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT
ON TABLE  credit_ledger              IS 'Append-only ledger — positive=credit, negative=debit';
COMMENT
ON COLUMN credit_ledger.reason       IS 'REGISTRATION_GIFT|SUBSCRIPTION|PURCHASE|REFERRAL|INTENT_EXECUTION|RETRY|REFUND|ADMIN_ADJUSTMENT';
COMMENT
ON COLUMN credit_ledger.reference_id IS 'intent_id for executions, stripe session_id for purchases';

CREATE INDEX idx_credit_ledger_org_id ON credit_ledger (org_id);
CREATE INDEX idx_credit_ledger_created_at ON credit_ledger (created_at);
CREATE INDEX idx_credit_ledger_reason ON credit_ledger (reason);

CREATE TABLE subscription
(
    id                   UUID PRIMARY KEY      DEFAULT gen_random_uuid(),
    orgId                UUID,
    stripeCustomerId     VARCHAR(255),
    stripeSubscriptionId VARCHAR(255),
    plan                 VARCHAR(255) NOT NULL DEFAULT 'FREE',
    status               VARCHAR(255) NOT NULL DEFAULT 'ACTIVE',
    createdAt            TIMESTAMPTZ(6),
    updatedAt            TIMESTAMPTZ(6)
);

CREATE INDEX idx_subscription_org_id ON subscription (orgId);
CREATE INDEX idx_subscription_stripe_sub ON subscription (stripeSubscriptionId);

CREATE TABLE billing_customer
(
    org_id           UUID PRIMARY KEY,
    orgId            UUID,
    stripeCustomerId VARCHAR(255),

    CONSTRAINT UKf8ybrcbugt66p970i9lmseiaq UNIQUE (stripeCustomerId)
);

CREATE INDEX idx_billing_customer_stripe ON billing_customer (stripeCustomerId);

-- ============================================================
-- OBSERVABILITY / EXPLAINABILITY
-- ============================================================

CREATE TABLE decision_traces
(
    decision_id       UUID PRIMARY KEY,
    intent_id         UUID        NOT NULL,
    tenant_id         VARCHAR     NOT NULL,
    decision_type     VARCHAR     NOT NULL,
    inputs_snapshot   JSONB,
    scoring_snapshot  JSONB,
    policy_snapshot   JSONB,
    portfolio_context JSONB,
    rationale         TEXT,
    timestamp         TIMESTAMPTZ NOT NULL
);

CREATE TABLE decision_trace_links
(
    parent_decision_id UUID NOT NULL,
    child_decision_id  UUID NOT NULL,
    PRIMARY KEY (parent_decision_id, child_decision_id)
);

CREATE TABLE intent_evaluations
(
    intent_id          UUID             NOT NULL,
    satisfaction_score DOUBLE PRECISION NOT NULL,
    drift_score        DOUBLE PRECISION NOT NULL,
    evaluated_at       TIMESTAMPTZ      NOT NULL
);

-- ============================================================
-- EVENTSOURCING / OUTBOX / MULTI-REGION
-- ============================================================

CREATE TABLE event_outbox
(
    id             UUID PRIMARY KEY,
    aggregate_type VARCHAR     NOT NULL,
    aggregate_id   UUID        NOT NULL,
    event_type     VARCHAR     NOT NULL,
    payload_json   JSONB       NOT NULL,
    published      BOOLEAN DEFAULT FALSE,
    created_at     TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_event_outbox_published ON event_outbox (published, created_at) WHERE published = FALSE;

CREATE TABLE consumer_offsets
(
    consumer_id  VARCHAR     NOT NULL,
    event_id     UUID        NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (consumer_id, event_id)
);

CREATE TABLE intent_dependencies
(
    parent_intent_id UUID        NOT NULL,
    child_intent_id  UUID        NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (parent_intent_id, child_intent_id)
);

CREATE TABLE intent_region_registry
(
    intent_id       UUID PRIMARY KEY,
    tenant_id       VARCHAR     NOT NULL,
    home_region     VARCHAR     NOT NULL,
    failover_region VARCHAR,
    updated_at      TIMESTAMPTZ NOT NULL
);

CREATE TABLE global_idempotency
(
    idempotency_key VARCHAR PRIMARY KEY,
    created_at      TIMESTAMPTZ NOT NULL
);

CREATE TABLE lifecycle_audit
(
    intent_id UUID        NOT NULL,
    phase     VARCHAR     NOT NULL,
    action    VARCHAR     NOT NULL,
    version   BIGINT      NOT NULL,
    timestamp TIMESTAMPTZ NOT NULL
);

CREATE TABLE drift_tracking
(
    intent_id  UUID PRIMARY KEY,
    last_drift DOUBLE PRECISION NOT NULL,
    updated_at TIMESTAMPTZ      NOT NULL
);

CREATE TABLE exploration_ledger
(
    entry_id    UUID PRIMARY KEY,
    adapter_id  VARCHAR          NOT NULL,
    intent_type VARCHAR          NOT NULL,
    exploration BOOLEAN          NOT NULL,
    reward      DOUBLE PRECISION NOT NULL,
    regret      DOUBLE PRECISION NOT NULL,
    confidence  DOUBLE PRECISION NOT NULL,
    timestamp   TIMESTAMPTZ      NOT NULL
);

CREATE TABLE user_feedback
(
    id         UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    user_id    UUID        NOT NULL REFERENCES users (user_id) ON DELETE CASCADE,
    rating     SMALLINT    NOT NULL CHECK (rating BETWEEN 1 AND 5),
    category   VARCHAR(20) NOT NULL CHECK (category IN ('bug', 'feature', 'billing', 'general')),
    comment    TEXT,
    page       VARCHAR(255),
    user_agent TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Index for querying by user and category
CREATE INDEX idx_user_feedback_user_id ON user_feedback (user_id);
CREATE INDEX idx_user_feedback_category ON user_feedback (category);
CREATE INDEX idx_user_feedback_created_at ON user_feedback (created_at DESC);

CREATE TABLE IF NOT EXISTS webhook_events
(
    id
    UUID
    PRIMARY
    KEY
    DEFAULT
    gen_random_uuid
(
),
    gateway VARCHAR
(
    20
) NOT NULL CHECK
(
    gateway
    IN
(
    'stripe',
    'razorpay'
)),
    event_type VARCHAR
(
    100
) NOT NULL,
    payload TEXT,
    status VARCHAR
(
    20
) NOT NULL DEFAULT 'received'
    CHECK
(
    status
    IN
(
    'received',
    'processed',
    'failed'
)),
    error TEXT,
    org_id UUID,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now
(
),
    processed_at TIMESTAMPTZ
    );

CREATE INDEX IF NOT EXISTS idx_webhook_events_gateway
    ON webhook_events(gateway);

CREATE INDEX IF NOT EXISTS idx_webhook_events_status
    ON webhook_events(status);

CREATE INDEX IF NOT EXISTS idx_webhook_events_received_at
    ON webhook_events(received_at DESC);

CREATE INDEX IF NOT EXISTS idx_webhook_events_org_id
    ON webhook_events(org_id)
    WHERE org_id IS NOT NULL;

-- ============================================================
-- AUDIT TRIGGERS  (V2)
--
-- Write to audit_log automatically on INSERT/UPDATE/DELETE
-- for intents, api_keys, policies, adapters.
--
-- User identity: app sets current_setting('app.current_user_id')
-- on each connection before writes. Falls back to NULL gracefully.
--   SET LOCAL "app.current_user_id" = '<user-uuid>';
-- ============================================================

CREATE
OR REPLACE FUNCTION fn_current_audit_user()
    RETURNS VARCHAR(255) LANGUAGE plpgsql AS
$$
BEGIN
RETURN current_setting('app.current_user_id', TRUE); -- TRUE = missing_ok
EXCEPTION
    WHEN OTHERS THEN RETURN NULL;
END;
$$;

-- ── INTENTS ──────────────────────────────────────────────────────────────────

CREATE
OR REPLACE FUNCTION fn_audit_intents()
    RETURNS TRIGGER LANGUAGE plpgsql AS
$$
DECLARE
v_action VARCHAR(100);
    v_detail
TEXT;
    v_uid
VARCHAR(255);
BEGIN
    v_uid
:= COALESCE(fn_current_audit_user(), NEW.user_id::TEXT);

    IF
TG_OP = 'INSERT' THEN
        v_action := 'INTENT_SUBMITTED';
        v_detail
:= 'type=' || NEW.intent_type || ' phase=' || NEW.phase;

    ELSIF
TG_OP = 'UPDATE' THEN
        IF NEW.terminal = TRUE AND OLD.terminal = FALSE THEN
            v_action := CASE NEW.satisfaction_state
                            WHEN 'SATISFIED' THEN 'INTENT_SATISFIED'
                            WHEN 'VIOLATED'  THEN 'INTENT_VIOLATED'
                            ELSE                  'INTENT_COMPLETED'
END;
            v_detail
:= 'phase=' || NEW.phase
                || ' satisfaction=' || NEW.satisfaction_state
                || ' retries='      || NEW.retry_count;
        ELSIF
NEW.phase IS DISTINCT FROM OLD.phase THEN
            v_action := 'INTENT_PHASE_CHANGED';
            v_detail
:= 'from=' || COALESCE(OLD.phase, '—') || ' to=' || NEW.phase;
ELSE
            RETURN NEW; -- uninteresting update — skip
END IF;

    ELSIF
TG_OP = 'DELETE' THEN
        v_uid    := COALESCE(fn_current_audit_user(), OLD.user_id::TEXT);
        v_action
:= 'INTENT_DELETED';
        v_detail
:= 'type=' || OLD.intent_type || ' phase=' || OLD.phase;
INSERT INTO audit_log (tenant_id, user_id, entity_type, entity_id, action, detail)
VALUES (OLD.tenant_id, v_uid, 'INTENT', OLD.id, v_action, v_detail);
RETURN OLD;
END IF;

INSERT INTO audit_log (tenant_id, user_id, entity_type, entity_id, action, detail)
VALUES (NEW.tenant_id, v_uid, 'INTENT', NEW.id, v_action, v_detail);
RETURN NEW;
END;
$$;

CREATE TRIGGER trg_audit_intents
    AFTER INSERT OR
UPDATE OR
DELETE
ON intents
    FOR EACH ROW EXECUTE FUNCTION fn_audit_intents();

-- ── API KEYS ─────────────────────────────────────────────────────────────────

CREATE
OR REPLACE FUNCTION fn_audit_api_keys()
    RETURNS TRIGGER LANGUAGE plpgsql AS
$$
DECLARE
v_action VARCHAR(100);
    v_detail
TEXT;
    v_uid
VARCHAR(255);
BEGIN
    IF
TG_OP = 'DELETE' THEN
        v_uid    := COALESCE(fn_current_audit_user(), OLD.created_by_user_id::TEXT);
        v_action
:= 'API_KEY_DELETED';
        v_detail
:= 'prefix=' || OLD.key_prefix || ' name=' || COALESCE(OLD.name, '—');
INSERT INTO audit_log (tenant_id, user_id, entity_type, entity_id, action, detail)
VALUES (OLD.tenant_id, v_uid, 'API_KEY', OLD.key_id, v_action, v_detail);
RETURN OLD;
END IF;

    v_uid
:= COALESCE(fn_current_audit_user(), NEW.created_by_user_id::TEXT);

    IF
TG_OP = 'INSERT' THEN
        v_action := 'API_KEY_CREATED';
        v_detail
:= 'prefix=' || NEW.key_prefix
            || ' name='  || COALESCE(NEW.name, '—')
            || ' scopes=' || COALESCE(NEW.scopes::TEXT, '[]');
    ELSIF
TG_OP = 'UPDATE' THEN
        IF NEW.revoked_at IS NOT NULL AND OLD.revoked_at IS NULL THEN
            v_action := 'API_KEY_REVOKED';
            v_detail
:= 'prefix=' || NEW.key_prefix || ' name=' || COALESCE(NEW.name, '—');
ELSE
            RETURN NEW; -- usage_count / last_used_at update — skip
END IF;
END IF;

INSERT INTO audit_log (tenant_id, user_id, entity_type, entity_id, action, detail)
VALUES (NEW.tenant_id, v_uid, 'API_KEY', NEW.key_id, v_action, v_detail);
RETURN NEW;
END;
$$;

CREATE TRIGGER trg_audit_api_keys
    AFTER INSERT OR
UPDATE OR
DELETE
ON api_keys
    FOR EACH ROW EXECUTE FUNCTION fn_audit_api_keys();

-- ── POLICIES ─────────────────────────────────────────────────────────────────

CREATE
OR REPLACE FUNCTION fn_audit_policies()
    RETURNS TRIGGER LANGUAGE plpgsql AS
$$
DECLARE
v_action VARCHAR(100);
    v_detail
TEXT;
    v_uid
VARCHAR(255);
BEGIN
    v_uid
:= fn_current_audit_user();

    IF
TG_OP = 'DELETE' THEN
        v_action := 'POLICY_DELETED';
        v_detail
:= 'name=' || COALESCE(OLD.name, '—') || ' phase=' || OLD.phase;
INSERT INTO audit_log (tenant_id, user_id, entity_type, entity_id, action, detail)
VALUES (OLD.tenant_id, v_uid, 'POLICY', OLD.id, v_action, v_detail);
RETURN OLD;
END IF;

    v_action
:= CASE TG_OP WHEN 'INSERT' THEN 'POLICY_CREATED' ELSE 'POLICY_UPDATED'
END;
    v_detail
:= 'name=' || COALESCE(NEW.name, '—')
        || ' phase='       || NEW.phase
        || ' enforcement=' || NEW.enforcement_mode;

INSERT INTO audit_log (tenant_id, user_id, entity_type, entity_id, action, detail)
VALUES (NEW.tenant_id, v_uid, 'POLICY', NEW.id, v_action, v_detail);
RETURN NEW;
END;
$$;

CREATE TRIGGER trg_audit_policies
    AFTER INSERT OR
UPDATE OR
DELETE
ON policies
    FOR EACH ROW EXECUTE FUNCTION fn_audit_policies();

-- ── ADAPTERS ─────────────────────────────────────────────────────────────────

CREATE
OR REPLACE FUNCTION fn_audit_adapters()
    RETURNS TRIGGER LANGUAGE plpgsql AS
$$
DECLARE
v_action VARCHAR(100);
    v_detail
TEXT;
    v_uid
VARCHAR(255);
BEGIN
    v_uid
:= fn_current_audit_user();

    IF
TG_OP = 'DELETE' THEN
        v_action := 'ADAPTER_DELETED';
        v_detail
:= 'name=' || OLD.name || ' provider=' || COALESCE(OLD.provider, '—');
INSERT INTO audit_log (tenant_id, user_id, entity_type, entity_id, action, detail)
VALUES (OLD.tenant_id, v_uid, 'ADAPTER', OLD.id, v_action, v_detail);
RETURN OLD;
END IF;

    IF
TG_OP = 'UPDATE' AND NEW.is_active IS DISTINCT FROM OLD.is_active THEN
        v_action := CASE WHEN NEW.is_active THEN 'ADAPTER_ENABLED' ELSE 'ADAPTER_DISABLED'
END;
    ELSIF
TG_OP = 'INSERT' THEN
        v_action := 'ADAPTER_CREATED';
ELSE
        v_action := 'ADAPTER_UPDATED';
END IF;

    v_detail
:= 'name='    || NEW.name
        || ' provider='    || COALESCE(NEW.provider, '—')
        || ' model='       || COALESCE(NEW.model_id, '—')
        || ' active='      || NEW.is_active;

INSERT INTO audit_log (tenant_id, user_id, entity_type, entity_id, action, detail)
VALUES (NEW.tenant_id, v_uid, 'ADAPTER', NEW.id, v_action, v_detail);
RETURN NEW;
END;
$$;

CREATE TRIGGER trg_audit_adapters
    AFTER INSERT OR
UPDATE OR
DELETE
ON adapters
    FOR EACH ROW EXECUTE FUNCTION fn_audit_adapters();

CREATE TABLE knowledge_chunks
(
    id         BIGSERIAL PRIMARY KEY,
    content    TEXT NOT NULL,
    embedding  VECTOR(1536), -- start with OpenAI
    provider   TEXT,         -- openai / ollama
    created_at TIMESTAMP DEFAULT now()
);

-- ============================================================
-- DONE
-- ============================================================
INSERT INTO adapters (id, tenant_id, name, adapter_type, provider, model_id,
                      is_active, allowed_intent_types, config, created_at, updated_at)
VALUES ('a0000000-0000-0000-0000-000000000001',
        NULL,
        'Claude Haiku',
        'LLM',
        'ANTHROPIC',
        'claude-haiku-4-5-20251001',
        true,
        '[]',
        '{
          "model": "claude-haiku-4-5-20251001",
          "max_tokens": 1024
        }',
        NOW(),
        NOW()) ON CONFLICT (id) DO NOTHING;
