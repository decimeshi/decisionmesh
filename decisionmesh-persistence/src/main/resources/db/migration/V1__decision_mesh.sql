-- ============================================================
-- V1__decision_mesh.sql
-- DecisionMesh — Complete Schema (Single Source of Truth)
--
-- Consolidates all migrations V1-V6 into one clean file.
-- For existing environments: set quarkus.flyway.baseline-on-migrate=true
-- ============================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS vector;

-- ============================================================
-- UTILITY FUNCTIONS
-- ============================================================

CREATE OR REPLACE FUNCTION fn_current_audit_user() RETURNS VARCHAR
    LANGUAGE plpgsql AS $$
BEGIN
RETURN current_setting('app.current_user_id', TRUE);
EXCEPTION WHEN OTHERS THEN RETURN NULL;
END;
$$;

CREATE OR REPLACE FUNCTION fn_set_updated_at() RETURNS TRIGGER
    LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at = now();
RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION fn_guard_immutable() RETURNS TRIGGER
    LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'Immutable record violation: table=% id=% (P0001)', TG_TABLE_NAME, OLD.id;
    ELSIF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Immutable record violation: table=% id=% (P0001)', TG_TABLE_NAME, OLD.id;
END IF;
RETURN NULL;
END;
$$;

-- ============================================================
-- TENANTS
-- ============================================================

CREATE TABLE tenants (
                         id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                         external_id  VARCHAR(255) NOT NULL,
                         name         VARCHAR(255) NOT NULL,
                         account_type VARCHAR(20)  NOT NULL DEFAULT 'INDIVIDUAL',
                         keycloak_group_id VARCHAR(36) DEFAULT NULL,
                         status       VARCHAR(50)  NOT NULL DEFAULT 'ACTIVE',
                         config       JSONB        DEFAULT '{}',
                         created_at   TIMESTAMPTZ  DEFAULT now(),
                         updated_at   TIMESTAMPTZ  DEFAULT now(),
                         CONSTRAINT uq_tenants_external_id UNIQUE (external_id),
                         CONSTRAINT chk_account_type CHECK (account_type IN ('INDIVIDUAL', 'ORGANIZATION'))
);

-- ============================================================
-- ORGANIZATIONS
-- ============================================================

CREATE TABLE organizations (
                               id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                               tenant_id    UUID         NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
                               name         VARCHAR(255) NOT NULL,
                               company_size VARCHAR(20)  DEFAULT NULL,
                               description  VARCHAR(255),
                               config       JSONB        DEFAULT '{}',
                               is_active    BOOLEAN      NOT NULL DEFAULT TRUE,
                               created_at   TIMESTAMPTZ  DEFAULT now(),
                               updated_at   TIMESTAMPTZ  DEFAULT now()
);

-- ============================================================
-- USERS
-- ============================================================

CREATE TABLE users (
                       user_id    UUID PRIMARY KEY,
                       tenant_id  UUID REFERENCES tenants(id) ON DELETE CASCADE,
                       email      VARCHAR(255),
                       name       VARCHAR(255),
                       is_active  BOOLEAN     NOT NULL DEFAULT TRUE,
                       created_at TIMESTAMPTZ DEFAULT now(),
                       updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                       CONSTRAINT uq_users_email UNIQUE (email)
);

-- ============================================================
-- USER ORGANIZATIONS
-- ============================================================

CREATE TABLE user_organizations (
                                    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                    user_id         UUID REFERENCES users(user_id) ON DELETE CASCADE,
                                    organization_id UUID REFERENCES organizations(id) ON DELETE CASCADE,
                                    tenant_id       UUID REFERENCES tenants(id) ON DELETE CASCADE,
                                    role            VARCHAR(100),
                                    permissions     JSONB   NOT NULL DEFAULT '[]',
                                    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
                                    created_at      TIMESTAMPTZ DEFAULT now(),
                                    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
                                    CONSTRAINT uq_user_organizations_user_org UNIQUE (user_id, organization_id)
);

-- ============================================================
-- ORG BRANDING
-- ============================================================

