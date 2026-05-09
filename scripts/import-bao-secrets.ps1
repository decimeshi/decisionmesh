# import-bao-secrets.ps1
# Imports secrets from a JSON file into OpenBao (KV v2).
#
# Usage:
#   .\import-bao-secrets.ps1
#   .\import-bao-secrets.ps1 -Token "dev-root-token"
#   .\import-bao-secrets.ps1 -Token "dev-root-token" -InputFile "bao_secrets.dev.json"
#   .\import-bao-secrets.ps1 -InputFile "bao_secrets.prod.json" -DryRun
#
# ExecutionPolicy:
#   Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass

param(
    [string]$BaoAddr   = "http://localhost:8200",
    [string]$Token     = "",
    [string]$InputFile = "bao_secrets.template.json",   # <- no hardcoded user path
    [switch]$DryRun    = $false
)

$ErrorActionPreference = "Stop"

function Write-Info { param($m) Write-Host "[INFO]  $m" -ForegroundColor Cyan   }
function Write-Ok   { param($m) Write-Host "[OK]    $m" -ForegroundColor Green  }
function Write-Warn { param($m) Write-Host "[WARN]  $m" -ForegroundColor Yellow }
function Write-Fail { param($m) Write-Host "[FAIL]  $m" -ForegroundColor Red    }
function Write-Head { param($m) Write-Host "`n$m" -ForegroundColor White        }

# ── Dry-run notice ────────────────────────────────────────────
if ($DryRun) {
    Write-Warn "DRY-RUN mode — nothing will be written to OpenBao"
}

# ── Input file ────────────────────────────────────────────────
if (-not (Test-Path $InputFile)) {
    Write-Fail "File not found: $InputFile"
    Write-Host "  Available templates: bao_secrets.template.json, bao_secrets.dev.json" -ForegroundColor Gray
    exit 1
}

Write-Head "DecisionMesh — OpenBao secret import"
$export      = Get-Content -Path $InputFile -Raw -Encoding UTF8 | ConvertFrom-Json
$exportedAt  = $export.exported_at
$totalPaths  = ($export.secrets.PSObject.Properties | Measure-Object).Count
Write-Info "File     : $InputFile"
Write-Info "Exported : $exportedAt"
Write-Info "Paths    : $totalPaths"

# ── Token prompt ──────────────────────────────────────────────
if (-not $Token) {
    Write-Host ""
    Write-Info "Find your dev token:"
    Write-Host "    docker exec openbao printenv VAULT_DEV_ROOT_TOKEN_ID" -ForegroundColor Gray
    Write-Host '    docker logs openbao 2>&1 | Select-String "Root Token"'  -ForegroundColor Gray
    Write-Host ""
    $Token = Read-Host "Paste your OpenBao token"
}

# ── Helper: REST call with timeout ───────────────────────────
function Invoke-Bao {
    param(
        [string]$Uri,
        [string]$Method  = "GET",
        [hashtable]$Headers = @{},
        [string]$Body    = $null
    )
    $base = @{
        Uri             = $Uri
        Method          = $Method
        Headers         = $Headers
        TimeoutSec      = 10
        UseBasicParsing = $true
        ErrorAction     = "Stop"
    }
    if ($Body) { $base["Body"] = $Body; $base["ContentType"] = "application/json" }
    return Invoke-RestMethod @base
}

$authHeaders = @{ "X-Vault-Token" = $Token }

# ── Connectivity ──────────────────────────────────────────────
Write-Head "1. Connectivity"
Write-Info "Connecting to $BaoAddr ..."
try {
    $health = Invoke-Bao -Uri "$BaoAddr/v1/sys/health"
    if ($health.sealed) {
        Write-Warn "OpenBao is SEALED — unseal before importing secrets"
    } else {
        Write-Ok "OpenBao is unsealed | version: $($health.version)"
    }
} catch {
    $code = $_.Exception.Response.StatusCode.value__
    switch ($code) {
        501 { Write-Ok "OpenBao is up but not yet initialized (HTTP 501)" }
        503 { Write-Warn "OpenBao is up but sealed (HTTP 503) — unseal first" }
        $null {
            Write-Fail "Cannot reach $BaoAddr — is OpenBao running?"
            Write-Host "  Start with: docker compose ... up -d openbao" -ForegroundColor Gray
            exit 1
        }
        default { Write-Warn "Unexpected health response: HTTP $code" }
    }
}

# ── Token validation ──────────────────────────────────────────
Write-Head "2. Token validation"
try {
    $me = Invoke-Bao -Uri "$BaoAddr/v1/auth/token/lookup-self" -Headers $authHeaders
    $policies = $me.data.policies -join ", "
    Write-Ok "Token valid — policies: $policies"
} catch {
    Write-Fail "Token rejected (HTTP $($_.Exception.Response.StatusCode.value__))"
    exit 1
}

