#!/usr/bin/env python3
"""Standalone, stdlib-only guard for the Arduino-ESP32 BLE-memory fix (issue: _bleLibraryInUse).

Root cause this guards: Arduino-ESP32 core 3.x `initArduino()` (esp32-hal-misc.c) releases the BLE
controller's memory (`btMemRelease(BT_MODE_BLE)`) unless `bleInUse()` is true. `bleInUse()` returns
`_bleLibraryInUse`, which is set true ONLY by a pre-main `__attribute__((constructor))` that lives in
`esp32-hal-alloc-ble-mem.h` (NimBLE 1.4 does not include that header, so the flag stays false and the
BLE memory is freed before `esp_bt_controller_init/enable`, which then fails on IDF 5.5.5).

This tool verifies the FIX actually landed in a built firmware image:
  1. the `_bleLibraryInUse` marker symbol exists (the flag is linked);
  2. the `_setBleLibraryInUse` constructor symbol exists (the ctor is not wiped by --gc-sections);
  3. the constructor's code address is present in `.init_array` (so it is retained as a real ctor,
     not merely a dead code symbol).

It reads an ELF32 little-endian (Tensilica Xtensa / ESP32-S3) object directly: ELF header, section
header table, and symbol table, all via the `struct` module. No `readelf`, no toolchain.

Exit code: 0 = pass (all three present), 1 = any requirement failed (clear message on stderr).

Usage:
    python3 check_ble_memory.py path/to/firmware.elf
    python3 check_ble_memory.py --quiet path/to/firmware.elf   # suppress pass message
"""

import os
import struct
import sys

ELF_MAGIC = b"\x7fELF"

SYT_STRTAB = 3    # SHT_STRTAB
SYT_SYMTAB = 2    # SHT_SYMTAB
SYT_NOBITS = 8
SYM_OBJECT = 1
SYM_FUNC = 2

SHN_UNDEF = 0
MIN_SH_ENTSIZE = 40      # ELF32 section header size
MIN_SYM_ENTSIZE = 16     # ELF32 symbol entry size
PTR_SIZE = 4             # ELF32 pointer size

MARKER = "_bleLibraryInUse"
CTOR = "_setBleLibraryInUse"
IA_START = "__init_array_start"
IA_END = "__init_array_end"

PTR32 = struct.Struct("<I")


class BLEGuardError(Exception):
    """Raised on a hard parse failure (not an ELF, malformed, truncated)."""


def parse_header(data):
    """Validate ELF32 little-endian; return a dict of header fields."""
    if len(data) < 4 or data[:4] != ELF_MAGIC:
        raise BLEGuardError("not an ELF file (bad magic)")
    if len(data) < 52:
        raise BLEGuardError("truncated ELF header")
    cls = data[4]
    if cls != 1:
        raise BLEGuardError("not ELF32 (EI_CLASS=%d)" % cls)
    if data[5] != 1:
        raise BLEGuardError("not little-endian (EI_DATA=%d)" % data[5])
    return {
        "machine": struct.unpack_from("<H", data, 0x12)[0],
        "e_shoff": struct.unpack_from("<I", data, 0x20)[0],
        "e_shentsize": struct.unpack_from("<H", data, 0x2E)[0],
        "e_shnum": struct.unpack_from("<H", data, 0x30)[0],
        "e_shstrndx": struct.unpack_from("<H", data, 0x32)[0],
    }


