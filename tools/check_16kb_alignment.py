#!/usr/bin/env python3
"""16 KB page-size compatibility check for a built APK (issue #14).

Android 15 and some Android 16 devices run with a 16 KB memory page size. A native library
whose ELF `PT_LOAD` segments are aligned below 16 KB cannot be mapped there, and a library
that is mapped directly out of the APK also needs its *data* to start on a 16 KB boundary
inside the archive. Those are two independent properties, so this checks them separately
and reports them separately:

  ELF alignment   — `p_align` of every `PT_LOAD` in the shared object.
  ZIP alignment   — the offset of the entry's data inside the APK, for entries stored
                    uncompressed (the only ones the loader maps in place).

Play's warning is not a reliable signal and is not used here. It has previously named
`libandroidx.graphics.path.so`, which measured clean at 0x4000, while the real offender
was `libmaplibre.so` at 0x1000. Measuring the artifact is what settles it.

Exit code 0 = every shipped library passes both checks. Non-zero = at least one fails,
and the message names the library, the ABI, the artifact it came from and the alignment
actually measured.

Usage:
    tools/check_16kb_alignment.py path/to/app.apk
    tools/check_16kb_alignment.py path/to/app.apk --expect-clean   # same, stricter output
    tools/check_16kb_alignment.py --report                          # print a table only

The `--expect-clean` form is what CI runs. Without it the tool reports and exits 0 for a
missing APK, which is what you want locally and not what you want in a pipeline.
"""

from __future__ import annotations

import argparse
import struct
import sys
import zipfile
from pathlib import Path

PAGE_16K = 16 * 1024

# ABIs that actually run 16 KB page kernels today. 32-bit ABIs are listed for completeness
# but are reported as informational: upstream ships them 4 KB aligned and Google's
# requirement does not cover them.
STRICT_ABIS = {"arm64-v8a", "x86_64", "arm64-v8a_32", "riscv64"}
INFO_ABIS = {"armeabi-v7a", "x86"}

# Which artifact each library name comes from, so a failure names something you can bump.
# Keyed by library file name; extend as dependencies change.
PROVENANCE = {
    "libmaplibre.so": "org.maplibre.gl:android-sdk",
    "libandroidx.graphics.path.so": "androidx.graphics:graphics-path",
    "libsqlite3.so": "androidx.sqlite:sqlite (androidx.sqlite.sqliteVersion)",
    "libmmkv.so": "com.tencent:mmkv",
    "libdatastore_shared_counter.so": "androidx.datastore:datastore-core",
}

PT_LOAD = 1
SHT_NOBITS = 8  # not used here; program headers only


def _elf_machine(data: bytes) -> str:
    if len(data) < 20:
        return "?"
    e_machine = struct.unpack_from("<H", data, 18)[0]
    return {0x3E: "x86-64", 0xB7: "AArch64", 0x28: "ARM", 0x3E7: "RISC-V"}.get(e_machine, f"0x{e_machine:x}")


def elf_load_alignments(data: bytes) -> list[int]:
    """Return p_align for every PT_LOAD, or [] if this is not a parseable ELF."""
    if len(data) < 64 or data[:4] != b"\x7fELF":
        return []
    is64 = data[4] == 2
    little = data[5] == 1
    end = "<" if little else ">"
    if is64:
        e_phoff = struct.unpack_from(end + "Q", data, 0x20)[0]
        e_phentsize = struct.unpack_from(end + "H", data, 0x36)[0]
        e_phnum = struct.unpack_from(end + "H", data, 0x38)[0]
    else:
        e_phoff = struct.unpack_from(end + "I", data, 0x1C)[0]
        e_phentsize = struct.unpack_from(end + "H", data, 0x2A)[0]
        e_phnum = struct.unpack_from(end + "H", data, 0x2C)[0]
    if e_phentsize == 0 or e_phnum == 0:
        return []

    aligns: list[int] = []
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        if off + e_phentsize > len(data):
            break
        p_type = struct.unpack_from(end + "I", data, off)[0]
        if p_type != PT_LOAD:
            continue
        if is64:
            # p_align is the last field of the 56-byte 64-bit program header.
            align = struct.unpack_from(end + "Q", data, off + 48)[0]
        else:
            align = struct.unpack_from(end + "I", data, off + 28)[0]
        aligns.append(int(align))
    return aligns


def lib_entries(zf: zipfile.ZipFile):
    for info in zf.infolist():
        name = info.filename
        if not name.startswith("lib/") or not name.endswith(".so"):
            continue
        parts = name.split("/")
        abi = parts[1] if len(parts) >= 3 else "?"
        yield abi, name, info


