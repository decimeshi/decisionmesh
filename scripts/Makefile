# ============================================================
#  DECIMESHI — DecisionMesh Local Infrastructure
#  Usage: make <target>
#  Run from: /mnt/d/DM/decisionmesh-infra/backend
# ============================================================

COMPOSE_BASE    = docker-compose.yml
COMPOSE_LOCAL   = docker-compose.local.yml
ENV_FILE        = .env.local
VAULT_CONTAINER = decisionmesh-local-openbao-1
VAULT_ADDR      = http://localhost:8200
VAULT_TOKEN     = dev-root-token

COMPOSE = docker compose \
	-f $(COMPOSE_BASE) \
	-f $(COMPOSE_LOCAL) \
	--env-file $(ENV_FILE)

.PHONY: help up down restart ps logs seed start stop clean rebuild \
        logs-backend logs-redis logs-kafka logs-vault logs-postgres \
        health shell-redis shell-postgres shell-vault

# ── Default target ─────────────────────────────────────────
help:
	@echo ""
	@echo "  DECIMESHI Local Infrastructure"
	@echo "  ────────────────────────────────────────────────"
	@echo "  make start        Full startup: up + seed vault"
	@echo "  make up           Start all containers"
	@echo "  make down         Stop all containers"
	@echo "  make restart      Stop then start all containers"
	@echo "  make stop         Alias for down"
	@echo "  make ps           Show container status"
	@echo "  make health       Check all container health"
	@echo "  make seed         Seed secrets into OpenBao/Vault"
	@echo "  make logs         Tail all logs"
	@echo "  make logs-backend Tail backend logs only"
	@echo "  make logs-redis   Tail redis logs only"
	@echo "  make logs-kafka   Tail kafka logs only"
	@echo "  make logs-vault   Tail vault logs only"
	@echo "  make logs-postgres Tail postgres logs only"
	@echo "  make shell-redis  Open redis-cli shell"
	@echo "  make shell-postgres Open psql shell"
	@echo "  make shell-vault  Open vault shell"
	@echo "  make clean        Stop containers and remove volumes"
	@echo "  make rebuild      Clean rebuild all images"
	@echo "  ────────────────────────────────────────────────"
	@echo ""

# ── Core lifecycle ─────────────────────────────────────────
up:
	@echo "Starting infrastructure containers..."
	$(COMPOSE) up -d
	@echo "Done. Run 'make ps' to check status."

down:
	@echo "Stopping all containers..."
	$(COMPOSE) down
	@echo "All containers stopped."

stop: down

restart:
	@echo "Restarting infrastructure..."
	$(COMPOSE) down
	$(COMPOSE) up -d
	@echo "Restart complete. Run 'make seed' to re-seed Vault."

# ── Full startup with vault seeding ───────────────────────
# Default: IntelliJ mode (backend runs on host with localhost URLs)
start: up
	@echo "Waiting 10 seconds for services to initialise..."
	@sleep 10
	@$(MAKE) seed
	@echo ""
	@echo "  Infrastructure is ready."
	@echo "  Start the backend from IntelliJ."
	@echo "  Profile: local"
	@echo "  VM option: -Dquarkus.profile=local"
	@echo ""
	@echo "  NOTE: If running backend in Docker use: make seed-docker"
	@echo ""

# ── Status and health ──────────────────────────────────────
ps:
	$(COMPOSE) ps

health:
	@echo "Container health status:"
	@docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}" | grep decisionmesh

# ── Logs ───────────────────────────────────────────────────
logs:
	$(COMPOSE) logs -f

logs-backend:
	$(COMPOSE) logs -f backend 2>/dev/null || docker logs -f decisionmesh-backend 2>/dev/null || echo "Backend not running in Docker - start from IntelliJ"

logs-redis:
	$(COMPOSE) logs -f redis

logs-kafka:
	$(COMPOSE) logs -f kafka

logs-vault:
	$(COMPOSE) logs -f openbao

logs-postgres:
	$(COMPOSE) logs -f postgres

# ── Vault seeding ──────────────────────────────────────────
# IntelliJ mode (default) - backend on localhost
seed:
	@bash seed-vault.sh

# Docker mode - backend inside container
seed-docker:
	@bash seed-vault.sh --docker

# ── Interactive shells ─────────────────────────────────────
shell-redis:
	@echo "Connecting to Redis CLI (password: decisionmesh)..."
	docker exec -it decisionmesh-local-redis-1 redis-cli -a decisionmesh

shell-postgres:
	@echo "Connecting to PostgreSQL..."
	docker exec -it decisionmesh-local-postgres-1 psql -U decisionmesh -d decisionmesh

shell-vault:
	@echo "Connecting to OpenBao shell..."
	docker exec -it $(VAULT_CONTAINER) sh -c \
		"export VAULT_ADDR=$(VAULT_ADDR); export VAULT_TOKEN=$(VAULT_TOKEN); sh"

# ── Clean and rebuild ──────────────────────────────────────
clean:
	@echo "WARNING: This will remove all containers AND volumes (database data will be lost)"
	@read -p "Are you sure? [y/N] " confirm && [ "$$confirm" = "y" ] || exit 1
	$(COMPOSE) down -v
	@echo "Clean complete. All data removed."

rebuild:
	@echo "Rebuilding all images..."
	$(COMPOSE) down
	$(COMPOSE) build --no-cache
	$(COMPOSE) up -d
	@echo "Rebuild complete."