def parse_sections(data, hdr):
    """Return a list of {i,name,type,addr,off,size,link,entsz} section dicts (bounds-checked)."""
    shoff = hdr["e_shoff"]
    entsz = hdr["e_shentsize"]
    n = hdr["e_shnum"]
    shstr = hdr["e_shstrndx"]
    if shoff == 0 or n == 0:
        raise BLEGuardError("ELF has no section header table")
    if entsz < MIN_SH_ENTSIZE:
        raise BLEGuardError("section header entry size %d < %d" % (entsz, MIN_SH_ENTSIZE))
    if shoff + n * entsz > len(data):
        raise BLEGuardError("section header table extends past end of file")
    if not (0 <= shstr < n):
        raise BLEGuardError("invalid section header string table index %d" % shstr)

    shbase = shoff + shstr * entsz
    shstr_off = struct.unpack_from("<I", data, shbase + 0x10)[0]
    shstr_size = struct.unpack_from("<I", data, shbase + 0x14)[0]
    if shstr_off + shstr_size > len(data):
        raise BLEGuardError("section-name string table extends past end of file")

    def shname(idx):
        no = struct.unpack_from("<I", data, shoff + idx * entsz + 0)[0]
        if no >= shstr_size:
            return ""
        end = data.find(b"\0", shstr_off + no, shstr_off + shstr_size)
        if end < 0:
            return ""
        return data[shstr_off + no:end].decode("utf-8", "replace")

    secs = []
    for i in range(n):
        base = shoff + i * entsz
        secs.append({
            "i": i,
            "name": shname(i),
            "type": struct.unpack_from("<I", data, base + 4)[0],
            "addr": struct.unpack_from("<I", data, base + 0x0C)[0],
            "off": struct.unpack_from("<I", data, base + 0x10)[0],
            "size": struct.unpack_from("<I", data, base + 0x14)[0],
            "link": struct.unpack_from("<I", data, base + 0x18)[0],
            "entsz": struct.unpack_from("<I", data, base + 0x24)[0],
        })
    return secs


def parse_symbols(data, secs):
    """Return {name: (value, size, type, shndx)} for every defined symbol.

    Only symbol tables that pass structural validation (entsize >= 16, size a multiple of entsize,
    and inside the file) are read; symbol names must terminate within the linked string table.
    """
    syms = {}
    for s in secs:
        if s["type"] != SYT_SYMTAB:
            continue
        if s["entsz"] < MIN_SYM_ENTSIZE:
            continue
        if s["size"] % s["entsz"] != 0:
            continue
        if s["off"] + s["size"] > len(data):
            continue
        strsec = next((x for x in secs if x["i"] == s["link"] and x["type"] == SYT_STRTAB), None)
        if strsec is None:
            continue
        str_off = strsec["off"]
        str_size = strsec["size"]
        if str_off + str_size > len(data):
            continue
        count = s["size"] // s["entsz"]
        base = s["off"]
        for k in range(count):
            ebase = base + k * s["entsz"]
            name_off = struct.unpack_from("<I", data, ebase)[0]
            if name_off >= str_size:
                continue
            end = data.find(b"\0", str_off + name_off, str_off + str_size)
            if end < 0:
                continue
            name = data[str_off + name_off:end].decode("utf-8", "replace")
            value = struct.unpack_from("<I", data, ebase + 4)[0]
            size = struct.unpack_from("<I", data, ebase + 8)[0]
            typ = data[ebase + 12] & 0xF
            shndx = struct.unpack_from("<H", data, ebase + 14)[0]
            syms[name] = (value, size, typ, shndx)
    return syms


def va_to_file_off(data, secs, addr):
    """Map a virtual address to a (file offset, section name), or (None, None)."""
    for s in secs:
        if s["type"] == SYT_NOBITS:
            continue
        if s["addr"] <= addr < s["addr"] + s["size"]:
            off = s["off"] + (addr - s["addr"])
            if s["off"] <= off < s["off"] + s["size"] and off + 4 <= len(data):
                return off, s["name"]
    return None, None


def init_array_range(secs, syms):
    """Return (start, end) of the constructor-pointer array, or raise if not found."""
    for s in secs:
        if s["name"] == ".init_array" and s["size"] > 0:
            return s["addr"], s["addr"] + s["size"]
    if IA_START in syms and IA_END in syms:
        start, end = syms[IA_START][0], syms[IA_END][0]
        if start and end and start < end:
            return start, end
    raise BLEGuardError("cannot locate .init_array (no .init_array section and no %s/%s symbols)"
                        % (IA_START, IA_END))


