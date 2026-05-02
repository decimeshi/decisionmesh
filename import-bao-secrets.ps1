# import-bao-secrets.ps1
# Imports secrets from bao_secrets_export.json into OpenBao on Windows.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File .\import-bao-secrets.ps1
#   powershell -ExecutionPolicy Bypass -File .\import-bao-secrets.ps1 -Token "dev-token"
#   powershell -ExecutionPolicy Bypass -File .\import-bao-secrets.ps1 -Token "dev-token" -Input "secrets.json"

param(
    [string]$BaoAddr    = "http://localhost:8200",
    [string]$Token      = "",
    [string]$InputFile  = "bao_secrets_export.json"
)

function Write-Info { param($m) Write-Host "[INFO]  $m" -ForegroundColor Cyan   }
function Write-Ok   { param($m) Write-Host "[OK]    $m" -ForegroundColor Green  }
function Write-Warn { param($m) Write-Host "[WARN]  $m" -ForegroundColor Yellow }
function Write-Fail { param($m) Write-Host "[ERROR] $m" -ForegroundColor Red    }

# Check input file exists
if (-not (Test-Path $InputFile)) {
    Write-Fail "Input file not found: $InputFile"
    Write-Host "  Copy bao_secrets_export.json to this folder first." -ForegroundColor Gray
    exit 1
}

# Prompt for token if not supplied
if (-not $Token) {
    Write-Info "No token supplied."
    Write-Host ""
    Write-Host "  Find your dev token:" -ForegroundColor Gray
    Write-Host '    docker logs openbao 2>&1 | Select-String "Root Token"' -ForegroundColor Gray
    Write-Host "    docker exec openbao printenv VAULT_DEV_ROOT_TOKEN_ID" -ForegroundColor Gray
    Write-Host ""
    $Token = Read-Host "  Paste your OpenBao root/dev token"
}

# Read and parse JSON
Write-Info "Reading $InputFile ..."
$export     = Get-Content -Path $InputFile -Raw -Encoding UTF8 | ConvertFrom-Json
$exportedAt = $export.exported_at
$totalPaths = $export.total_paths
$totalKeys  = $export.total_keys
Write-Info "Exported at: $exportedAt | Paths: $totalPaths | Keys: $totalKeys"

# Test connectivity
Write-Info "Connecting to $BaoAddr ..."
try {
    $health = Invoke-RestMethod -Uri "$BaoAddr/v1/sys/health" -Method GET -ErrorAction Stop
    Write-Ok "OpenBao reachable - version: $($health.version) | sealed: $($health.sealed)"
} catch {
    Write-Fail "Cannot reach $BaoAddr - is OpenBao running?"
    Write-Host "  Start with: docker start openbao" -ForegroundColor Gray
    exit 1
}

# Verify token
try {
    $me = Invoke-RestMethod `
        -Uri "$BaoAddr/v1/auth/token/lookup-self" `
        -Method GET `
        -Headers @{ "X-Vault-Token" = $Token } `
        -ErrorAction Stop
    $policyList = $me.data.policies -join ", "
    Write-Ok "Token valid - policies: $policyList"
} catch {
    Write-Fail "Token rejected. Check token and try again."
    exit 1
}

# Write a single secret path
function Write-Secret {
    param(
        [string]$Path,
        [string]$KvVersion,
        [object]$Data
    )

    # Build payload
    if ($KvVersion -eq "v2") {
        $apiPath = $Path -replace "^secret/", "secret/data/"
        $payload = @{ data = $Data } | ConvertTo-Json -Depth 10
    } else {
        $apiPath = $Path
        $payload = $Data | ConvertTo-Json -Depth 10
    }

    try {
        $res = Invoke-RestMethod `
            -Uri "$BaoAddr/v1/$apiPath" `
            -Method POST `
            -Headers @{ "X-Vault-Token" = $Token; "Content-Type" = "application/json" } `
            -Body $payload `
            -ErrorAction Stop
        return "ok"
    } catch {
        $status = $_.Exception.Response.StatusCode.value__
        return "fail:$status"
    }
}

# Run import
Write-Host ""
Write-Info "Starting import into $BaoAddr ..."
Write-Host ""

$imported    = 0
$failed      = 0
$failedPaths = @()

$secretPaths = $export.secrets.PSObject.Properties.Name

foreach ($path in $secretPaths) {
    $entry     = $export.secrets.$path
    $kvVersion = $entry.kv_version
    $data      = $entry.data
    $keyCount  = ($data.PSObject.Properties | Measure-Object).Count

    Write-Info "Writing $path ($kvVersion) - $keyCount key(s)"

    $result = Write-Secret -Path $path -KvVersion $kvVersion -Data $data

    if ($result -eq "ok") {
        Write-Ok "  Written: $path"
        $imported++
    } else {
        # Retry as v1 if v2 failed
        if ($kvVersion -eq "v2") {
            Write-Warn "  v2 failed ($result) - retrying as v1 ..."
            $result = Write-Secret -Path $path -KvVersion "v1" -Data $data
            if ($result -eq "ok") {
                Write-Ok "  Written (v1 fallback): $path"
                $imported++
            } else {
                Write-Fail "  Failed: $path ($result)"
                $failedPaths += $path
                $failed++
            }
        } else {
            Write-Fail "  Failed: $path ($result)"
            $failedPaths += $path
            $failed++
        }
    }
}

# Verify first secret
Write-Host ""
Write-Info "Verifying first imported secret ..."
$firstPath = $secretPaths[0]
$firstVer  = $export.secrets.$firstPath.kv_version

if ($firstVer -eq "v2") {
    $verifyPath = $firstPath -replace "^secret/", "secret/data/"
} else {
    $verifyPath = $firstPath
}

try {
    $verify = Invoke-RestMethod `
        -Uri "$BaoAddr/v1/$verifyPath" `
        -Method GET `
        -Headers @{ "X-Vault-Token" = $Token } `
        -ErrorAction Stop
    Write-Ok "Verified: $firstPath is readable"
} catch {
    Write-Warn "Could not verify $firstPath - check manually:"
    Write-Host "  docker exec openbao bao kv get $firstPath" -ForegroundColor Gray
}

# Summary
Write-Host ""
Write-Host "=============================================" -ForegroundColor Cyan
Write-Ok "Import complete!"
Write-Host "  Imported : $imported" -ForegroundColor White
Write-Host "  Failed   : $failed"   -ForegroundColor White

if ($failedPaths.Count -gt 0) {
    Write-Host ""
    Write-Warn "Failed paths - retry manually:"
    $failedPaths | ForEach-Object {
        Write-Host "  docker exec openbao bao kv get $_" -ForegroundColor Yellow
    }
}

Write-Host "=============================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "  Verify all secrets with:" -ForegroundColor Gray
Write-Host "    docker exec openbao bao kv list secret/" -ForegroundColor Gray
