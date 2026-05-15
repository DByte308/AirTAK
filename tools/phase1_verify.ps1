# AirTAK Phase 1 verification helper.
# Runs the automated Phase 1 checks and optionally uploads to a connected T-Echo Plus.

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
    Write-Host "[phase1] Running native unit tests..." -ForegroundColor Cyan
    & $PioWrapper test -e native
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    Write-Host "`n[phase1] Building T-Echo Plus firmware..." -ForegroundColor Cyan
    & $PioWrapper run -e t_echo_plus
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    if ($Upload) {
        Write-Host "`n[phase1] Uploading firmware..." -ForegroundColor Cyan
        $flashArgs = @()
        if ($Port -ne "") { $flashArgs += @("-Port", $Port) }
        & $FlashScript @flashArgs
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }

    Write-Host "`n[phase1] Automated checks passed." -ForegroundColor Green
    if (-not $Upload) {
        Write-Host "[phase1] Hardware smoke checks remain in docs/phase1-acceptance.md." -ForegroundColor Yellow
    }
}
finally {
    Pop-Location
}