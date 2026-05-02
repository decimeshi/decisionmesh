#!/usr/bin/env bash
# scripts/init-openbao.sh
# Run ONCE after first deploy. Initialises OpenBao and loads all secrets
# that VaultConfigSource reads at startup.
#
# Based on code review — secrets read from:
#   VaultConfigSource.java       — quarkus.vault.url + client-token
#   Paths used in application.properties / env:
#     secret/decisionmesh/db         — database credentials
#     secret/decisionmesh/llm        — Anthropic API key + model
#     secret/decisionmesh/stripe     — Stripe keys (BillingService)
#     secret/decisionmesh/razorpay   — Razorpay keys (RazorpayService)
#     secret/decisionmesh/auth       — Zitadel service token
#     secret/decisionmesh/email      — SMTP credentials (EmailService)
#
# Usage:
#   ssh root@SERVER_IP
#   cd /opt/decisionmesh
#   bash scripts/init-openbao.sh

set -euo pipefail
GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
ok()   { echo -e "${GREEN}[OK]${NC}    $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC}  $*"; }
info() { echo -e "${CYAN}[INFO]${NC}  $*"; }

BAO="docker exec dm-openbao bao"
ADDR="http://127.0.0.1:8200"

# ── Init or unseal ────────────────────────────────────────────────────────
STATUS=$($BAO status -address=$ADDR -format=json 2>/dev/null || echo '{"initialized":false}')
INITIALIZED=$(echo "$STATUS" | python3 -c "import sys,json; print(json.load(sys.stdin).get('initialized','false'))")

if [[ "$INITIALIZED" == "False" || "$INITIALIZED" == "false" ]]; then
    info "Initialising OpenBao..."
    OUT=$($BAO operator init -address=$ADDR -key-shares=3 -key-threshold=2 -format=json)
    mkdir -p /opt/decisionmesh/infra/openbao
    echo "$OUT" > /opt/decisionmesh/infra/openbao/init-output.json
    chmod 600 /opt/decisionmesh/infra/openbao/init-output.json
    K1=$(echo "$OUT" | python3 -c "import sys,json; print(json.load(sys.stdin)['unseal_keys_b64'][0])")
    K2=$(echo "$OUT" | python3 -c "import sys,json; print(json.load(sys.stdin)['unseal_keys_b64'][1])")
    ROOT=$(echo "$OUT" | python3 -c "import sys,json; print(json.load(sys.stdin)['root_token'])")
    $BAO operator unseal -address=$ADDR "$K1"
    $BAO operator unseal -address=$ADDR "$K2"
    ok "Initialised and unsealed"
else
    ROOT=$(python3 -c "import json; print(json.load(open('/opt/decisionmesh/infra/openbao/init-output.json'))['root_token'])")
    SEALED=$(echo "$STATUS" | python3 -c "import sys,json; print(json.load(sys.stdin).get('sealed','false'))")
    if [[ "$SEALED" == "True" || "$SEALED" == "true" ]]; then
        warn "Vault sealed — unsealing..."
        K1=$(python3 -c "import json; print(json.load(open('/opt/decisionmesh/infra/openbao/init-output.json'))['unseal_keys_b64'][0])")
        K2=$(python3 -c "import json; print(json.load(open('/opt/decisionmesh/infra/openbao/init-output.json'))['unseal_keys_b64'][1])")
        $BAO operator unseal -address=$ADDR "$K1"
        $BAO operator unseal -address=$ADDR "$K2"
        ok "Unsealed"
    else
        ok "Already unsealed"
    fi
fi

# ── Enable KV v2 ──────────────────────────────────────────────────────────
$BAO secrets enable -address=$ADDR -path=secret kv-v2 2>/dev/null && ok "KV v2 enabled" || ok "KV v2 already enabled"

# ── Load secrets ──────────────────────────────────────────────────────────
source /opt/decisionmesh/.env.prod

