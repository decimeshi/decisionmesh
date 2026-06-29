#!/bin/bash
# ============================================================
#  DECIMESHI — DecisionMesh Local Infrastructure
#  Linux/WSL shell script equivalent of Makefile
#  Usage: ./dm.sh <command>
#  Run from: /mnt/d/DM/decisionmesh-infra/backend
# ============================================================

set -e

# ── Configuration ───────────────────────────────────────────
COMPOSE_BASE="docker-compose.yml"
COMPOSE_LOCAL="docker-compose.local.yml"
ENV_FILE=".env.local"
VAULT_CONTAINER="decisionmesh-local-openbao-1"
VAULT_ADDR="http://localhost:8200"
VAULT_TOKEN="dev-root-token"

COMPOSE="docker compose -f $COMPOSE_BASE -f $COMPOSE_LOCAL --env-file $ENV_FILE"

# ── Colours ─────────────────────────────────────────────────
GREEN='\033[0;32m'
TEAL='\033[0;36m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
BOLD='\033[1m'
NC='\033[0m' # No colour

# ── Helpers ─────────────────────────────────────────────────
info()    { echo -e "${TEAL}[INFO]${NC}  $1"; }
success() { echo -e "${GREEN}[OK]${NC}    $1"; }
warn()    { echo -e "${YELLOW}[WARN]${NC}  $1"; }
error()   { echo -e "${RED}[ERROR]${NC} $1"; exit 1; }
divider() { echo -e "${TEAL}────────────────────────────────────────────────${NC}"; }

# ── Check we are in the right directory ─────────────────────
check_dir() {
  if [ ! -f "$COMPOSE_BASE" ] || [ ! -f "$COMPOSE_LOCAL" ]; then
    error "docker-compose.yml or docker-compose.local.yml not found.\nRun this script from /mnt/d/DM/decisionmesh-infra/backend"
  fi
}

# ── Commands ────────────────────────────────────────────────

cmd_help() {
  echo ""
  echo -e "${BOLD}  DECIMESHI Local Infrastructure${NC}"
  divider
  echo "  ./dm.sh start         Full startup: up + seed vault"
  echo "  ./dm.sh up            Start all containers"
  echo "  ./dm.sh down          Stop all containers"
  echo "  ./dm.sh stop          Alias for down"
  echo "  ./dm.sh restart       Stop then start all containers"
  echo "  ./dm.sh ps            Show container status"
  echo "  ./dm.sh health        Check all container health"
  echo "  ./dm.sh seed          Seed secrets into OpenBao/Vault"
  echo "  ./dm.sh logs          Tail all logs"
  echo "  ./dm.sh logs-backend  Tail backend logs only"
  echo "  ./dm.sh logs-redis    Tail redis logs only"
  echo "  ./dm.sh logs-kafka    Tail kafka logs only"
  echo "  ./dm.sh logs-vault    Tail vault/openbao logs only"
  echo "  ./dm.sh logs-postgres Tail postgres logs only"
  echo "  ./dm.sh shell-redis   Open redis-cli shell"
  echo "  ./dm.sh shell-pg      Open psql shell"
  echo "  ./dm.sh shell-vault   Open vault shell"
  echo "  ./dm.sh clean         Stop and remove all volumes"
  echo "  ./dm.sh rebuild       Clean rebuild all images"
  echo "  ./dm.sh status        Show vault secret paths"
  divider
  echo ""
}

cmd_up() {
  check_dir
  info "Starting infrastructure containers..."
  $COMPOSE up -d
  success "Containers started. Run './dm.sh ps' to check status."
}

cmd_down() {
  check_dir
  info "Stopping all containers..."
  $COMPOSE down
  success "All containers stopped."
}

cmd_restart() {
  check_dir
  info "Restarting infrastructure..."
  $COMPOSE down
  $COMPOSE up -d
  success "Restart complete. Run './dm.sh seed' to re-seed Vault."
}

