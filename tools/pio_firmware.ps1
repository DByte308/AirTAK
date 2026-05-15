# Runs the pinned firmware PlatformIO installation with its venv-local tools on PATH.

param(
    [Parameter(Position = 0)]
    [string]$Command,

    [Alias("e")]
    [string]$Environment,

    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$ExtraArgs
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$FirmwareDir = Join-Path $ProjectRoot "firmware"
$VenvScripts = Join-Path $FirmwareDir ".venv\Scripts"
$Platformio = Join-Path $VenvScripts "platformio.exe"

if (-not (Test-Path $Platformio)) {
    Write-Error "PlatformIO venv is missing. Run .\tools\setup_firmware_env.ps1 first."
    exit 1
}

$env:PATH = "$VenvScripts;$env:PATH"
$PlatformioArgs = @()
if ($Command) { $PlatformioArgs += $Command }
if ($Environment) { $PlatformioArgs += @("-e", $Environment) }
if ($ExtraArgs) { $PlatformioArgs += $ExtraArgs }

Push-Location $FirmwareDir
try {
    & $Platformio @PlatformioArgs
    exit $LASTEXITCODE
} finally {
    Pop-Location
}