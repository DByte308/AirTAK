#!/usr/bin/env python3
"""Self-test for tools/check_16kb_alignment.py.

A CI gate that cannot fail is worse than no gate: it reads as coverage while letting
anything through. This builds synthetic APKs in memory — a real ZIP, real ELF program
headers — and asserts the checker passes the good ones and fails the bad ones.

Run: python3 tools/test_check_16kb_alignment.py
"""

from __future__ import annotations

import importlib.util
import io
import struct
import sys
import tempfile
import zipfile
import zlib
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("check16", HERE / "check_16kb_alignment.py")
check16 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check16)

ELF_MAGIC = b"\x7fELF"


def elf64(load_align: int, machine: int = 0xB7) -> bytes:
    """A minimal but structurally real ELF64 shared object with three PT_LOAD segments."""
    e_ident = ELF_MAGIC + bytes([2, 1, 1, 0]) + b"\0" * 8  # 64-bit, little endian
    phentsize = 56
    phnum = 3
    e_phoff = 64
    header = e_ident + struct.pack(
        "<HHIQQQIHHHHHH",
        3,             # e_type = ET_DYN
        machine,       # e_machine
        1,             # e_version
        0,             # e_entry
        e_phoff,       # e_phoff
        0,             # e_shoff
        0,             # e_flags
        64,            # e_ehsize
        phentsize,
        phnum,
        64,            # e_shentsize
        0,             # e_shnum
        0,             # e_shstrndx
    )
    assert len(header) == 64, len(header)

    def phoff(align: int) -> bytes:
        return struct.pack(
            "<IIQQQQQQ",
            1,     # PT_LOAD
            5,     # PF_R | PF_X
            0,     # p_offset
            0,     # p_vaddr
            0,     # p_paddr
            0x100,  # p_filesz
            0x100,  # p_memsz
            align,  # p_align
        )

    body = phoff(load_align) + phoff(load_align) + phoff(load_align)
    return header + body + b"\0" * 64


def apk_with(entries: dict[str, tuple[int, bool]], align: int = check16.PAGE_16K) -> bytes:
    """Build an APK-shaped zip: entries map 'lib/<abi>/<name>.so' -> (align, stored).

    Stored entries are page-aligned the way AGP/ZIPALIGN aligns them, so a fixture that is
    meant to exercise the ELF dimension does not also trip the ZIP dimension. Python's own
    zipfile writer packs local headers back to back — if the first stored payload is not
    itself a multiple of the page size, every later entry is misaligned for an unrelated
    reason, which would hide exactly the distinction this tool exists to draw.

    Padding goes into the entry's extra field, as zipalign does, because that is the only
    byte range a zip reader is guaranteed to skip. It is applied iteratively because the
    extra-field length is part of the offset the next entry inherits.
    """
    pad = {name: 0 for name in entries}
    for _ in range(24):
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as zf:
            for name, (elf_align, stored) in entries.items():
                info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_STORED if stored else zipfile.ZIP_DEFLATED
                info.extra = b"\0" * pad[name]
                zf.writestr(info, elf64(elf_align))
        raw = buf.getvalue()
        changed = False
        buf.seek(0)
        with zipfile.ZipFile(buf) as zf:
            for info in zf.infolist():
                if info.compress_type != zipfile.ZIP_STORED:
                    continue
                offset = info.header_offset + 30 + len(info.filename.encode()) + len(info.extra)
                need = (-offset) % align
                if need:
                    pad[info.filename] += need
                    changed = True
        if not changed:
            return raw
    raise AssertionError(f"alignment padding did not converge: {pad}")


def run(entries: dict[str, tuple[int, bool]], path: Path):
    path.write_bytes(apk_with(entries))
    return check16.check(path)


