#!/usr/bin/env bash
# import-bao-secrets.sh
# Imports secrets from a JSON file into OpenBao (KV v2).
#
# Usage:
#   ./import-bao-secrets.sh [--file FILE] [--addr ADDR] [--token TOKEN] [--dry-run]
#
# Flags:
#   --file     Path to secrets JSON file  (default: bao_secrets.template.json)
#   --addr     OpenBao address            (default: http://localhost:8200)
#   --token    OpenBao token              (prompted if omitted)
#   --dry-run  Print what would be written without actually writing

set -euo pipefail

# ── defaults ────────────────────────────────────────────────
BAO_ADDR="${BAO_ADDR:-http://localhost:8200}"
BAO_TOKEN="${BAO_TOKEN:-}"
INPUT_FILE="bao_secrets.template.json"
DRY_RUN=false

# ── colours ─────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'

info() { echo -e "${CYAN}[INFO]${NC}  $*"; }
ok()   { echo -e "${GREEN}[OK]${NC}    $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC}  $*"; }
fail() { echo -e "${RED}[FAIL]${NC}  $*"; }
head() { echo -e "\n${BOLD}$*${NC}"; }

# ── argument parsing ─────────────────────────────────────────
while [[ $# -gt 0 ]]; do
    case $1 in
        --token)   BAO_TOKEN="$2"; shift 2 ;;
        --addr)    BAO_ADDR="$2";  shift 2 ;;
        --file)    INPUT_FILE="$2"; shift 2 ;;
        --dry-run) DRY_RUN=true; shift ;;
        *) echo "Unknown flag: $1"; exit 1 ;;
    esac
done

# ── dependency check ─────────────────────────────────────────
if ! command -v jq &>/dev/null; then
    fail "jq is required. Install with: apt install jq / brew install jq"
    exit 1
fi
if ! command -v curl &>/dev/null; then
    fail "curl is required."
    exit 1
fi

# ── input file ───────────────────────────────────────────────
if [[ ! -f "$INPUT_FILE" ]]; then
    fail "File not found: $INPUT_FILE"
    exit 1
fi

head "DecisionMesh — OpenBao secret import"
[[ "$DRY_RUN" == "true" ]] && warn "DRY-RUN mode — nothing will be written"

EXPORTED_AT=$(jq -r '.exported_at' "$INPUT_FILE")
TOTAL_PATHS=$(jq '.secrets | length' "$INPUT_FILE")
info "File     : $INPUT_FILE"
info "Exported : $EXPORTED_AT"
info "Paths    : $TOTAL_PATHS"

# ── token prompt ─────────────────────────────────────────────
if [[ -z "$BAO_TOKEN" ]]; then
    echo ""
    info "Find your dev token:"
    echo "    docker exec openbao printenv VAULT_DEV_ROOT_TOKEN_ID"
    echo "    docker logs openbao 2>&1 | grep 'Root Token'"
    echo ""
    read -rsp "Paste OpenBao token: " BAO_TOKEN
    echo ""
fi

# ── helper: curl with timeout ────────────────────────────────
bao_curl() {
    curl --silent --show-error --max-time 10 "$@"
}

# ── connectivity check ───────────────────────────────────────
head "1. Connectivity"
info "Connecting to $BAO_ADDR ..."

HTTP_CODE=$(bao_curl -o /dev/null -w "%{http_code}" "$BAO_ADDR/v1/sys/health" || echo "000")
case "$HTTP_CODE" in
    200) ok "OpenBao is unsealed and ready" ;;
    429) ok "OpenBao is up (standby node)" ;;
    501) ok "OpenBao is up (not yet initialized — init it first)" ;;
    503) warn "OpenBao is up but SEALED — unseal before importing secrets" ;;
    000) fail "Cannot reach $BAO_ADDR (connection refused or timeout)"; exit 1 ;;
    *)   warn "Unexpected health response: HTTP $HTTP_CODE" ;;
esac

# ── token validation ─────────────────────────────────────────
head "2. Token validation"
TOKEN_STATUS=$(bao_curl -o /dev/null -w "%{http_code}" \
    -H "X-Vault-Token: $BAO_TOKEN" \
    "$BAO_ADDR/v1/auth/token/lookup-self" || echo "000")

if [[ "$TOKEN_STATUS" != "200" ]]; then
    fail "Token rejected (HTTP $TOKEN_STATUS). Check the token and retry."
    exit 1
fi

POLICIES=$(bao_curl \
    -H "X-Vault-Token: $BAO_TOKEN" \
    "$BAO_ADDR/v1/auth/token/lookup-self" \
    | jq -r '.data.policies | join(", ")')
ok "Token valid — policies: $POLICIES"

# ── ensure KV v2 is mounted at secret/ ───────────────────────
head "3. KV engine"
MOUNTS=$(bao_curl \
    -H "X-Vault-Token: $BAO_TOKEN" \
    "$BAO_ADDR/v1/sys/mounts")

MOUNT_TYPE=$(echo "$MOUNTS" | jq -r '.["secret/"].options.version // empty')

