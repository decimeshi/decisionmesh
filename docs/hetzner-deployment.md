# DecisionMesh — Hetzner Deployment Runbook

## Prerequisites

- Docker Desktop running on Windows
- Logged into GitHub Container Registry:

```powershell
docker login ghcr.io -u thirupala -p YOUR_GITHUB_TOKEN
```

> Generate token at `https://github.com/settings/tokens/new`
> Required scopes: `write:packages`, `read:packages`, `repo`

---

## STAGING DEPLOYMENT

### Windows — Build & Push

**1. Switch to staging branch and pull latest:**
```powershell
cd D:\DM\decisionmesh
git checkout staging
git pull origin staging
```

**2. Build JAR:**
```powershell
mvn clean package -DskipTests
```

**3. Build Docker image:**
```powershell
docker build -t ghcr.io/thirupala/decisionmesh:staging .
```

**4. Push to GitHub Container Registry:**
```powershell
docker push ghcr.io/thirupala/decisionmesh:staging
```

---

### Hetzner — Deploy

**5. SSH into server:**
```powershell
ssh decisionmesh@178.105.87.59
```

**6. Pull new image:**
```bash
cd /opt/decisionmesh/infra
docker pull ghcr.io/thirupala/decisionmesh:staging
```

**7. Restart API container only:**
```bash
docker compose -f backend/docker-compose.staging.yml --env-file .env.staging \
  up -d --no-deps --force-recreate api
```

**8. Verify startup:**
```bash
docker logs dm-api-staging --tail 50
```

**9. Health check:**
```bash
curl -s https://api-staging.decimeshi.com/health | python3 -m json.tool
```

✅ All checks should show `"status": "UP"`

---

## PRODUCTION DEPLOYMENT

> ⚠️ Always verify staging is healthy before deploying to production.

### Windows — Build & Push

**1. Switch to main branch and pull latest:**
```powershell
cd D:\DM\decisionmesh
git checkout main
git pull origin main
```

**2. Build JAR:**
```powershell
mvn clean package -DskipTests
```

**3. Build Docker image:**
```powershell
docker build -t ghcr.io/thirupala/decisionmesh:latest .
```

**4. Push to GitHub Container Registry:**
```powershell
docker push ghcr.io/thirupala/decisionmesh:latest
```

---

### Hetzner — Deploy

**5. SSH into server:**
```powershell
ssh decisionmesh@178.105.87.59
```

**6. Pull new image:**
```bash
cd /opt/decisionmesh/infra
docker pull ghcr.io/thirupala/decisionmesh:latest
```

**7. Restart API container only:**
```bash
docker compose -f backend/docker-compose.yml -f backend/docker-compose.prod.yml \
  --env-file .env.prod up -d --no-deps --force-recreate api
```

**8. Verify startup:**
```bash
docker logs dm-api --tail 50
```

**9. Health check:**
```bash
curl -s https://api.decimeshi.com/health | python3 -m json.tool
```

✅ All checks should show `"status": "UP"`

---

## FRONTEND DEPLOYMENT

Frontend deploys **automatically via Cloudflare Pages** on every push — no manual steps needed.

| Branch    | Environment | URL                              |
|-----------|-------------|----------------------------------|
| `staging` | Staging     | `https://app-staging.decimeshi.com` |
| `main`    | Production  | `https://decimeshi.com`          |

```powershell
# Deploy staging frontend
cd D:\DM\decisionmesh-ui
git checkout staging
git push origin staging        # Cloudflare auto-builds and deploys

# Deploy production frontend
git checkout main
git merge staging
git push origin main           # Cloudflare auto-builds and deploys
```

Monitor builds at: `https://dash.cloudflare.com` → Workers & Pages → decisionmesh-ui

---

## SCHEMA MANAGEMENT

> ⚠️ Dropping the schema deletes ALL data. Only do this when explicitly required
> (e.g. breaking entity changes, major migrations, or resetting a test environment).
> **Never drop the production schema unless absolutely necessary and approved.**

Quarkus manages the schema automatically via Flyway on startup.
Normal deployments — including entity changes and new columns — do **not** require
dropping the schema.

**When to drop:**
- Staging: resetting test data, or after breaking Flyway migration conflicts
- Production: never under normal circumstances

**How to drop (staging only — use with caution):**
```bash
docker exec -it decisionmesh-staging-postgres-1 psql -U decisionmesh -d decisionmesh -c \
  'DROP SCHEMA public CASCADE; CREATE SCHEMA public; GRANT ALL ON SCHEMA public TO decisionmesh; GRANT ALL ON SCHEMA public TO public;'
```

After dropping, restart the API — Quarkus will recreate all tables and re-seed data on startup:
```bash
docker compose -f backend/docker-compose.staging.yml --env-file .env.staging \
  up -d --no-deps --force-recreate api
```

---

## FULL STACK RESTART (infra down)

If postgres, redis, kafka, or openbao containers are stopped, use the start scripts
instead of recreating the API alone.

**Staging:**
```bash
cd /opt/decisionmesh/infra
bash scripts/start-staging.sh
```

**Production:**
```bash
cd /opt/decisionmesh/infra
bash scripts/start-prod.sh
# OpenBao needs manual unseal after prod restart:
bash scripts/unseal-prod.sh
```

**Gateway (Caddy — if down):**
```bash
docker compose -f backend/docker-compose.gateway.yml up -d
```

---

## USEFUL COMMANDS

**Check all running containers:**
```bash
docker ps | grep -E 'dm-api|postgres|redis|kafka|openbao|caddy'
```

**Live logs:**
```bash
docker logs dm-api-staging -f    # staging
docker logs dm-api -f            # production
```

**Import secrets into OpenBao (after staging restart):**
```bash
cd /opt/decisionmesh/infra
bash scripts/import-bao-secrets.sh --env staging
```

**Container resource usage:**
```bash
docker stats dm-api-staging dm-api --no-stream
```

---

## ENVIRONMENTS AT A GLANCE

| | Staging | Production |
|---|---|---|
| **API URL** | `https://api-staging.decimeshi.com` | `https://api.decimeshi.com` |
| **Frontend URL** | `https://app-staging.decimeshi.com` | `https://decimeshi.com` |
| **Docker image** | `ghcr.io/thirupala/decisionmesh:staging` | `ghcr.io/thirupala/decisionmesh:latest` |
| **API container** | `dm-api-staging` | `dm-api` |
| **Port** | `8081` | `8080` |
| **OpenBao mode** | Dev (auto-unseal) | Server (manual unseal) |
| **Branch** | `staging` | `main` |
