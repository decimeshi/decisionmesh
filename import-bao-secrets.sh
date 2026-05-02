#!/usr/bin/env bash
set -euo pipefail

# ---------------- CONFIG ----------------
BAO_ADDR="${BAO_ADDR:-http://localhost:8200}"
BAO_TOKEN="${BAO_TOKEN:-}"
INPUT_FILE="bao_secrets_export.json"

# ---------------- COLORS ----------------
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; WHITE='\033[1;37m'; GRAY='\033[0;37m'; NC='\033[0m'

# ---------------- LOG FUNCTIONS ----------------
info() { echo -e "${CYAN}[INFO]${NC}  $*"; }
ok()   { echo -e "${GREEN}[OK]${NC}    $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC}  $*"; }
fail() { echo -e "${RED}[ERROR]${NC} $*"; }

# ---------------- ARGS ----------------
while [[ $# -gt 0 ]]; do
    case $1 in
        --token) BAO_TOKEN="$2"; shift 2 ;;
        --addr)  BAO_ADDR="$2";  shift 2 ;;
        --file)  INPUT_FILE="$2"; shift 2 ;;
        *) echo "Unknown flag: $1"; exit 1 ;;
    esac
done

# ---------------- DEP CHECK ----------------
if ! command -v jq &>/dev/null; then
    fail "jq is required"
    exit 1
fi

if [[ ! -f "$INPUT_FILE" ]]; then
    fail "Missing file: $INPUT_FILE"
    exit 1
fi

# ---------------- READ FILE ----------------
EXPORTED_AT=$(jq -r '.exported_at' "$INPUT_FILE")
TOTAL_PATHS=$(jq -r '.total_paths' "$INPUT_FILE")
TOTAL_KEYS=$(jq -r '.total_keys' "$INPUT_FILE")

info "Reading $INPUT_FILE"
info "Exported at: $EXPORTED_AT | Paths: $TOTAL_PATHS | Keys: $TOTAL_KEYS"

# ---------------- TOKEN ----------------
if [[ -z "$BAO_TOKEN" ]]; then
    read -rsp "Paste OpenBao token: " BAO_TOKEN
    echo ""
fi

# ---------------- CONNECT ----------------
info "Connecting to $BAO_ADDR ..."
HEALTH=$(curl -sf "$BAO_ADDR/v1/sys/health" || true)

if [[ -z "$HEALTH" ]]; then
    fail "Cannot connect to OpenBao"
    exit 1
fi

ok "Connected"

# ---------------- TOKEN CHECK ----------------
TOKEN_CHECK=$(curl -sf \
    -H "X-Vault-Token: $BAO_TOKEN" \
    "$BAO_ADDR/v1/auth/token/lookup-self" || true)

if [[ -z "$TOKEN_CHECK" ]]; then
    fail "Invalid token"
    exit 1
fi

ok "Token valid"

# ---------------- ENABLE KV ----------------
info "Checking KV engine..."

MOUNTS=$(curl -sf \
    -H "X-Vault-Token: $BAO_TOKEN" \
    "$BAO_ADDR/v1/sys/mounts")

if ! echo "$MOUNTS" | jq -e '.["secret/"]' >/dev/null; then
    warn "'secret/' not found → enabling KV v2..."

    STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
        -X POST \
        -H "X-Vault-Token: $BAO_TOKEN" \
        -H "Content-Type: application/json" \
        -d '{"type":"kv","options":{"version":"2"}}' \
        "$BAO_ADDR/v1/sys/mounts/secret")

    if [[ "$STATUS" == "200" || "$STATUS" == "204" ]]; then
        ok "KV enabled"
    else
        fail "Failed to enable KV ($STATUS)"
        exit 1
    fi
else
    ok "KV already enabled"
fi

# ---------------- WRITE FUNCTION ----------------
write_secret() {
    local path="$1"
    local kv_version="$2"
    local data_json="$3"

    local api_path payload status

    if [[ "$kv_version" == "v2" ]]; then
        api_path=$(echo "$path" | sed 's|^secret/|secret/data/|')
        payload=$(jq -n --argjson d "$data_json" '{"data": $d}')
    else
        api_path="$path"
        payload="$data_json"
    fi

    status=$(curl -s -o /dev/null -w "%{http_code}" \
        -X POST \
        -H "X-Vault-Token: $BAO_TOKEN" \
        -H "Content-Type: application/json" \
        -d "$payload" \
        "$BAO_ADDR/v1/$api_path")

    echo "$status"
}

# ---------------- IMPORT ----------------
echo ""
info "Starting import..."
echo ""

IMPORTED=0
FAILED=0

while IFS= read -r path; do
    kv=$(jq -r --arg p "$path" '.secrets[$p].kv_version' "$INPUT_FILE")
    data=$(jq -c --arg p "$path" '.secrets[$p].data' "$INPUT_FILE")

    info "Writing $path ($kv)"

    status=$(write_secret "$path" "$kv" "$data")

    if [[ "$status" == "200" || "$status" == "204" ]]; then
        ok "Written"
        ((IMPORTED++))
    else
        fail "Failed ($status)"
        ((FAILED++))
    fi

done < <(jq -r '.secrets | keys[]' "$INPUT_FILE")

# ---------------- VERIFY ----------------
echo ""
info "Verifying..."

FIRST=$(jq -r '.secrets | keys[0]' "$INPUT_FILE")
VERIFY_PATH=$(echo "$FIRST" | sed 's|^secret/|secret/data/|')

VERIFY=$(curl -sf \
    -H "X-Vault-Token: $BAO_TOKEN" \
    "$BAO_ADDR/v1/$VERIFY_PATH" || true)

if [[ -n "$VERIFY" ]]; then
    ok "Verified"
else
    warn "Verification failed"
fi

# ---------------- SUMMARY ----------------
echo ""
echo "=============================="
echo "Imported: $IMPORTED"
echo "Failed:   $FAILED"
echo "=============================="