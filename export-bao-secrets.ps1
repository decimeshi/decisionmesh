# export-bao-secrets.ps1
# Exports all OpenBao secrets to a JSON file for import on Mac/Linux.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File .\export-bao-secrets.ps1
#   powershell -ExecutionPolicy Bypass -File .\export-bao-secrets.ps1 -Token "dev-token"
#   powershell -ExecutionPolicy Bypass -File .\export-bao-secrets.ps1 -Token "dev-token" -RootPath "secret/" -Output "secrets.json"

param(
    [string]$BaoAddr  = "http://localhost:8200",
    [string]$Token    = "",
    [string]$RootPath = "secret/",
    [string]$Output   = "bao_secrets_export.json"
)

function Write-Info { param($m) Write-Host "[INFO]  $m" -ForegroundColor Cyan   }
function Write-Ok   { param($m) Write-Host "[OK]    $m" -ForegroundColor Green  }
function Write-Warn { param($m) Write-Host "[WARN]  $m" -ForegroundColor Yellow }
function Write-Fail { param($m) Write-Host "[ERROR] $m" -ForegroundColor Red    }

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

# Test connectivity
Write-Info "Connecting to $BaoAddr ..."

try {
    $health = Invoke-RestMethod -Uri "$BaoAddr/v1/sys/health" -Method GET -ErrorAction Stop
    Write-Ok "OpenBao reachable - version: $($health.version) | sealed: $($health.sealed)"
} catch {
    Write-Fail "Cannot reach $BaoAddr - is OpenBao running?"
    Write-Fail "Start it with: docker start openbao"
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

# REST helper
function Invoke-BaoGet {
    param([string]$Uri)
    try {
        return Invoke-RestMethod `
            -Uri $Uri `
            -Method GET `
            -Headers @{ "X-Vault-Token" = $Token } `
            -ErrorAction Stop
    } catch {
        return $null
    }
}

# Storage
$allSecrets = [ordered]@{}
$totalKeys  = 0
$errors     = @()

# Recursive walker
function Export-Path {
    param([string]$Path)

    # KV v2 list
    $metaPath = $Path -replace "^secret/", "secret/metadata/"
    $listUri  = ("$BaoAddr/v1/$metaPath").TrimEnd("/") + "?list=true"
    $listRes  = Invoke-BaoGet -Uri $listUri

    # Fallback KV v1 list
    if (-not $listRes) {
        $listUri = "$BaoAddr/v1/${Path}?list=true"
        $listRes = Invoke-BaoGet -Uri $listUri
    }

    if (-not $listRes -or -not $listRes.data.keys) {
        Write-Warn "Could not list: $Path (skipping)"
        return
    }

    foreach ($key in $listRes.data.keys) {
        $fullPath = "$Path$key"

        if ($key.EndsWith("/")) {
            Export-Path -Path $fullPath
        } else {
            Write-Info "Reading $fullPath"

            # Try KV v2
            $dataPath = $fullPath -replace "^secret/", "secret/data/"
            $dataUri  = "$BaoAddr/v1/$dataPath"
            $res      = Invoke-BaoGet -Uri $dataUri

            $secretData = $null
            $kvVersion  = "v2"

            if ($res -and $res.data.data) {
                $secretData = $res.data.data
            } else {
                # Fallback KV v1
                $dataUri    = "$BaoAddr/v1/$fullPath"
                $res        = Invoke-BaoGet -Uri $dataUri
                $kvVersion  = "v1"
                if ($res -and $res.data) {
                    $secretData = $res.data
                }
            }

            if ($secretData) {
                $keyCount = ($secretData.PSObject.Properties | Measure-Object).Count
                $script:allSecrets[$fullPath] = [ordered]@{
                    path       = $fullPath
                    kv_version = $kvVersion
                    data       = $secretData
                }
                $script:totalKeys += $keyCount
                Write-Ok "  Exported: $fullPath ($kvVersion) - $keyCount key(s)"
            } else {
                Write-Warn "  Could not read: $fullPath - skipping"
                $script:errors += $fullPath
            }
        }
    }
}

# Run export
Write-Host ""
Write-Info "Starting export from: $RootPath"
Write-Host ""

Export-Path -Path $RootPath

# Write JSON output
$exportDoc = [ordered]@{
    exported_at  = (Get-Date -Format "yyyy-MM-ddTHH:mm:ssZ")
    bao_address  = $BaoAddr
    root_path    = $RootPath
    total_paths  = $allSecrets.Count
    total_keys   = $totalKeys
    secrets      = $allSecrets
}

$exportDoc | ConvertTo-Json -Depth 20 | Out-File -FilePath $Output -Encoding UTF8

# Summary
Write-Host ""
Write-Host "=============================================" -ForegroundColor Cyan
Write-Ok "Export complete!"
Write-Host "  Paths exported : $($allSecrets.Count)" -ForegroundColor White
Write-Host "  Total keys     : $totalKeys"           -ForegroundColor White
Write-Host "  Output file    : $Output"              -ForegroundColor White

if ($errors.Count -gt 0) {
    Write-Host ""
    Write-Warn "Failed to read $($errors.Count) path(s):"
    $errors | ForEach-Object { Write-Host "    - $_" -ForegroundColor Yellow }
}

Write-Host "=============================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "  Copy the file to Mac/Linux, then run:" -ForegroundColor Gray
Write-Host "    chmod +x import-bao-secrets.sh"      -ForegroundColor Gray
Write-Host "    ./import-bao-secrets.sh"             -ForegroundColor Gray
Write-Host ""
Write-Warn "WARNING: Plaintext secrets - do NOT commit to git."
Write-Host '  Run: echo bao_secrets_export.json >> .gitignore' -ForegroundColor Gray