def check_ble_memory(data):
    """Return a list of problem strings (empty list == pass)."""
    problems = []
    hdr = parse_header(data)
    secs = parse_sections(data, hdr)
    syms = parse_symbols(data, secs)

    # 1. marker symbol, defined and an object.
    if MARKER not in syms:
        problems.append("missing marker symbol '%s' (the BLE-alloc header is not linked)" % MARKER)
    else:
        _val, _sz, typ, shndx = syms[MARKER]
        if shndx == SHN_UNDEF:
            problems.append("marker symbol '%s' is undefined (not defined in any section)" % MARKER)
        elif typ != SYM_OBJECT:
            problems.append("marker symbol '%s' is type %d (expected OBJECT 1)" % (MARKER, typ))

    # 2. constructor symbol, defined, a code function with a non-zero address.
    if CTOR not in syms:
        problems.append("missing constructor symbol '%s' (the BLE-alloc ctor is not linked)" % CTOR)
        return problems
    ctor_val, _ctor_size, typ, shndx = syms[CTOR]
    if shndx == SHN_UNDEF:
        problems.append("constructor symbol '%s' is undefined (not defined in any section)" % CTOR)
        return problems
    if typ != SYM_FUNC:
        problems.append("constructor symbol '%s' is type %d (expected FUNC 2)" % (CTOR, typ))
    if ctor_val == 0:
        problems.append("constructor symbol '%s' has address 0 (absent/zero)" % CTOR)
        return problems

    # 3. constructor address present in .init_array (which must be a bounded, 4-aligned region).
    start, end = init_array_range(secs, syms)
    if (end - start) % PTR_SIZE != 0:
        problems.append("init_array region [0x%08x..0x%08x) is not a multiple of the %d-byte "
                        "pointer size" % (start, end, PTR_SIZE))
        return problems
    found = False
    mapped = 0
    for pos in range(start, end, PTR_SIZE):
        off, _sec = va_to_file_off(data, secs, pos)
        if off is None:
            continue
        mapped += 1
        if PTR32.unpack_from(data, off)[0] == ctor_val:
            found = True
            break
    if not found:
        if mapped == 0:
            problems.append("init_array region [0x%08x..0x%08x) does not map to file-backed data "
                            "(malformed sections)" % (start, end))
        else:
            problems.append(
                "constructor '%s' (addr 0x%08x) is NOT retained in .init_array "
                "[0x%08x..0x%08x) — the fix was gc/link-stripped" % (CTOR, ctor_val, start, end))

    return problems


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    quiet = False
    if argv and argv[0] in ("-q", "--quiet"):
        quiet = True
        argv = argv[1:]
    if len(argv) != 1:
        sys.stderr.write("usage: check_ble_memory.py [-q] path/to/firmware.elf\n")
        return 2
    path = argv[0]
    if not os.path.isfile(path):
        sys.stderr.write("check_ble_memory: ERROR: file not found: %s\n" % path)
        return 1
    try:
        with open(path, "rb") as fh:
            data = fh.read()
    except OSError as e:
        sys.stderr.write("check_ble_memory: ERROR: cannot read %s: %s\n" % (path, e))
        return 1

    try:
        problems = check_ble_memory(data)
    except BLEGuardError as e:
        sys.stderr.write("check_ble_memory: ERROR: %s\n" % e)
        return 1

    if problems:
        for p in problems:
            sys.stderr.write("check_ble_memory: FAIL: %s\n" % p)
        sys.stderr.write("check_ble_memory: %d requirement(s) failed in %s\n" % (len(problems), path))
        return 1

    if not quiet:
        sys.stdout.write("check_ble_memory: OK: %s retains _bleLibraryInUse, "
                         "_setBleLibraryInUse in .init_array\n" % path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
