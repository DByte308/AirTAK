# MASH — Mesh Airsoft Situational Hub
# tools/flash.ps1 — Flash firmware to T-Echo Plus via PlatformIO
# Planning: §18.1 | Roadmap: T.5
#
# Usage: .\tools\flash.ps1 [-Port COM3] [-Clean]
#   -Port  : (optional) Serial port. If omitted, PlatformIO auto-detects.
#   -Clean : (optional) Run clean before build.
#
# Requires: PlatformIO Core (pio) in PATH.

param(
    [string]$Port  = "",
    [switch]$Clean = $false
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$FirmwareDir = Join-Path $ProjectRoot "firmware"

Push-Location $FirmwareDir
try {
    # Verify pio is available
    if (-not (Get-Command pio -ErrorAction SilentlyContinue)) {
        Write-Error "PlatformIO CLI (pio) not found in PATH. Install from https://docs.platformio.org/en/latest/core/installation/index.html"
        exit 1
    }

    # Check that a T-Echo Plus is connected
    $devices = & pio device list 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Warning "Could not list connected devices. Proceeding anyway."
    } else {
        Write-Host "Connected devices:`n$devices"
    }

    if ($Clean) {
        Write-Host "`n[flash.ps1] Cleaning previous build..." -ForegroundColor Cyan
        & pio run -e t_echo_plus -t clean
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }

    Write-Host "`n[flash.ps1] Building and uploading to T-Echo Plus..." -ForegroundColor Cyan

    $uploadArgs = @("run", "-e", "t_echo_plus", "-t", "upload")
    if ($Port -ne "") {
        $uploadArgs += "--upload-port"
        $uploadArgs += $Port
    }

    & pio @uploadArgs
    if ($LASTEXITCODE -ne 0) {
        Write-Error "[flash.ps1] Upload failed (exit code $LASTEXITCODE). Check USB connection and device bootloader mode."
        exit $LASTEXITCODE
    }

    Write-Host "`n[flash.ps1] Upload complete." -ForegroundColor Green
}
finally {
    Pop-Location
}