if [[ -z "$MOUNT_TYPE" ]]; then
    warn "'secret/' not found — enabling KV v2..."
    if [[ "$DRY_RUN" == "true" ]]; then
        info "[DRY-RUN] Would enable KV v2 at secret/"
    else
        STATUS=$(bao_curl -o /dev/null -w "%{http_code}" \
            -X POST \
            -H "X-Vault-Token: $BAO_TOKEN" \
            -H "Content-Type: application/json" \
            -d '{"type":"kv","options":{"version":"2"}}' \
            "$BAO_ADDR/v1/sys/mounts/secret")
        if [[ "$STATUS" == "200" || "$STATUS" == "204" ]]; then
            ok "KV v2 enabled at secret/"
        else
            fail "Failed to enable KV engine (HTTP $STATUS)"
            exit 1
        fi
    fi
elif [[ "$MOUNT_TYPE" == "2" ]]; then
    ok "KV v2 already mounted at secret/"
else
    warn "KV v1 is mounted at secret/ — import will treat all paths as v1"
fi

# ── write function ───────────────────────────────────────────
# Returns the HTTP status code on stdout.
write_secret() {
    local path="$1"
    local kv_version="$2"
    local data_json="$3"
    local api_path payload

    if [[ "$kv_version" == "v2" ]]; then
        # KV v2: data lives under secret/data/<path-without-secret/>
        api_path=$(echo "$path" | sed 's|^secret/|secret/data/|')
        payload=$(jq -n --argjson d "$data_json" '{"data": $d}')
    else
        api_path="$path"
        payload="$data_json"
    fi

    bao_curl -o /dev/null -w "%{http_code}" \
        -X POST \
        -H "X-Vault-Token: $BAO_TOKEN" \
        -H "Content-Type: application/json" \
        -d "$payload" \
        "$BAO_ADDR/v1/$api_path"
}

# ── import ───────────────────────────────────────────────────
head "4. Importing secrets"
IMPORTED=0
FAILED=0
declare -a FAILED_PATHS=()

# NOTE: Use process substitution (< <(...)) NOT a pipe (|).
# A pipe runs the while loop in a subshell, making IMPORTED/FAILED
# invisible to the parent process. This was the bug in the original script.
while IFS= read -r entry; do

    KEY=$(echo "$entry" | jq -r '.key')
    ACTUAL_PATH=$(echo "$entry" | jq -r '.value.path')
    KV=$(echo "$entry" | jq -r '.value.kv_version // "v2"')
    DATA=$(echo "$entry" | jq -c '.value.data')
    KEY_COUNT=$(echo "$DATA" | jq 'keys | length')

    if [[ -z "$ACTUAL_PATH" || "$ACTUAL_PATH" == "null" ]]; then
        fail "  Skipping '$KEY' — missing 'path' field"
        (( FAILED++ )) || true
        FAILED_PATHS+=("$KEY")
        continue
    fi

    info "  $ACTUAL_PATH ($KV) — $KEY_COUNT key(s)"

    if [[ "$DRY_RUN" == "true" ]]; then
        echo "  $(echo "$DATA" | jq -r 'keys[]' | sed 's/^/    key: /')"
        (( IMPORTED++ )) || true
        continue
    fi

    STATUS=$(write_secret "$ACTUAL_PATH" "$KV" "$DATA")

    if [[ "$STATUS" == "200" || "$STATUS" == "204" ]]; then
        ok "  Written: $ACTUAL_PATH"
        (( IMPORTED++ )) || true
    else
        fail "  Failed : $ACTUAL_PATH (HTTP $STATUS)"
        (( FAILED++ )) || true
        FAILED_PATHS+=("$ACTUAL_PATH")
    fi

done < <(jq -c '.secrets | to_entries[]' "$INPUT_FILE")

# ── verify ───────────────────────────────────────────────────
if [[ "$DRY_RUN" == "false" && "$IMPORTED" -gt 0 ]]; then
    head "5. Verification"

    # Use the 'path' field from the first entry (not the outer key)
    FIRST_PATH=$(jq -r '.secrets | to_entries[0].value.path' "$INPUT_FILE")
    FIRST_KV=$(jq -r '.secrets | to_entries[0].value.kv_version // "v2"' "$INPUT_FILE")

    if [[ "$FIRST_KV" == "v2" ]]; then
        VERIFY_PATH=$(echo "$FIRST_PATH" | sed 's|^secret/|secret/data/|')
    else
        VERIFY_PATH="$FIRST_PATH"
    fi

    VERIFY_STATUS=$(bao_curl -o /dev/null -w "%{http_code}" \
        -H "X-Vault-Token: $BAO_TOKEN" \
        "$BAO_ADDR/v1/$VERIFY_PATH")

    if [[ "$VERIFY_STATUS" == "200" ]]; then
        ok "Read-back of '$FIRST_PATH' succeeded"
    else
        warn "Read-back returned HTTP $VERIFY_STATUS — verify manually:"
        echo "    docker exec openbao bao kv get $FIRST_PATH"
    fi
fi

# ── summary ─────────────────────────────────────────────────
head "Summary"
echo "  Imported : $IMPORTED"
echo "  Failed   : $FAILED"

if [[ ${#FAILED_PATHS[@]} -gt 0 ]]; then
    echo ""
    warn "Failed paths — retry manually:"
    for p in "${FAILED_PATHS[@]}"; do
        echo "    docker exec openbao bao kv put $p @payload.json"
    done
fi

echo ""
info "Verify all paths:"
echo "    docker exec openbao bao kv list secret/decisionmesh"
echo "    docker exec openbao bao kv get  secret/decisionmesh/db"
