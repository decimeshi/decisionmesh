#!/usr/bin/env bash
# scripts/deploy.sh — Deploy DecisionMesh to Hetzner CX32
#
# First time: ssh root@SERVER_IP then bash scripts/deploy.sh --setup
# Deploy:     ./scripts/deploy.sh --host SERVER_IP

set -euo pipefail
RED='\033[0;31m'; GREEN='\033[0;32m'; CYAN='\033[0;36m'; NC='\033[0m'
info() { echo -e "${CYAN}[INFO]${NC}  $*"; }
ok()   { echo -e "${GREEN}[OK]${NC}    $*"; }
fail() { echo -e "${RED}[ERROR]${NC} $*"; exit 1; }

HOST=""; SETUP=false
while [[ $# -gt 0 ]]; do
    case $1 in
        --host)  HOST="$2"; shift 2 ;;
        --setup) SETUP=true; shift ;;
        *) fail "Unknown: $1" ;;
    esac
done

if [[ "$SETUP" == true ]]; then
    info "First-time server setup..."
    if ! command -v docker &>/dev/null; then
        curl -fsSL https://get.docker.com | sh
        systemctl enable --now docker
        ok "Docker installed"
    fi
    # Firewall
    ufw --force reset
    ufw default deny incoming; ufw default allow outgoing
    ufw allow 22/tcp; ufw allow 80/tcp; ufw allow 443/tcp
    ufw --force enable
    ok "Firewall: 22, 80, 443 only"
    # 2GB swap for ollama model loading
    if [[ ! -f /swapfile ]]; then
        fallocate -l 2G /swapfile; chmod 600 /swapfile
        mkswap /swapfile; swapon /swapfile
        echo '/swapfile none swap sw 0 0' >> /etc/fstab
        ok "2GB swap created"
    fi
    mkdir -p /opt/decisionmesh
    ok "Done. Next: scp files + run --host deploy"
    exit 0
fi

[[ -z "$HOST" ]] && fail "Usage: ./scripts/deploy.sh --host SERVER_IP"
[[ ! -f ".env.prod" ]] && fail ".env.prod not found. Copy .env.prod.example and fill in values."

info "Syncing files to $HOST..."
rsync -az --delete \
    --exclude='.git' --exclude='target/' --exclude='node_modules/' \
    . root@"$HOST":/opt/decisionmesh/
rsync -az .env.prod root@"$HOST":/opt/decisionmesh/.env.prod
ok "Files synced"

info "Deploying on server..."
ssh root@"$HOST" bash << 'REMOTE'
set -euo pipefail
cd /opt/decisionmesh

docker compose -f docker-compose.prod.yml --env-file .env.prod pull
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d --remove-orphans
docker compose -f docker-compose.staging.yml --env-file .env.prod up -d --remove-orphans

echo "Waiting for API health..."
for i in $(seq 1 30); do
    docker exec dm-api curl -sf http://localhost:8080/q/health/live >/dev/null 2>&1 && break
    echo "  attempt $i/30..."; sleep 5
done

docker ps --format "table {{.Names}}\t{{.Status}}"
REMOTE

ok "Deploy complete!"
echo "  Prod:    https://api.$HOST/q/health"
echo "  Staging: https://staging.$HOST/q/health"