cmd_start() {
  check_dir
  cmd_up
  info "Waiting 10 seconds for services to initialise..."
  sleep 10
  cmd_seed
  echo ""
  divider
  success "Infrastructure is ready."
  echo -e "  ${BOLD}Next step:${NC} Start the backend from IntelliJ"
  echo -e "  ${BOLD}Profile:${NC}    local"
  echo -e "  ${BOLD}VM option:${NC}  -Dquarkus.profile=local"
  divider
  echo ""
}

cmd_ps() {
  check_dir
  $COMPOSE ps
}

cmd_health() {
  echo ""
  info "Container health status:"
  docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}" | grep decisionmesh || warn "No decisionmesh containers found"
  echo ""
}

cmd_logs() {
  check_dir
  $COMPOSE logs -f
}

cmd_logs_backend() {
  check_dir
  $COMPOSE logs -f backend 2>/dev/null || \
  docker logs -f decisionmesh-backend 2>/dev/null || \
  warn "Backend not running in Docker — start from IntelliJ"
}

cmd_logs_redis() {
  check_dir
  $COMPOSE logs -f redis
}

cmd_logs_kafka() {
  check_dir
  $COMPOSE logs -f kafka
}

cmd_logs_vault() {
  check_dir
  $COMPOSE logs -f openbao
}

cmd_logs_postgres() {
  check_dir
  $COMPOSE logs -f postgres
}

cmd_seed() {
  bash seed-vault.sh
}

cmd_seed_docker() {
  bash seed-vault.sh --docker
}

cmd_status() {
  info "Listing all secrets in OpenBao..."
  docker exec "$VAULT_CONTAINER" sh -c "
    export VAULT_ADDR=$VAULT_ADDR
    export VAULT_TOKEN=$VAULT_TOKEN
    vault kv list secret/decisionmesh
  "
}

cmd_shell_redis() {
  info "Connecting to Redis CLI (password: decisionmesh)..."
  docker exec -it decisionmesh-local-redis-1 redis-cli -a decisionmesh
}

cmd_shell_postgres() {
  info "Connecting to PostgreSQL..."
  docker exec -it decisionmesh-local-postgres-1 psql -U decisionmesh -d decisionmesh
}

cmd_shell_vault() {
  info "Connecting to OpenBao shell..."
  docker exec -it "$VAULT_CONTAINER" sh -c \
    "export VAULT_ADDR=$VAULT_ADDR; export VAULT_TOKEN=$VAULT_TOKEN; sh"
}

cmd_clean() {
  check_dir
  warn "This will remove all containers AND volumes."
  warn "Database data will be permanently lost."
  echo ""
  read -rp "Are you sure? [y/N] " confirm
  if [ "$confirm" != "y" ] && [ "$confirm" != "Y" ]; then
    info "Aborted."
    exit 0
  fi
  $COMPOSE down -v
  success "Clean complete. All containers and volumes removed."
}

cmd_rebuild() {
  check_dir
  info "Rebuilding all images..."
  $COMPOSE down
  $COMPOSE build --no-cache
  $COMPOSE up -d
  success "Rebuild complete."
}

# ── Router ───────────────────────────────────────────────────
COMMAND="${1:-help}"

case "$COMMAND" in
  help|--help|-h)   cmd_help ;;
  up)               cmd_up ;;
  down|stop)        cmd_down ;;
  restart)          cmd_restart ;;
  start)            cmd_start ;;
  ps)               cmd_ps ;;
  health)           cmd_health ;;
  seed)             cmd_seed ;;
  seed-docker)      cmd_seed_docker ;;
  status)           cmd_status ;;
  logs)             cmd_logs ;;
  logs-backend)     cmd_logs_backend ;;
  logs-redis)       cmd_logs_redis ;;
  logs-kafka)       cmd_logs_kafka ;;
  logs-vault)       cmd_logs_vault ;;
  logs-postgres)    cmd_logs_postgres ;;
  shell-redis)      cmd_shell_redis ;;
  shell-pg)         cmd_shell_postgres ;;
  shell-vault)      cmd_shell_vault ;;
  clean)            cmd_clean ;;
  rebuild)          cmd_rebuild ;;
  *)
    error "Unknown command: $COMMAND\nRun './dm.sh help' to see available commands."
    ;;
esac