def main() -> int:
    failures: list[str] = []

    def expect(cond: bool, msg: str) -> None:
        if not cond:
            failures.append(msg)

    with tempfile.TemporaryDirectory() as td:
        apk = Path(td) / "app.apk"

        # 1. Everything 16 KB aligned and stored: clean.
        errors, notes, rows = run(
            {
                "lib/arm64-v8a/libmaplibre.so": (0x4000, True),
                "lib/x86_64/libmaplibre.so": (0x4000, True),
            },
            apk,
        )
        expect(len(rows) == 2, f"expected 2 libraries, got {len(rows)}")
        expect(errors == [], f"clean APK produced errors: {errors}")

        # 2. The actual bug: MapLibre 11.0.0 shipped 0x1000. Must fail, on both strict ABIs.
        errors, notes, rows = run(
            {
                "lib/arm64-v8a/libmaplibre.so": (0x1000, True),
                "lib/x86_64/libmaplibre.so": (0x1000, True),
            },
            apk,
        )
        expect(len(errors) == 2, f"4 KB aligned libs must produce 2 errors, got {len(errors)}: {errors}")
        expect(
            any("org.maplibre.gl:android-sdk" in e for e in errors),
            f"failure must name the artifact so it can be bumped: {errors}",
        )
        expect(
            all("0x1000" in e for e in errors),
            f"failure must report the alignment actually measured: {errors}",
        )

        # 3. Mixed: one good, one bad. Only the bad one may be reported.
        errors, notes, rows = run(
            {
                "lib/arm64-v8a/libandroidx.graphics.path.so": (0x4000, True),
                "lib/arm64-v8a/libmaplibre.so": (0x1000, True),
            },
            apk,
        )
        expect(len(errors) == 1, f"expected exactly 1 error, got {errors}")
        expect(
            errors and "libmaplibre.so" in errors[0] and "graphics.path" not in errors[0],
            f"the clean library must not be blamed: {errors}",
        )

        # 4. 32-bit ABIs are informational, not failures: upstream ships them 4 KB and
        #    Google's requirement does not cover them. AirTAK does not even package them.
        errors, notes, rows = run({"lib/armeabi-v7a/libmaplibre.so": (0x1000, True)}, apk)
        expect(errors == [], f"armeabi-v7a must not fail the gate: {errors}")
        expect(
            any("armeabi-v7a" in n and "PT_LOAD" in n for n in notes),
            f"a 4 KB 32-bit library should still be reported as a note, got {notes}",
        )

        # 5. In-archive alignment is a separate property from ELF alignment. AGP page-aligns
        #    stored entries; a hand-assembled or re-zipped APK can lose that while keeping
        #    PT_LOAD correct, and only the ZIP check catches it.
        # Build the misaligned archive by hand: two stored entries packed back to back,
        # which is what a naive re-zip of an APK produces.
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as zf:
            zf.writestr(zipfile.ZipInfo("lib/arm64-v8a/libmaplibre.so"), elf64(0x4000))
            zf.writestr(zipfile.ZipInfo("lib/arm64-v8a/libsecond.so"), elf64(0x4000))
        apk.write_bytes(buf.getvalue())
        errors, notes, rows = check16.check(apk)
        misaligned = [e for e in errors if "data starts at offset" in e]
        expect(
            len(misaligned) >= 1,
            "a stored entry whose payload is off the 16 KB boundary must fail the ZIP check; "
            f"got errors={errors} notes={notes}",
        )

        # 6. Compressed entries are extracted at install, so in-archive alignment is not a
        #    failure — but it must be noted, because it changes what the loader does.
        errors, notes, rows = run({"lib/arm64-v8a/libmaplibre.so": (0x4000, False)}, apk)
        expect(errors == [], f"compressed storage must not fail the gate: {errors}")
        expect(
            any("compressed" in n for n in notes),
            f"compressed storage must be noted, got {notes}",
        )

        # 7. A non-ELF payload must not silently pass.
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as zf:
            zf.writestr("lib/arm64-v8a/libjunk.so", b"not an elf at all")
        apk.write_bytes(buf.getvalue())
        errors, notes, rows = check16.check(apk)
        expect(len(errors) == 1, f"unparseable ELF must fail, got {errors}")

        # 8. CLI contract: a missing APK is an error in CI mode, never a silent pass.
        rc = check16.main([str(Path(td) / "nope.apk"), "--expect-clean"])
        expect(rc != 0, "missing APK must not exit 0 under --expect-clean")

    if failures:
        for f in failures:
            print(f"FAIL: {f}")
        print(f"\n{len(failures)} self-test(s) failed")
        return 1
    print("check_16kb_alignment self-test: all cases passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
