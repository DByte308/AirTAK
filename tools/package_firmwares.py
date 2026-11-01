#!/usr/bin/env python3
"""Build and publish the seven production board targets to Firmwares/ locally.

Default: build all boards, then validate all outputs before publishing.
--collect-only: package already-built outputs (caller must have just built them).
--verify: verify the published manifest/checksums without building or changing files.
"""
import argparse
import hashlib
import json
import pathlib
import shutil
import subprocess
import tempfile
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
BOARDS = {
    "heltec_v3": ("Heltec-V3", "esp32"),
    "heltec_v4": ("Heltec-V4", "esp32"),
    "heltec_t114": ("Heltec-T114", "nrf52"),
    "t3s3": ("LilyGO-T3S3", "esp32"),
    "t_deck": ("LilyGO-T-Deck", "esp32"),
    "t_echo": ("LilyGO-T-Echo", "nrf52"),
    "t_echo_plus": ("LilyGO-T-Echo-Plus", "nrf52"),
}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def firmware_source():
    paths = subprocess.check_output(
        ["git", "ls-files", "-z", "firmware"], cwd=ROOT,
    ).decode().split("\0")
    hasher = hashlib.sha256()
    for name in sorted(filter(None, paths)):
        hasher.update(name.encode() + b"\0" + (ROOT / name).read_bytes() + b"\0")
    return hasher.hexdigest()


def validate_output(directory, family):
    if family == "esp32":
        application = (directory / "firmware.bin").read_bytes()
        factory = (directory / "firmware.factory.bin").read_bytes()
        if not application or application[0] != 0xE9 or factory[0] != 0xE9:
            raise ValueError(f"invalid ESP32 image in {directory}")
        if factory[0x10000:] != application:
            raise ValueError(f"factory/app mismatch in {directory}")
        for name, offset in (("bootloader.bin", 0), ("partitions.bin", 0x8000)):
            part = (directory / name).read_bytes()
            if not part or factory[offset:offset + len(part)] != part:
                raise ValueError(f"factory/{name} mismatch in {directory}")
        return ["firmware.bin", "firmware.factory.bin"]
    with zipfile.ZipFile(directory / "firmware.zip") as archive:
        if archive.testzip() is not None:
            raise ValueError(f"corrupt DFU ZIP in {directory}")
        application = json.loads(archive.read("manifest.json"))["manifest"]["application"]
        for field in ("bin_file", "dat_file"):
            if not archive.read(application[field]):
                raise ValueError(f"empty DFU {field} in {directory}")
    return ["firmware.zip"]


def verify(destination):
    manifest = json.loads((destination / "manifest.json").read_text())
    expected_environments = set(BOARDS)
    if set(manifest["boards"]) != expected_environments:
        raise ValueError("manifest board coverage mismatch")
    actual_paths = set()
    for env, entry in manifest["boards"].items():
        folder, family = BOARDS[env]
        expected_files = {"firmware.bin", "firmware.factory.bin"} if family == "esp32" else {"firmware.zip"}
        if entry["directory"] != folder or {pathlib.Path(f["path"]).name for f in entry["files"]} != expected_files:
            raise ValueError(f"unexpected artifact layout for {env}")
        for artifact in entry["files"]:
            name = artifact["path"]
            if pathlib.PurePosixPath(name).parent != pathlib.PurePosixPath(folder):
                raise ValueError(f"unexpected artifact path: {name}")
            data = (destination / name).read_bytes()
            if len(data) != artifact["bytes"] or digest(data) != artifact["sha256"]:
                raise ValueError(f"artifact checksum mismatch: {name}")
            actual_paths.add(name)
    sums = (destination / "SHA256SUMS").read_text().splitlines()
    expected_sums = sorted(f"{f['sha256']}  {f['path']}" for b in manifest["boards"].values() for f in b["files"])
    if sorted(sums) != expected_sums:
        raise ValueError("SHA256SUMS does not match manifest")
    published = {str(p.relative_to(destination)) for p in destination.rglob("*") if p.suffix in (".bin", ".zip", ".hex", ".uf2")}
    if published != actual_paths:
        raise ValueError("unexpected or missing firmware artifact")
    print(f"Verified {len(actual_paths)} artifacts for {len(BOARDS)} boards")


def collect(destination):
    # Validate every board before replacing any published artifact.
    files = {env: validate_output(ROOT / "firmware/.pio/build" / env, family)
             for env, (_, family) in BOARDS.items()}
    revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT).decode().strip()
    if subprocess.check_output(["git", "status", "--porcelain", "--", "firmware"], cwd=ROOT).strip():
        raise ValueError("commit firmware source/config changes before packaging for traceable binaries")
    manifest = {
        "source_commit": revision,
        "firmware_source_sha256": firmware_source(),
        "build_tool": subprocess.check_output([str(ROOT / "firmware/.venv/bin/platformio"), "--version"]).decode().strip(),
        "verification": "Local build and artifact checks only; not a seven-board hardware test",
        "boards": {},
    }
    with tempfile.TemporaryDirectory(prefix="airtak-firmwares-") as temporary:
        staging = pathlib.Path(temporary)
        for env, (folder, family) in BOARDS.items():
            board_dir = staging / folder
            board_dir.mkdir()
            artifacts = []
            for name in files[env]:
                source = ROOT / "firmware/.pio/build" / env / name
                target = board_dir / name
                shutil.copyfile(source, target)
                data = target.read_bytes()
                artifacts.append({"path": f"{folder}/{name}", "bytes": len(data), "sha256": digest(data)})
            manifest["boards"][env] = {"directory": folder, "platform": family, "files": artifacts}
        (staging / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
        sums = sorted(f"{f['sha256']}  {f['path']}" for b in manifest["boards"].values() for f in b["files"])
        (staging / "SHA256SUMS").write_text("\n".join(sums) + "\n")
        verify(staging)
        destination.mkdir(exist_ok=True)
        for folder, _ in BOARDS.values():
            target = destination / folder
            if target.exists():
                shutil.rmtree(target)
            shutil.copytree(staging / folder, target)
        for name in ("manifest.json", "SHA256SUMS"):
            shutil.copyfile(staging / name, destination / name)
    verify(destination)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--collect-only", action="store_true")
    group.add_argument("--verify", action="store_true")
    args = parser.parse_args()
    destination = ROOT / "Firmwares"
    if args.verify:
        verify(destination)
        return
    if not args.collect_only:
        for env in BOARDS:
            subprocess.run([str(ROOT / "tools/pio_firmware.sh"), "run", "-e", env, "-j", "4"], cwd=ROOT, check=True)
    collect(destination)


if __name__ == "__main__":
    main()
