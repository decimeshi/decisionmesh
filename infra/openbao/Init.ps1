# infra/openbao/init.ps1
# Run this once after docker-compose up to seed all DecisionMesh secrets.
# Edit your real API keys below before running.
# Usage: .\infra\openbao\init.ps1

$BAO_ADDR = "http://localhost:8200"
$BAO_TOKEN = "dev-root-token"

$headers = @{
    "X-Vault-Token" = $BAO_TOKEN
    "Content-Type"  = "application/json"
}

function Write-Secret($path, $data) {
    $url  = "$BAO_ADDR/v1/secret/data/$path"
    $body = @{ data = $data } | ConvertTo-Json -Depth 5
    try {
        Invoke-RestMethod -Uri $url -Method POST -Headers $headers -Body $body | Out-Null
        Write-Host "[OpenBao] Stored: secret/$path" -ForegroundColor Green
    } catch {
        Write-Host "[OpenBao] Failed: secret/$path — $_" -ForegroundColor Red
    }
}

function Enable-KV {
    $url  = "$BAO_ADDR/v1/sys/mounts/secret"
    $body = @{ type = "kv"; options = @{ version = "2" } } | ConvertTo-Json
    try {
        Invoke-RestMethod -Uri $url -Method POST -Headers $headers -Body $body | Out-Null
        Write-Host "[OpenBao] KV engine enabled" -ForegroundColor Green
    } catch {
        Write-Host "[OpenBao] KV already enabled (OK)" -ForegroundColor Yellow
    }
}

# ── Wait for OpenBao ──────────────────────────────────────────────────────────
Write-Host "[OpenBao] Waiting for OpenBao to be ready..."
$ready = $false
for ($i = 0; $i -lt 10; $i++) {
    try {
        Invoke-RestMethod -Uri "$BAO_ADDR/v1/sys/health" -Method GET | Out-Null
        $ready = $true
        break
    } catch {
        Start-Sleep -Seconds 2
    }
}
if (-not $ready) {
    Write-Host "[OpenBao] OpenBao not reachable at $BAO_ADDR" -ForegroundColor Red
    exit 1
}
Write-Host "[OpenBao] OpenBao is ready." -ForegroundColor Green

# ── Enable KV engine ─────────────────────────────────────────────────────────
Enable-KV

# ── Database credentials ──────────────────────────────────────────────────────
Write-Secret "decisionmesh/db" @{
    url      = "jdbc:postgresql://localhost:5432/decisionmesh"
    username = "decisionmesh"
    password = "decisionmesh"
}

Write-Secret "decisionmesh/auth" @{
    zitadel_service_account_token = "sGNiaWA0fIgHMSSNlRUU8Fg..."
    vault_token                   = "dev-root-token"
}

Write-Secret "decisionmesh/email" @{
    username = "thirupala@gmail.com"
    password = "arvx zsho ibpj bvrl"
}

Write-Secret "decisionmesh/stripe" @{
    secret_key      = "sk_test_..."
    webhook_secret  = "whsec_..."
}

Write-Secret "decisionmesh/razorpay" @{
    key_id          = "rzp_test_..."
    key_secret      = "your_secret"
    webhook_secret  = "your_webhook_secret"
}

Write-Secret "decisionmesh/llm" @{
    openai_api_key    = "sk-..."
    anthropic_api_key = "sk-ant-..."
    gemini_api_key    = "your_key"
    deepseek_api_key  = "your_key"
    azure_api_key     = "your_key"
    azure_resource    = "your_resource"
}
# ── Redis credentials ─────────────────────────────────────────────────────────
Write-Secret "decisionmesh/redis" @{
    host     = "localhost"
    port     = "6379"
    password = ""
}

Write-Host ""
Write-Host "[OpenBao] Bootstrap complete!" -ForegroundColor Cyan
Write-Host "[OpenBao] UI: http://localhost:8200 — Token: dev-root-token" -ForegroundColor Cyan