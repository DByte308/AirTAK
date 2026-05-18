# AirTAK firmware development environment setup.
# Creates firmware/.venv and installs the pinned PlatformIO toolchain wrapper.

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$FirmwareDir = Join-Path $ProjectRoot "firmware"
$VenvDir = Join-Path $FirmwareDir ".venv"
$Requirements = Join-Path $FirmwareDir "requirements-dev.txt"

if (-not (Test-Path $VenvDir)) {
    python -m venv $VenvDir
}

$Python = Join-Path $VenvDir "Scripts\python.exe"
$ScriptsDir = Join-Path $VenvDir "Scripts"
& $Python -m pip install --upgrade pip
& $Python -m pip install -r $Requirements

$WrapperDir = Join-Path $VenvDir "Scripts"
@"
@echo off
"%~dp0python.exe" -m ziglang cc %*
"@ | Set-Content -Path (Join-Path $WrapperDir "gcc.cmd") -Encoding ASCII

@"
@echo off
"%~dp0python.exe" -m ziglang c++ %*
"@ | Set-Content -Path (Join-Path $WrapperDir "g++.cmd") -Encoding ASCII

@"
@echo off
"%~dp0python.exe" -m ziglang ar %*
"@ | Set-Content -Path (Join-Path $WrapperDir "ar.cmd") -Encoding ASCII

Write-Host "Firmware environment ready." -ForegroundColor Green
Write-Host "Use: .\tools\pio_firmware.ps1 run -e t_echo_plus"
Write-Host "Use: .\tools\pio_firmware.ps1 run -e t_echo -t upload  (USB DFU via adafruit-nrfutil)"
Write-Host "Use: .\tools\pio_firmware.ps1 test -e native"