# ── Ensure KV v2 is mounted at secret/ ───────────────────────
Write-Head "3. KV engine"
try {
    $mounts = Invoke-Bao -Uri "$BaoAddr/v1/sys/mounts" -Headers $authHeaders
    $secretMount = $mounts."secret/"
    if ($null -eq $secretMount) {
        Write-Warn "'secret/' not mounted — enabling KV v2..."
        if (-not $DryRun) {
            $body = '{"type":"kv","options":{"version":"2"}}'
            Invoke-Bao -Uri "$BaoAddr/v1/sys/mounts/secret" -Method POST `
                       -Headers $authHeaders -Body $body | Out-Null
            Write-Ok "KV v2 enabled at secret/"
        } else {
            Write-Info "[DRY-RUN] Would enable KV v2 at secret/"
        }
    } elseif ($secretMount.options.version -eq "2") {
        Write-Ok "KV v2 already mounted at secret/"
    } else {
        Write-Warn "KV v1 detected — paths will be written as v1"
    }
} catch {
    Write-Warn "Could not read mounts: $($_.Exception.Message)"
}

# ── Write-Secret function ─────────────────────────────────────
function Write-BaoSecret {
    param(
        [string]$Path,
        [string]$KvVersion,
        [object]$Data
    )

    if ($KvVersion -eq "v2") {
        $apiPath = $Path -replace "^secret/", "secret/data/"
        $payload = @{ data = $Data } | ConvertTo-Json -Depth 10 -Compress
    } else {
        $apiPath = $Path
        $payload = $Data | ConvertTo-Json -Depth 10 -Compress
    }

    try {
        Invoke-Bao -Uri "$BaoAddr/v1/$apiPath" -Method POST `
                   -Headers $authHeaders -Body $payload | Out-Null
        return "ok"
    } catch {
        $code = $_.Exception.Response.StatusCode.value__
        return "fail:$($code ?? 'unknown')"
    }
}

# ── Import ────────────────────────────────────────────────────
Write-Head "4. Importing secrets"

$imported    = 0
$failed      = 0
$failedPaths = @()

foreach ($prop in $export.secrets.PSObject.Properties) {
    $entry      = $prop.Value
    $actualPath = $entry.path
    $kvVersion  = if ($entry.kv_version) { $entry.kv_version } else { "v2" }
    $data       = $entry.data
    $keyCount   = ($data.PSObject.Properties | Measure-Object).Count

    if (-not $actualPath) {
        Write-Fail "  Skipping '$($prop.Name)' — missing 'path' field"
        $failed++
        $failedPaths += $prop.Name
        continue
    }

    Write-Info "  $actualPath ($kvVersion) — $keyCount key(s)"

    if ($DryRun) {
        $data.PSObject.Properties.Name | ForEach-Object { Write-Host "    key: $_" -ForegroundColor Gray }
        $imported++
        continue
    }

    $result = Write-BaoSecret -Path $actualPath -KvVersion $kvVersion -Data $data

    if ($result -eq "ok") {
        Write-Ok "  Written: $actualPath"
        $imported++
    } else {
        Write-Fail "  Failed : $actualPath ($result)"
        $failed++
        $failedPaths += $actualPath
    }
}

# ── Verify ────────────────────────────────────────────────────
if (-not $DryRun -and $imported -gt 0) {
    Write-Head "5. Verification"
    $firstEntry = $export.secrets.PSObject.Properties | Select-Object -First 1
    $firstPath  = $firstEntry.Value.path
    $firstKv    = if ($firstEntry.Value.kv_version) { $firstEntry.Value.kv_version } else { "v2" }

    if ($firstKv -eq "v2") {
        $verifyPath = $firstPath -replace "^secret/", "secret/data/"
    } else {
        $verifyPath = $firstPath
    }

    try {
        Invoke-Bao -Uri "$BaoAddr/v1/$verifyPath" -Headers $authHeaders | Out-Null
        Write-Ok "Read-back of '$firstPath' succeeded"
    } catch {
        Write-Warn "Read-back returned HTTP $($_.Exception.Response.StatusCode.value__)"
        Write-Host "  Verify manually: docker exec openbao bao kv get $firstPath" -ForegroundColor Gray
    }
}

# ── Summary ───────────────────────────────────────────────────
Write-Head "Summary"
Write-Host "  Imported : $imported" -ForegroundColor White
Write-Host "  Failed   : $failed"   -ForegroundColor White

if ($failedPaths.Count -gt 0) {
    Write-Host ""
    Write-Warn "Failed paths — retry manually:"
    $failedPaths | ForEach-Object { Write-Host "    docker exec openbao bao kv get $_" -ForegroundColor Yellow }
}

Write-Host ""
Write-Info "Verify all paths:"
Write-Host "    docker exec openbao bao kv list secret/decisionmesh" -ForegroundColor Gray
Write-Host "    docker exec openbao bao kv get  secret/decisionmesh/db" -ForegroundColor Gray
