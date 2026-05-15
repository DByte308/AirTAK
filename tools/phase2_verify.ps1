# AirTAK Phase 2 verification helper.
# Runs the automated mesh-stack checks and optionally uploads to a connected T-Echo Plus.

param(
    [switch]$Upload = $false,
    [string]$Port = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$PioWrapper = Join-Path $PSScriptRoot "pio_firmware.ps1"
$FlashScript = Join-Path $PSScriptRoot "flash.ps1"

Push-Location $ProjectRoot
try {
    Write-Host "[phase2] Running native unit tests..." -ForegroundColor Cyan
    & $PioWrapper test -e native
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    Write-Host "`n[phase2] Building T-Echo Plus firmware..." -ForegroundColor Cyan
    & $PioWrapper run -e t_echo_plus
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    if ($Upload) {
        Write-Host "`n[phase2] Uploading firmware..." -ForegroundColor Cyan
        $flashArgs = @()
        if ($Port -ne "") { $flashArgs += @("-Port", $Port) }
        & $FlashScript @flashArgs
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }

    Write-Host "`n[phase2] Automated checks passed." -ForegroundColor Green
    if (-not $Upload) {
        Write-Host "[phase2] Hardware mesh/interop field checks still require physical devices." -ForegroundColor Yellow
    }
}
finally {
    Pop-Location
}