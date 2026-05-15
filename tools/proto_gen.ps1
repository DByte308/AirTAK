# MASH — Mesh Airsoft Situational Hub
# tools/proto_gen.ps1 — Generate protobuf bindings from proto/ sources
# Planning: §18.3 | Roadmap: T.4
#
# Generates:
#   1. nanopb C/C++ bindings → firmware/src/proto/
#      Sources: proto/mash/*.proto (NOT proto/meshtastic/ — those are vendored, do not regenerate)
#   2. Kotlin lite bindings → android/app/src/main/proto/ (copied for Gradle protobuf plugin)
#
# Requirements:
#   - protoc in PATH (https://github.com/protocolbuffers/protobuf/releases)
#   - nanopb generator: pip install nanopb  (or use nanopb from lib/nanopb/)
#
# CRITICAL: proto/meshtastic/ protos are VENDORED at the commit pinned in
# proto/meshtastic/VERSION. Do NOT regenerate them — edit manually if needed.

param(
    [switch]$FirmwareOnly = $false,
    [switch]$AndroidOnly  = $false
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ProjectRoot   = Split-Path -Parent $PSScriptRoot
$ProtoMash     = Join-Path $ProjectRoot "proto\mash"
$FirmwareProto = Join-Path $ProjectRoot "firmware\src\proto"
$AndroidProto  = Join-Path $ProjectRoot "android\app\src\main\proto\com\mash\proto"

# Verify protoc is available
if (-not (Get-Command protoc -ErrorAction SilentlyContinue)) {
    Write-Error "protoc not found in PATH. Download from https://github.com/protocolbuffers/protobuf/releases"
    exit 1
}

$ProtoFiles = Get-ChildItem -Path $ProtoMash -Filter "*.proto" | Select-Object -ExpandProperty FullName

if ($ProtoFiles.Count -eq 0) {
    Write-Warning "No .proto files found in $ProtoMash"
    exit 0
}

# ── Firmware nanopb generation ────────────────────────────────────────────────
if (-not $AndroidOnly) {
    Write-Host "`n[proto_gen.ps1] Generating nanopb C bindings → $FirmwareProto" -ForegroundColor Cyan

    # Find nanopb generator (prefer local lib, fall back to PATH)
    $nanopbGen = Join-Path $ProjectRoot "firmware\lib\nanopb\generator\nanopb_generator.py"
    if (-not (Test-Path $nanopbGen)) {
        $nanopbGen = "nanopb_generator.py"  # fall back to PATH
    }

    New-Item -ItemType Directory -Force -Path $FirmwareProto | Out-Null

    foreach ($proto in $ProtoFiles) {
        Write-Host "  Generating nanopb for: $(Split-Path -Leaf $proto)"
        & python $nanopbGen `
            --proto-path $ProtoMash `
            --proto-path (Join-Path $ProjectRoot "proto") `
            --output-dir $FirmwareProto `
            $proto
        if ($LASTEXITCODE -ne 0) {
            Write-Error "nanopb generation failed for $proto"
            exit $LASTEXITCODE
        }
    }
}

# ── Android Kotlin/Java protobuf generation ───────────────────────────────────
if (-not $FirmwareOnly) {
    Write-Host "`n[proto_gen.ps1] Copying proto sources for Android Gradle protobuf plugin → $AndroidProto" -ForegroundColor Cyan

    # The Android Gradle protobuf plugin (protobuf-gradle-plugin) generates Kotlin from
    # proto sources placed under src/main/proto/. We copy the MASH protos there.
    # The plugin handles compilation; we do NOT run protoc manually for Android.
    New-Item -ItemType Directory -Force -Path $AndroidProto | Out-Null

    foreach ($proto in $ProtoFiles) {
        $dest = Join-Path $AndroidProto (Split-Path -Leaf $proto)
        Copy-Item -Path $proto -Destination $dest -Force
        Write-Host "  Copied: $(Split-Path -Leaf $proto)"
    }

    Write-Host "  Android proto sources ready. Run './gradlew :app:generateDebugProto' to compile."
}

Write-Host "`n[proto_gen.ps1] Done." -ForegroundColor Green