info "Loading secrets into OpenBao..."

# DB credentials
$BAO kv put -address=$ADDR secret/decisionmesh/db \
    url="jdbc:postgresql://postgres:5432/${DB_NAME:-decisionmesh}" \
    username="${DB_USER:-decisionmesh}" \
    password="${DB_PASSWORD}"
ok "secret/decisionmesh/db"

# Redis
$BAO kv put -address=$ADDR secret/decisionmesh/redis \
    password="${REDIS_PASSWORD}"
ok "secret/decisionmesh/redis"

# Zitadel service token (used by OnboardingService for role assignment)
$BAO kv put -address=$ADDR secret/decisionmesh/auth \
    zitadel_service_token="${ZITADEL_SERVICE_TOKEN}" \
    zitadel_org_id="${ZITADEL_ORG_ID:-368134337511633629}" \
    zitadel_project_id="${ZITADEL_PROJECT_ID:-368134576352038839}"
ok "secret/decisionmesh/auth"

# LLM — Anthropic
echo ""
warn "Enter Anthropic API key (hidden):"
read -rsp "  ANTHROPIC_API_KEY: " ANTHROPIC_KEY; echo ""
$BAO kv put -address=$ADDR secret/decisionmesh/llm \
    provider="anthropic" \
    api_key="$ANTHROPIC_KEY" \
    model="claude-haiku-4-5-20251001"
ok "secret/decisionmesh/llm"

# Stripe
warn "Enter Stripe Secret Key (hidden):"
read -rsp "  STRIPE_SECRET_KEY: " STRIPE_KEY; echo ""
warn "Enter Stripe Webhook Secret (hidden):"
read -rsp "  STRIPE_WEBHOOK_SECRET: " STRIPE_WEBHOOK; echo ""
$BAO kv put -address=$ADDR secret/decisionmesh/stripe \
    secret_key="$STRIPE_KEY" \
    webhook_secret="$STRIPE_WEBHOOK"
ok "secret/decisionmesh/stripe"

# Razorpay
warn "Enter Razorpay Key ID:"
read -rp "  RAZORPAY_KEY_ID: " RAZORPAY_ID
warn "Enter Razorpay Key Secret (hidden):"
read -rsp "  RAZORPAY_SECRET: " RAZORPAY_SECRET; echo ""
$BAO kv put -address=$ADDR secret/decisionmesh/razorpay \
    key_id="$RAZORPAY_ID" \
    key_secret="$RAZORPAY_SECRET"
ok "secret/decisionmesh/razorpay"

# Email (EmailService)
warn "Enter SMTP password for mailer (hidden):"
read -rsp "  SMTP_PASSWORD: " SMTP_PASS; echo ""
$BAO kv put -address=$ADDR secret/decisionmesh/email \
    host="smtp.gmail.com" \
    port="587" \
    username="thirupala@gmail.com" \
    password="$SMTP_PASS"
ok "secret/decisionmesh/email"

# ── Create app token for Quarkus ──────────────────────────────────────────
$BAO policy write -address=$ADDR decisionmesh-api - << 'POLICY'
path "secret/data/decisionmesh/*" { capabilities = ["read"] }
path "secret/metadata/decisionmesh/*" { capabilities = ["list"] }
POLICY

APP_TOKEN=$($BAO token create -address=$ADDR \
    -policy=decisionmesh-api \
    -ttl=8760h \
    -field=token)

# ── Summary ───────────────────────────────────────────────────────────────
echo ""
echo -e "${CYAN}================================================${NC}"
ok "OpenBao ready!"
echo ""
echo -e "  ${YELLOW}Add this to .env.prod, then restart api:${NC}"
echo "  BAO_APP_TOKEN=$APP_TOKEN"
echo ""
echo "  docker compose -f docker-compose.prod.yml --env-file .env.prod up -d api"
echo -e "${CYAN}================================================${NC}"