def real_data_offset(raw: bytes, header_offset: int) -> int:
    """Parse a local file header and return where the payload starts."""
    if raw[header_offset : header_offset + 4] != b"PK\x03\x04":
        return -1
    name_len = struct.unpack_from("<H", raw, header_offset + 26)[0]
    extra_len = struct.unpack_from("<H", raw, header_offset + 28)[0]
    return header_offset + 30 + name_len + extra_len


def check(apk_path: Path) -> tuple[list[str], list[str], list[dict]]:
    """Return (errors, notes, rows)."""
    errors: list[str] = []
    notes: list[str] = []
    rows: list[dict] = []

    raw = apk_path.read_bytes()
    with zipfile.ZipFile(apk_path) as zf:
        libs = list(lib_entries(zf))
        if not libs:
            notes.append("no lib/**/*.so entries found in the APK")

        for abi, name, info in sorted(libs, key=lambda t: (t[0], t[1])):
            data = zf.read(info)
            aligns = elf_load_alignments(data)
            stored = info.compress_type == zipfile.ZIP_STORED
            payload = real_data_offset(raw, info.header_offset)
            payload_off = payload if stored else None

            row = {
                "abi": abi,
                "lib": name.rsplit("/", 1)[-1],
                "artifact": PROVENANCE.get(name.rsplit("/", 1)[-1], "unknown artifact"),
                "machine": _elf_machine(data),
                "elf_align": min(aligns) if aligns else None,
                "stored": stored,
                "data_offset": payload_off,
            }
            rows.append(row)

            strict = abi in STRICT_ABIS
            label = f"{abi}/{row['lib']}"

            if not aligns:
                (errors if strict else notes).append(
                    f"{label}: not a parseable ELF, cannot confirm PT_LOAD alignment"
                )
                continue

            worst = min(aligns)
            if worst < PAGE_16K:
                msg = (
                    f"{label}: PT_LOAD alignment is 0x{worst:x} ({worst} B), below "
                    f"{PAGE_16K} B — from {row['artifact']}"
                )
                (errors if strict else notes).append(msg)

            if not stored:
                # Compressed entries are extracted at install, so in-archive alignment does
                # not apply; but then the app pays extraction cost and the APK is larger.
                notes.append(f"{label}: stored compressed, so it is extracted rather than mapped")
            else:
                if payload < 0:
                    errors.append(f"{label}: could not locate the local file header")
                elif payload % PAGE_16K != 0:
                    msg = (
                        f"{label}: uncompressed data starts at offset {payload} "
                        f"(0x{payload:x}), not a multiple of {PAGE_16K} — "
                        f"remainder {payload % PAGE_16K}"
                    )
                    (errors if strict else notes).append(msg)

    return errors, notes, rows


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description="16 KB page-size alignment check for an APK")
    ap.add_argument("apk", nargs="?", help="path to the APK under test")
    ap.add_argument("--report", action="store_true", help="print the table and exit 0")
    ap.add_argument(
        "--expect-clean",
        action="store_true",
        help="CI mode: a missing APK or any strict-ABI failure is an error",
    )
    args = ap.parse_args(argv)

    if not args.apk:
        print("no APK path given", file=sys.stderr)
        return 2 if args.expect_clean else 0
    apk_path = Path(args.apk)
    if not apk_path.is_file():
        # A missing artifact is a CI failure, never a pass.
        print(f"ERROR: {apk_path} does not exist", file=sys.stderr)
        return 2 if args.expect_clean else 0

    errors, notes, rows = check(apk_path)

    if rows:
        print(f"{'ABI':<12} {'library':<32} {'ELF align':>10} {'stored':>7} {'data offset':>14}  artifact")
        for r in rows:
            align = f"0x{r['elf_align']:x}" if r["elf_align"] else "?"
            off = f"{r['data_offset']:#x}" if r["data_offset"] is not None else "n/a"
            print(
                f"{r['abi']:<12} {r['lib']:<32} {align:>10} "
                f"{str(r['stored']):>7} {off:>14}  {r['artifact']}"
            )
    for n in notes:
        print(f"note: {n}")
    for e in errors:
        print(f"FAIL: {e}", file=sys.stderr)

    if errors:
        print(
            f"\n{len(errors)} library/libraries below the 16 KB requirement on a strict ABI. "
            "Bump the named artifact to a release whose native libraries are 16 KB aligned, "
            "or rebuild them with -Wl,-z,max-page-size=16384. Do not suppress this check.",
            file=sys.stderr,
        )
        return 1
    print(f"\nOK: {len(rows)} native libraries, all strict-ABI ones 16 KB aligned in ELF and in-archive")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