CREATE TABLE org_branding (
                              tenant_id     UUID PRIMARY KEY REFERENCES tenants(id) ON DELETE CASCADE,
                              org_name      VARCHAR(255),
                              primary_color VARCHAR(7)   DEFAULT '#2563eb',
                              logo_url      VARCHAR(255),
                              favicon       VARCHAR(255),
                              updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============================================================
-- PROJECTS
-- ============================================================

CREATE TABLE projects (
                          id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                          tenant_id   UUID         NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
                          name        VARCHAR(255) NOT NULL,
                          description TEXT,
                          environment VARCHAR(50)  NOT NULL DEFAULT 'Production',
                          is_default  BOOLEAN      NOT NULL DEFAULT FALSE,
                          created_at  TIMESTAMPTZ  DEFAULT CURRENT_TIMESTAMP,
                          CONSTRAINT chk_env CHECK (environment IN ('Development', 'Staging', 'Production', 'Sandbox'))
);

CREATE INDEX idx_projects_tenant ON projects(tenant_id);

-- ============================================================
-- MEMBERSHIP
-- ============================================================

CREATE TABLE membership (
                            id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                            tenant_id      UUID        NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
                            user_id        UUID        NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
                            project_id     UUID REFERENCES projects(id) ON DELETE CASCADE,
                            role           VARCHAR(20) NOT NULL DEFAULT 'VIEWER',
                            joined_at      TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
                            last_active_at TIMESTAMPTZ,
                            CONSTRAINT chk_member_role CHECK (role IN ('ADMIN', 'ANALYST', 'VIEWER')),
                            CONSTRAINT uq_tenant_user_project UNIQUE NULLS NOT DISTINCT (tenant_id, user_id, project_id)
);

CREATE INDEX idx_members_user    ON membership(user_id);
CREATE INDEX idx_members_tenant  ON membership(tenant_id);
CREATE INDEX idx_members_project ON membership(project_id);

-- ============================================================
-- INVITATIONS
-- ============================================================

CREATE TABLE invitations (
                             id         UUID PRIMARY KEY,
                             tenant_id  UUID         NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
                             project_id UUID REFERENCES projects(id) ON DELETE CASCADE,
                             email      VARCHAR(255) NOT NULL,
                             role       VARCHAR(20)  NOT NULL DEFAULT 'VIEWER',
                             status     VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
                             token      VARCHAR(64)  NOT NULL UNIQUE,
                             created_at TIMESTAMPTZ  DEFAULT now(),
                             expires_at TIMESTAMPTZ
);

CREATE INDEX idx_invitations_tenant ON invitations(tenant_id);
CREATE INDEX idx_invitations_token  ON invitations(token);
CREATE INDEX idx_invitations_email  ON invitations(email);

-- ============================================================
-- API KEYS
-- ============================================================

CREATE TABLE api_keys (
                          key_id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                          tenant_id          UUID REFERENCES tenants(id) ON DELETE CASCADE,
                          organization_id    UUID REFERENCES organizations(id) ON DELETE CASCADE,
                          user_id            UUID,
                          created_by_user_id UUID,
                          name               VARCHAR(255),
                          key_hash           VARCHAR(255) UNIQUE,
                          key_prefix         VARCHAR(20),
                          scopes             JSONB        DEFAULT '[]',
                          active             BOOLEAN      DEFAULT TRUE,
                          revoked_at         TIMESTAMPTZ,
                          revoked_by         VARCHAR(255),
                          last_used_at       TIMESTAMPTZ,
                          usage_count        BIGINT       DEFAULT 0,
                          created_at         TIMESTAMPTZ  DEFAULT now(),
                          created_by         VARCHAR(255),
                          expires_at         TIMESTAMPTZ,
                          ip_whitelist       JSONB        DEFAULT '[]',
                          rate_limit         INTEGER
);

CREATE INDEX idx_api_keys_tenant   ON api_keys(tenant_id);
CREATE INDEX idx_api_keys_user_id  ON api_keys(user_id);
CREATE INDEX idx_api_keys_key_hash ON api_keys(key_hash);

-- ============================================================
-- ADAPTERS
-- ============================================================

CREATE TABLE adapters (
                          id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                          tenant_id           UUID,
                          name                VARCHAR(255) NOT NULL,
                          adapter_type        VARCHAR(100),
                          provider            VARCHAR(100),
                          model_id            VARCHAR(255),
                          region              VARCHAR(100),
                          base_cost_per_token NUMERIC(18,8),
                          max_tokens_per_call INTEGER,
                          avg_latency_ms      BIGINT,
                          config              JSONB   NOT NULL DEFAULT '{}',
                          capability_flags    JSONB   NOT NULL DEFAULT '{}',
                          allowed_intent_types JSONB  NOT NULL DEFAULT '[]',
                          is_active           BOOLEAN NOT NULL DEFAULT TRUE,
                          created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
                          updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
                          CONSTRAINT adapters_adapter_type_check CHECK (adapter_type IN (
                                                                                         'LLM','EMBEDDING','TOOL','RETRIEVAL','RERANKER','CLASSIFIER','CUSTOM'))
);

-- ============================================================
-- ADAPTER PERFORMANCE PROFILES
-- ============================================================

CREATE TABLE adapter_performance_profiles (
                                              id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                              adapter_id          UUID NOT NULL,
                                              tenant_id           UUID NOT NULL,
                                              ema_cost            FLOAT NOT NULL DEFAULT 0,
                                              ema_latency_ms      FLOAT NOT NULL DEFAULT 0,
                                              ema_success_rate    FLOAT NOT NULL DEFAULT 1,
                                              ema_risk_score      FLOAT NOT NULL DEFAULT 0,
                                              ema_confidence      FLOAT NOT NULL DEFAULT 0,
                                              composite_score     FLOAT NOT NULL DEFAULT 0,
                                              execution_count     BIGINT NOT NULL DEFAULT 0,
                                              success_count       BIGINT NOT NULL DEFAULT 0,
                                              failure_count       BIGINT NOT NULL DEFAULT 0,
                                              cold_start          BOOLEAN NOT NULL DEFAULT TRUE,
                                              cold_start_threshold INTEGER DEFAULT 10,
                                              is_degraded         BOOLEAN NOT NULL DEFAULT FALSE,
                                              degraded_since      TIMESTAMPTZ,
                                              degraded_reason     VARCHAR(255),
                                              last_executed_at    TIMESTAMPTZ,
                                              version             INTEGER NOT NULL DEFAULT 0,
                                              created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
                                              updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE adapter_profile_versions (
                                          id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                          profile_id       UUID,
                                          tenant_id        UUID,
                                          adapter_id       UUID REFERENCES adapters(id) ON DELETE CASCADE,
                                          ema_cost         FLOAT NOT NULL DEFAULT 0,
                                          ema_latency_ms   FLOAT NOT NULL DEFAULT 0,
                                          ema_success_rate FLOAT NOT NULL DEFAULT 1,
                                          ema_risk_score   FLOAT NOT NULL DEFAULT 0,
                                          ema_confidence   FLOAT NOT NULL DEFAULT 0,
                                          composite_score  FLOAT NOT NULL DEFAULT 0,
                                          execution_count  BIGINT NOT NULL DEFAULT 0,
                                          success_count    BIGINT NOT NULL DEFAULT 0,
                                          failure_count    BIGINT NOT NULL DEFAULT 0,
                                          cold_start       BOOLEAN NOT NULL DEFAULT TRUE,
                                          is_degraded      BOOLEAN NOT NULL DEFAULT FALSE,
                                          version          INTEGER NOT NULL DEFAULT 0,
                                          snapshotted_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_profile_versions_adapter ON adapter_profile_versions(adapter_id, tenant_id, snapshotted_at DESC);
CREATE INDEX idx_profile_versions_profile ON adapter_profile_versions(profile_id, snapshotted_at DESC);

-- ============================================================
-- POLICIES
-- ============================================================

CREATE TABLE policies (
                          id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                          tenant_id       UUID,
                          name            VARCHAR(255),
                          description     TEXT,
                          scope           VARCHAR(50)  NOT NULL DEFAULT 'TENANT',
                          scope_ref_id    UUID,
                          phase           VARCHAR(50)  NOT NULL DEFAULT 'PRE_EXECUTION',
                          enforcement_mode VARCHAR(50) NOT NULL DEFAULT 'LOG_ONLY',
                          policy_type     VARCHAR(100) NOT NULL DEFAULT 'CUSTOM_DSL',
                          rule_dsl        JSONB        NOT NULL DEFAULT '{}',
                          priority        INTEGER      NOT NULL DEFAULT 100,
                          is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
                          version         INTEGER      NOT NULL DEFAULT 0,
                          created_at      TIMESTAMPTZ  DEFAULT now(),
                          updated_at      TIMESTAMPTZ  DEFAULT now()
);

-- policy_evaluations moved after intents

-- ============================================================
-- INTENTS
-- ============================================================

CREATE TABLE intents (
                         id                UUID NOT NULL,
                         tenant_id         UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
                         user_id           UUID,
                         intent_type       VARCHAR(255) NOT NULL,
                         phase             VARCHAR(50)  NOT NULL,
                         satisfaction_state VARCHAR(50) NOT NULL DEFAULT 'UNKNOWN',
                         retry_count       INTEGER      NOT NULL DEFAULT 0,
                         max_retries       INTEGER      NOT NULL DEFAULT 0,
                         terminal          BOOLEAN      NOT NULL DEFAULT FALSE,
                         version           BIGINT       NOT NULL DEFAULT 0,
                         model_tier        VARCHAR(20),
                         payload           JSONB        NOT NULL,
                         injection_risk    NUMERIC(5,4) DEFAULT 0,
                         created_at        TIMESTAMPTZ  NOT NULL,
                         updated_at        TIMESTAMPTZ  NOT NULL,
                         PRIMARY KEY (id)
);

CREATE INDEX idx_intents_tenant    ON intents(tenant_id);
CREATE INDEX idx_intents_phase     ON intents(tenant_id, phase);
CREATE INDEX idx_intents_terminal  ON intents(tenant_id, terminal, created_at DESC);

-- ============================================================
-- INTENT EVENTS (immutable)
-- ============================================================

CREATE TABLE intent_events (
                               id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                               event_id         UUID        NOT NULL,
                               intent_id        UUID        NOT NULL,
                               tenant_id        UUID        NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
                               version          BIGINT      NOT NULL,
                               event_type       VARCHAR(255) NOT NULL,
                               aggregate_type   VARCHAR(255) NOT NULL DEFAULT 'Intent',
                               occurred_at      TIMESTAMPTZ  NOT NULL,
                               payload          JSONB        NOT NULL,
                               phase_from       VARCHAR(50),
                               phase_to         VARCHAR(50),
                               actor_id         UUID,
                               actor_type       VARCHAR(100),
                               plan_id          UUID,
                               plan_version     INTEGER,
                               execution_id     UUID,
                               attempt_number   INTEGER,
                               adapter_id       UUID,
                               policy_id        UUID,
                               drift_score_snapshot  NUMERIC(5,4),
                               cost_usd_snapshot     NUMERIC(12,6),
                               risk_score_snapshot   NUMERIC(5,4),
                               trace_id         VARCHAR(64),
                               span_id          VARCHAR(64),
                               parent_span_id   VARCHAR(64)
);

CREATE INDEX idx_intent_events_intent ON intent_events(intent_id, occurred_at);
CREATE INDEX idx_intent_events_tenant ON intent_events(tenant_id, occurred_at DESC);

CREATE TRIGGER trg_guard_intent_events
    BEFORE UPDATE OR DELETE ON intent_events
    FOR EACH ROW EXECUTE FUNCTION fn_guard_immutable();

-- ============================================================
-- INTENT PLANS
-- ============================================================

CREATE TABLE intent_plans (
                              id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                              intent_id        UUID REFERENCES intents(id) ON DELETE CASCADE,
                              tenant_id        UUID REFERENCES tenants(id) ON DELETE CASCADE,
                              plan_version     INTEGER NOT NULL DEFAULT 1,
                              strategy         VARCHAR(50)  NOT NULL DEFAULT 'SINGLE_ADAPTER',
                              status           VARCHAR(50)  NOT NULL DEFAULT 'PENDING',
                              was_exploration  BOOLEAN      NOT NULL DEFAULT FALSE,
                              ranking_snapshot JSONB        NOT NULL DEFAULT '{}',
                              budget_allocation JSONB       NOT NULL DEFAULT '{}',
                              planner_notes    TEXT,
                              created_at       TIMESTAMPTZ  DEFAULT now()
);

CREATE TABLE intent_plan_steps (
                                   id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                   plan_id          UUID REFERENCES intent_plans(id) ON DELETE CASCADE,
                                   intent_id        UUID REFERENCES intents(id) ON DELETE CASCADE,
                                   tenant_id        UUID REFERENCES tenants(id) ON DELETE CASCADE,
                                   adapter_id       UUID REFERENCES adapters(id) ON DELETE SET NULL,
                                   step_order       INTEGER,
                                   step_type        VARCHAR(50)  NOT NULL DEFAULT 'LLM_CALL',
                                   is_conditional   BOOLEAN      NOT NULL DEFAULT FALSE,
                                   condition_expr   JSONB,
                                   config_snapshot  JSONB        NOT NULL DEFAULT '{}',
                                   estimated_cost_usd    NUMERIC(12,6),
                                   estimated_latency_ms  BIGINT,
                                   created_at       TIMESTAMPTZ  DEFAULT now()
);

-- ============================================================
-- EXECUTION RECORDS
-- ============================================================

CREATE TABLE execution_records (
                                   id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                   intent_id            UUID REFERENCES intents(id) ON DELETE CASCADE,
                                   tenant_id            UUID,
                                   adapter_id           UUID,
                                   plan_id              UUID,
                                   plan_step_id         UUID,
                                   status               VARCHAR(50),
                                   cost_usd             NUMERIC(12,6),
                                   latency_ms           BIGINT,
                                   prompt_tokens        INTEGER DEFAULT 0,
                                   completion_tokens    INTEGER DEFAULT 0,
                                   total_tokens         INTEGER DEFAULT 0,
                                   risk_score           NUMERIC(5,4) DEFAULT 0,
                                   failure_reason       VARCHAR(255),
                                   response_text        TEXT,
                                   quality_score        NUMERIC(5,4),
                                   hallucination_risk   NUMERIC(5,4),
                                   hallucination_detected BOOLEAN DEFAULT FALSE,
                                   quality_reasoning    VARCHAR(500),
                                   metadata             JSONB DEFAULT '{}',
                                   executed_at          TIMESTAMPTZ DEFAULT now(),
                                   cache_read_tokens    INTEGER NOT NULL DEFAULT 0,
                                   cache_write_tokens   INTEGER NOT NULL DEFAULT 0,
                                   cache_hit            BOOLEAN NOT NULL DEFAULT FALSE,
                                   attempt_number       INTEGER NOT NULL DEFAULT 1
);

CREATE UNIQUE INDEX uq_exec_intent_attempt ON execution_records(intent_id, attempt_number);
CREATE INDEX idx_exec_intent   ON execution_records(intent_id);
CREATE INDEX idx_exec_tenant   ON execution_records(tenant_id, executed_at DESC);

-- ============================================================
-- GOVERNANCE LEDGER
-- ============================================================

CREATE TABLE ledger_entry (
                              id                   UUID PRIMARY KEY,
                              intent_id            UUID        NOT NULL,
                              tenant_id            VARCHAR(255) NOT NULL,
                              aggregate_version    BIGINT      NOT NULL DEFAULT 0,
                              event_id             UUID,
                              event_type           VARCHAR(120),
                              policy_snapshot_json TEXT,
                              budget_snapshot_json TEXT,
                              sla_snapshot_json    TEXT,
                              previous_hash        VARCHAR(64),
                              current_hash         VARCHAR(64) NOT NULL,
                              timestamp            TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_ledger_entry_intent_id     ON ledger_entry(intent_id);
CREATE INDEX idx_ledger_entry_tenant_intent ON ledger_entry(tenant_id, intent_id);

-- ============================================================
-- POLICY EVALUATIONS (after intents, policies, adapters)
-- ============================================================

CREATE TABLE policy_evaluations (
                                    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                    intent_id        UUID REFERENCES intents(id) ON DELETE CASCADE,
                                    policy_id        UUID REFERENCES policies(id) ON DELETE CASCADE,
                                    tenant_id        UUID,
                                    adapter_id       UUID REFERENCES adapters(id) ON DELETE SET NULL,
                                    result           VARCHAR(50),
                                    phase            VARCHAR(50)  NOT NULL DEFAULT 'PRE_EXECUTION',
                                    enforcement_mode VARCHAR(50)  NOT NULL DEFAULT 'LOG_ONLY',
                                    block_reason     VARCHAR(512),
                                    attempt_number   INTEGER,
                                    context_snapshot JSONB        NOT NULL DEFAULT '{}',
                                    evaluated_at     TIMESTAMPTZ  DEFAULT now()
);

-- ============================================================
-- SPEND RECORDS
-- ============================================================

CREATE TABLE spend_records (
                               id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                               intent_id         UUID REFERENCES intents(id) ON DELETE CASCADE,
                               execution_id      UUID REFERENCES execution_records(id) ON DELETE CASCADE,
                               tenant_id         UUID REFERENCES tenants(id) ON DELETE CASCADE,
                               adapter_id        UUID REFERENCES adapters(id) ON DELETE SET NULL,
                               amount_usd        NUMERIC(12,6),
                               token_count       INTEGER DEFAULT 0,
                               budget_ceiling_usd NUMERIC(12,6),
                               recorded_at       TIMESTAMPTZ DEFAULT now()
);

-- ============================================================
-- CREDIT LEDGER
-- ============================================================

CREATE TABLE credit_ledger (
                               id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                               org_id       UUID        NOT NULL,
                               amount       INTEGER     NOT NULL,
                               reason       VARCHAR(30) NOT NULL,
                               reference_id VARCHAR(255),
                               created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE credit_ledger IS 'Append-only ledger - positive=credit, negative=debit';

-- ============================================================
-- BILLING
-- ============================================================

CREATE TABLE subscription (
                              id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                              orgid                UUID,
                              stripecustomerid     VARCHAR(255),
                              stripesubscriptionid VARCHAR(255),
                              plan                 VARCHAR(255) NOT NULL DEFAULT 'FREE',
                              status               VARCHAR(255) NOT NULL DEFAULT 'ACTIVE',
                              createdat            TIMESTAMPTZ(6),
                              updatedat            TIMESTAMPTZ(6)
);

CREATE TABLE billing_customer (
                                  org_id           UUID NOT NULL,
                                  orgid            UUID,
                                  stripecustomerid VARCHAR(255)
);

CREATE TABLE webhook_events (
                                id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                gateway      VARCHAR(20)  NOT NULL,
                                event_type   VARCHAR(100) NOT NULL,
                                payload      TEXT,
                                status       VARCHAR(20)  NOT NULL DEFAULT 'received',
                                error        TEXT,
                                org_id       UUID,
                                received_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
                                processed_at TIMESTAMPTZ,
                                CONSTRAINT webhook_events_gateway_check CHECK (gateway IN ('stripe', 'razorpay')),
                                CONSTRAINT webhook_events_status_check  CHECK (status IN ('received', 'processed', 'failed'))
);

-- ============================================================
-- AUDIT LOG
-- ============================================================

CREATE TABLE audit_log (
                           id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                           tenant_id     UUID,
                           user_id       VARCHAR(255),
                           entity_type   VARCHAR(100),
                           entity_id     UUID,
                           resource_type VARCHAR(100),
                           resource_id   VARCHAR(255),
                           action        VARCHAR(100),
                           outcome       VARCHAR(20) DEFAULT 'SUCCESS',
                           detail        TEXT,
                           occurred_at   TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX idx_audit_tenant ON audit_log(tenant_id, occurred_at DESC);

-- ============================================================
-- INTENT LIBRARY
-- ============================================================

CREATE TABLE intent_library (
                                id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                name            VARCHAR(255) NOT NULL,
                                category        VARCHAR(100) NOT NULL,
                                category_label  VARCHAR(100),
                                vertical        VARCHAR(50)  NOT NULL DEFAULT 'FINTECH',
                                description     TEXT,
                                risk_level      VARCHAR(50),
                                default_policy  VARCHAR(50),
                                sla_ms          INTEGER,
                                regulatory_ref  VARCHAR(255),
                                tags            TEXT,
                                is_active       BOOLEAN NOT NULL DEFAULT TRUE,
                                created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
                                example_payload JSONB
);

-- ============================================================
-- RATE LIMITING
-- ============================================================

CREATE TABLE rate_limit_configs (
                                    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                    tenant_id UUID REFERENCES tenants(id) ON DELETE CASCADE
);

CREATE TABLE rate_limit_counters (
                                     id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                     config_id UUID REFERENCES rate_limit_configs(id) ON DELETE CASCADE,
                                     tenant_id UUID REFERENCES tenants(id) ON DELETE CASCADE
);

-- ============================================================
-- IDEMPOTENCY
-- ============================================================

CREATE TABLE tenant_idempotency (
                                    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                    tenant_id       UUID        NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
                                    idempotency_key VARCHAR(255) NOT NULL,
                                    created_at      TIMESTAMPTZ DEFAULT now()
);

CREATE UNIQUE INDEX uq_tenant_idempotency ON tenant_idempotency(tenant_id, idempotency_key);

CREATE TABLE global_idempotency (
                                    idempotency_key VARCHAR NOT NULL PRIMARY KEY,
                                    created_at      TIMESTAMPTZ NOT NULL
);

-- ============================================================
-- MISC TABLES
-- ============================================================

CREATE TABLE drift_tracking (
                                intent_id  UUID NOT NULL PRIMARY KEY,
                                last_drift FLOAT NOT NULL,
                                updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE sla_windows (
                             id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                             intent_id   UUID REFERENCES intents(id) ON DELETE CASCADE,
                             tenant_id   UUID REFERENCES tenants(id) ON DELETE CASCADE,
                             deadline_ms BIGINT
);

CREATE TABLE intent_drift_evaluations (
                                          id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                          intent_id    UUID REFERENCES intents(id) ON DELETE CASCADE,
                                          execution_id UUID,
                                          tenant_id    UUID REFERENCES tenants(id) ON DELETE CASCADE,
                                          drift_score  NUMERIC(5,4)
);

CREATE TABLE lifecycle_audit (
                                 intent_id  UUID        NOT NULL,
                                 phase      VARCHAR     NOT NULL,
                                 action     VARCHAR     NOT NULL,
                                 version    BIGINT      NOT NULL,
                                 timestamp  TIMESTAMPTZ NOT NULL
);

CREATE TABLE consumer_offsets (
                                  consumer_id  VARCHAR     NOT NULL,
                                  event_id     UUID        NOT NULL,
                                  processed_at TIMESTAMPTZ NOT NULL,
                                  PRIMARY KEY (consumer_id, event_id)
);

CREATE TABLE processed_events (
                                  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                  event_id     VARCHAR(255) NOT NULL,
                                  processed_at TIMESTAMPTZ  DEFAULT now()
);

CREATE TABLE event_outbox (
                              id             UUID PRIMARY KEY,
                              aggregate_type VARCHAR     NOT NULL,
                              aggregate_id   UUID        NOT NULL,
                              event_type     VARCHAR     NOT NULL,
                              payload_json   JSONB       NOT NULL,
                              published      BOOLEAN     DEFAULT FALSE,
                              created_at     TIMESTAMPTZ NOT NULL
);

CREATE TABLE decision_traces (
                                 decision_id       UUID NOT NULL PRIMARY KEY,
                                 intent_id         UUID NOT NULL,
                                 tenant_id         VARCHAR NOT NULL,
                                 decision_type     VARCHAR NOT NULL,
                                 inputs_snapshot   JSONB,
                                 scoring_snapshot  JSONB,
                                 policy_snapshot   JSONB,
                                 portfolio_context JSONB,
                                 rationale         TEXT,
                                 timestamp         TIMESTAMPTZ NOT NULL
);

CREATE TABLE decision_trace_links (
                                      parent_decision_id UUID NOT NULL,
                                      child_decision_id  UUID NOT NULL,
                                      PRIMARY KEY (parent_decision_id, child_decision_id)
);

CREATE TABLE intent_region_registry (
                                        intent_id       UUID NOT NULL PRIMARY KEY,
                                        tenant_id       VARCHAR NOT NULL,
                                        home_region     VARCHAR NOT NULL,
                                        failover_region VARCHAR,
                                        updated_at      TIMESTAMPTZ NOT NULL
);

CREATE TABLE intent_evaluations (
                                    intent_id          UUID NOT NULL PRIMARY KEY,
                                    satisfaction_score FLOAT NOT NULL,
                                    drift_score        FLOAT NOT NULL,
                                    evaluated_at       TIMESTAMPTZ NOT NULL
);

CREATE TABLE intent_dependencies (
                                     parent_intent_id UUID NOT NULL,
                                     child_intent_id  UUID NOT NULL,
                                     created_at       TIMESTAMPTZ NOT NULL,
                                     PRIMARY KEY (parent_intent_id, child_intent_id)
);

CREATE TABLE exploration_ledger (
                                    entry_id    UUID NOT NULL PRIMARY KEY,
                                    adapter_id  VARCHAR NOT NULL,
                                    intent_type VARCHAR NOT NULL,
                                    exploration BOOLEAN NOT NULL,
                                    reward      FLOAT   NOT NULL,
                                    regret      FLOAT   NOT NULL,
                                    confidence  FLOAT   NOT NULL,
                                    timestamp   TIMESTAMPTZ NOT NULL
);

CREATE TABLE user_feedback (
                               id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                               user_id    UUID        NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
                               rating     SMALLINT    NOT NULL,
                               category   VARCHAR(20) NOT NULL,
                               comment    TEXT,
                               page       VARCHAR(255),
                               user_agent TEXT,
                               created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                               CONSTRAINT user_feedback_rating_check   CHECK (rating >= 1 AND rating <= 5),
                               CONSTRAINT user_feedback_category_check CHECK (category IN ('bug','feature','billing','general'))
);

-- ============================================================
-- GDPR ERASURE REQUESTS
-- ============================================================

CREATE TABLE erasure_requests (
                                  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                  tenant_id    UUID        NOT NULL,
                                  requested_by VARCHAR(255) NOT NULL,
                                  requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                                  completed_at TIMESTAMPTZ,
                                  status       VARCHAR(20) NOT NULL DEFAULT 'PENDING',
                                  reason       TEXT,
                                  error        TEXT,
                                  CONSTRAINT chk_erasure_status CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED'))
);

CREATE INDEX idx_erasure_tenant ON erasure_requests(tenant_id);
CREATE INDEX idx_erasure_status ON erasure_requests(status, requested_at DESC);

-- ============================================================
-- KNOWLEDGE BASE
-- ============================================================

CREATE TABLE knowledge_chunks (
                                  id        BIGSERIAL PRIMARY KEY,
                                  content   TEXT NOT NULL,
                                  embedding vector(1536),
                                  provider  TEXT,
                                  created_at TIMESTAMP DEFAULT now()
);

-- ============================================================
-- AUDIT TRIGGER FUNCTIONS
-- ============================================================

CREATE OR REPLACE FUNCTION fn_audit_intents() RETURNS TRIGGER
    LANGUAGE plpgsql AS $$
DECLARE
v_action VARCHAR(100);
    v_detail TEXT;
    v_uid    VARCHAR(255);
BEGIN
    v_uid := COALESCE(fn_current_audit_user(), NEW.user_id::TEXT);
    IF TG_OP = 'INSERT' THEN
        v_action := 'INTENT_SUBMITTED';
        v_detail := 'type=' || NEW.intent_type || ' phase=' || NEW.phase;
    ELSIF TG_OP = 'UPDATE' THEN
        IF NEW.terminal = TRUE AND OLD.terminal = FALSE THEN
            v_action := CASE NEW.satisfaction_state
                WHEN 'SATISFIED' THEN 'INTENT_SATISFIED'
                WHEN 'VIOLATED'  THEN 'INTENT_VIOLATED'
                ELSE                  'INTENT_COMPLETED'
END;
            v_detail := 'phase=' || NEW.phase
                || ' satisfaction=' || NEW.satisfaction_state
                || ' retries='      || NEW.retry_count;
        ELSIF NEW.phase IS DISTINCT FROM OLD.phase THEN
            v_action := 'INTENT_PHASE_CHANGED';
            v_detail := 'from=' || COALESCE(OLD.phase, '—') || ' to=' || NEW.phase;
ELSE
            RETURN NEW;
END IF;
    ELSIF TG_OP = 'DELETE' THEN
        v_uid    := COALESCE(fn_current_audit_user(), OLD.user_id::TEXT);
        v_action := 'INTENT_DELETED';
        v_detail := 'type=' || OLD.intent_type || ' phase=' || OLD.phase;
INSERT INTO audit_log(tenant_id, user_id, entity_type, entity_id, action, detail)
VALUES (OLD.tenant_id, v_uid, 'INTENT', OLD.id, v_action, v_detail);
RETURN OLD;
END IF;
INSERT INTO audit_log(tenant_id, user_id, entity_type, entity_id, action, detail)
VALUES (NEW.tenant_id, v_uid, 'INTENT', NEW.id, v_action, v_detail);
RETURN NEW;
END;
$$;

CREATE TRIGGER trg_audit_intents
    AFTER INSERT OR UPDATE OR DELETE ON intents
    FOR EACH ROW EXECUTE FUNCTION fn_audit_intents();

CREATE OR REPLACE FUNCTION fn_audit_api_keys() RETURNS TRIGGER
    LANGUAGE plpgsql AS $$
DECLARE
v_action VARCHAR(100);
    v_detail TEXT;
    v_uid    VARCHAR(255);
BEGIN
    IF TG_OP = 'DELETE' THEN
        v_uid    := COALESCE(fn_current_audit_user(), OLD.created_by_user_id::TEXT);
        v_action := 'API_KEY_DELETED';
        v_detail := 'prefix=' || OLD.key_prefix || ' name=' || COALESCE(OLD.name, '—');
INSERT INTO audit_log(tenant_id, user_id, entity_type, entity_id, action, detail)
VALUES (OLD.tenant_id, v_uid, 'API_KEY', OLD.key_id, v_action, v_detail);
RETURN OLD;
END IF;
    v_uid := COALESCE(fn_current_audit_user(), NEW.created_by_user_id::TEXT);
    IF TG_OP = 'INSERT' THEN
        v_action := 'API_KEY_CREATED';
        v_detail := 'prefix=' || NEW.key_prefix
            || ' name='   || COALESCE(NEW.name, '—')
            || ' scopes=' || COALESCE(NEW.scopes::TEXT, '[]');
    ELSIF TG_OP = 'UPDATE' THEN
        IF NEW.revoked_at IS NOT NULL AND OLD.revoked_at IS NULL THEN
            v_action := 'API_KEY_REVOKED';
            v_detail := 'prefix=' || NEW.key_prefix || ' name=' || COALESCE(NEW.name, '—');
ELSE
            RETURN NEW;
END IF;
END IF;
INSERT INTO audit_log(tenant_id, user_id, entity_type, entity_id, action, detail)
VALUES (NEW.tenant_id, v_uid, 'API_KEY', NEW.key_id, v_action, v_detail);
RETURN NEW;
END;
$$;

CREATE TRIGGER trg_audit_api_keys
    AFTER INSERT OR UPDATE OR DELETE ON api_keys
    FOR EACH ROW EXECUTE FUNCTION fn_audit_api_keys();

CREATE OR REPLACE FUNCTION fn_audit_policies() RETURNS TRIGGER
    LANGUAGE plpgsql AS $$
DECLARE
v_action VARCHAR(100);
    v_detail TEXT;
    v_uid    VARCHAR(255);
BEGIN
    v_uid := fn_current_audit_user();
    IF TG_OP = 'DELETE' THEN
        v_action := 'POLICY_DELETED';
        v_detail := 'name=' || COALESCE(OLD.name, '—') || ' phase=' || OLD.phase;
INSERT INTO audit_log(tenant_id, user_id, entity_type, entity_id, action, detail)
VALUES (OLD.tenant_id, v_uid, 'POLICY', OLD.id, v_action, v_detail);
RETURN OLD;
END IF;
    v_action := CASE TG_OP WHEN 'INSERT' THEN 'POLICY_CREATED' ELSE 'POLICY_UPDATED' END;
    v_detail := 'name=' || COALESCE(NEW.name, '—')
        || ' phase='       || NEW.phase
        || ' enforcement=' || NEW.enforcement_mode;
INSERT INTO audit_log(tenant_id, user_id, entity_type, entity_id, action, detail)
VALUES (NEW.tenant_id, v_uid, 'POLICY', NEW.id, v_action, v_detail);
RETURN NEW;
END;
$$;

CREATE TRIGGER trg_audit_policies
    AFTER INSERT OR UPDATE OR DELETE ON policies
    FOR EACH ROW EXECUTE FUNCTION fn_audit_policies();

CREATE OR REPLACE FUNCTION fn_audit_adapters() RETURNS TRIGGER
    LANGUAGE plpgsql AS $$
DECLARE
v_action VARCHAR(100);
    v_detail TEXT;
    v_uid    VARCHAR(255);
BEGIN
    v_uid := fn_current_audit_user();
    IF TG_OP = 'DELETE' THEN
        v_action := 'ADAPTER_DELETED';
        v_detail := 'name=' || OLD.name || ' provider=' || COALESCE(OLD.provider, '—');
INSERT INTO audit_log(tenant_id, user_id, entity_type, entity_id, action, detail)
VALUES (OLD.tenant_id, v_uid, 'ADAPTER', OLD.id, v_action, v_detail);
RETURN OLD;
END IF;
    IF TG_OP = 'UPDATE' AND NEW.is_active IS DISTINCT FROM OLD.is_active THEN
        v_action := CASE WHEN NEW.is_active THEN 'ADAPTER_ENABLED' ELSE 'ADAPTER_DISABLED' END;
    ELSIF TG_OP = 'INSERT' THEN
        v_action := 'ADAPTER_CREATED';
ELSE
        v_action := 'ADAPTER_UPDATED';
END IF;
    v_detail := 'name='     || NEW.name
        || ' provider=' || COALESCE(NEW.provider, '—')
        || ' model='    || COALESCE(NEW.model_id, '—')
        || ' active='   || NEW.is_active;
INSERT INTO audit_log(tenant_id, user_id, entity_type, entity_id, action, detail)
VALUES (NEW.tenant_id, v_uid, 'ADAPTER', NEW.id, v_action, v_detail);
RETURN NEW;
END;
$$;

CREATE TRIGGER trg_audit_adapters
    AFTER INSERT OR UPDATE OR DELETE ON adapters
    FOR EACH ROW EXECUTE FUNCTION fn_audit_adapters();

-- ============================================================
-- SEED DATA — Default Claude Haiku adapter
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
        '{"model": "claude-haiku-4-5-20251001", "max_tokens": 1024}',
        NOW(), NOW())
    ON CONFLICT (id) DO NOTHING;

-- ============================================================
-- DONE
-- ============================================================