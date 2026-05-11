# =============================================================
# scripts/init-openbao.ps1
# Seeds all secrets into local OpenBao after container starts.
# Run once after: docker compose ... up -d
#
# Usage: .\scripts\init-openbao.ps1
# =============================================================

. "$PSScriptRoot\set-vault-env.ps1"

$VAULT_ADDR  = "http://localhost:8200"
$VAULT_TOKEN = "dev-root-token"
$BASE_URL    = "$VAULT_ADDR/v1/secret/data/decisionmesh"
$HEADERS     = @{ "X-Vault-Token" = $VAULT_TOKEN; "Content-Type" = "application/json" }

# Wait for OpenBao to be ready
Write-Host "Waiting for OpenBao to be ready..."
$ready = $false
for ($i = 0; $i -lt 30; $i++) {
    try {
        Invoke-RestMethod "$VAULT_ADDR/v1/sys/health" -EA Stop | Out-Null
        $ready = $true
        break
    } catch {
        Start-Sleep 2
    }
}
if (-not $ready) {
    Write-Error "OpenBao did not become ready in time. Is the container running?"
    exit 1
}
Write-Host "OpenBao is ready. Seeding secrets..."

# ----------------------------------------------------------
# DB
# ----------------------------------------------------------
Invoke-RestMethod "$BASE_URL/db" -Method POST -Headers $HEADERS -Body (ConvertTo-Json @{
    data = @{
        "quarkus.datasource.username"          = "decisionmesh"
        "quarkus.datasource.password"          = "decisionmesh"
        "quarkus.datasource.jdbc.url"          = "jdbc:postgresql://localhost:5432/decisionmesh"
        "quarkus.datasource.reactive.url"      = "postgresql://localhost:5432/decisionmesh"
        "quarkus.datasource.reactive.username" = "decisionmesh"
        "quarkus.datasource.reactive.password" = "decisionmesh"
    }
}) | Out-Null
Write-Host "  [OK] db"

# ----------------------------------------------------------
# Redis — password must match REDIS_PASSWORD in .env
# ----------------------------------------------------------
Invoke-RestMethod "$BASE_URL/redis" -Method POST -Headers $HEADERS -Body (ConvertTo-Json @{
    data = @{
        "quarkus.redis.hosts"    = "redis://localhost:6379"
        "quarkus.redis.password" = "redis_local"
    }
}) | Out-Null
Write-Host "  [OK] redis"

# ----------------------------------------------------------
# Kafka — localhost for local dev (not kafka:9092)
# ----------------------------------------------------------
Invoke-RestMethod "$BASE_URL/kafka" -Method POST -Headers $HEADERS -Body (ConvertTo-Json @{
    data = @{
        "kafka.bootstrap.servers"                                  = "localhost:9092"
        "mp.messaging.connector.smallrye-kafka.bootstrap.servers" = "localhost:9092"
    }
}) | Out-Null
Write-Host "  [OK] kafka"

# ----------------------------------------------------------
# Auth (OIDC / Zitadel)
# ----------------------------------------------------------
Invoke-RestMethod "$BASE_URL/auth" -Method POST -Headers $HEADERS -Body (ConvertTo-Json @{
    data = @{
        "quarkus.oidc.auth-server-url"  = "https://decisionmesh-1pgrry.eu1.zitadel.cloud"
        "quarkus.oidc.client-id"        = "368134611768783581"
        "quarkus.oidc.application-type" = "hybrid"
        "quarkus.oidc.redirect-path"    = "/auth/callback"
        "zitadel_service_account_token" = "REPLACE_WITH_REAL_TOKEN"
        "username"                      = "REPLACE_WITH_REAL_EMAIL"
    }
}) | Out-Null
Write-Host "  [OK] auth"

# ----------------------------------------------------------
# LLM API keys
# ----------------------------------------------------------
Invoke-RestMethod "$BASE_URL/llm" -Method POST -Headers $HEADERS -Body (ConvertTo-Json @{
    data = @{
        "llm.openai.api-key"    = "sk-local-dummy"
        "llm.gemini.api-key"    = "gemini-local-dummy"
        "llm.deepseek.api-key"  = "deepseek-local-dummy"
        "llm.anthropic.api-key" = "sk-local-dummy"
    }
}) | Out-Null
Write-Host "  [OK] llm"

# ----------------------------------------------------------
# Stripe
# ----------------------------------------------------------
Invoke-RestMethod "$BASE_URL/stripe" -Method POST -Headers $HEADERS -Body (ConvertTo-Json @{
    data = @{
        "stripe.secret.key" = "sk_test_your_stripe_test_key"
    }
}) | Out-Null
Write-Host "  [OK] stripe"

# ----------------------------------------------------------
# Razorpay
# ----------------------------------------------------------
Invoke-RestMethod "$BASE_URL/razorpay" -Method POST -Headers $HEADERS -Body (ConvertTo-Json @{
    data = @{
        "razorpay.key.id"         = "rzp_test_local_dummy"
        "razorpay.key.secret"     = "razorpay-local-dummy"
        "razorpay.webhook.secret" = "razorpay-webhook-dummy"
    }
}) | Out-Null
Write-Host "  [OK] razorpay"

# ----------------------------------------------------------
# Email
# ----------------------------------------------------------
Invoke-RestMethod "$BASE_URL/email" -Method POST -Headers $HEADERS -Body (ConvertTo-Json @{
    data = @{
        "quarkus.mailer.username" = "REPLACE_WITH_EMAIL"
        "quarkus.mailer.password" = "REPLACE_WITH_PASSWORD"
    }
}) | Out-Null
Write-Host "  [OK] email"

Write-Host ""
Write-Host "All secrets seeded successfully into OpenBao."
Write-Host "You can now run: mvn quarkus:dev"