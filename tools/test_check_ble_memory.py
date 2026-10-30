#!/usr/bin/env python3
"""Unit tests for check_ble_memory.py using hand-built synthetic ELF32 LE fixtures.

No readelf, no toolchain, no external deps: a small `make_elf()` helper assembles a valid
ELF32 little-endian object (ELF header + section header table + .symtab/.strtab/.shstrtab, with a
.text block for the ctor, a .data block for the marker, and an .init_array region holding the ctor
pointer). Each test flips one property (marker present, ctor present, ctor retained, init_array
location, endianness, class) and asserts the guard's behaviour.

Run:  python3 test_check_ble_memory.py
"""
import os
import shutil
import struct
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import check_ble_memory as cbm

SHN_ABS = 0xFFF1

# Section-type constants local to the fixture builder (kept independent of the parser internals).
SH_PROGBITS = 1
SH_SYMTAB = 2
SH_STRTAB = 3


# ── synthetic ELF32-LE builder ───────────────────────────────────────────────
def build_strtab(names):
    buf = b"\0"  # index 0 = empty string (null symbol)
    offs = {}
    for n in names:
        offs[n] = len(buf)
        buf += n.encode() + b"\0"
    return buf, offs


def build_shstrtab(names):
    buf = b""
    offs = {}
    for n in names:
        offs[n] = len(buf)
        buf += n.encode() + b"\0"
    return buf, offs


def build_ehdr(machine, shoff, shentsize, shnum, shstrndx, cls=1, data_enc=1):
    h = bytearray(52)
    h[0:4] = b"\x7fELF"
    h[4] = cls
    h[5] = data_enc
    h[6] = 1
    struct.pack_into("<H", h, 0x10, 2)    # e_type EXEC
    struct.pack_into("<H", h, 0x12, machine)
    struct.pack_into("<I", h, 0x14, 1)    # e_version
    struct.pack_into("<I", h, 0x20, shoff)
    struct.pack_into("<H", h, 0x28, 52)   # e_ehsize
    struct.pack_into("<H", h, 0x2E, shentsize)
    struct.pack_into("<H", h, 0x30, shnum)
    struct.pack_into("<H", h, 0x32, shstrndx)
    return bytes(h)


def make_elf(marker=True, ctor=True, in_array=True, init_array_section=True,
             machine=0xFE, cls=1, data_enc=1, marker_undef=False, ctor_undef=False,
             ctor_zero=False):
    """Build a minimal ELF32 LE object.

    Section indices: 0=NULL 1=.text 2=.data [3=.init_array] 4=.symtab 5=.strtab 6=.shstrtab
    (without .init_array the tables shift to 3/4/5). A ctor FUNC lives at 0x1000, a marker OBJECT
    at 0x2000, and `.init_array` (or an __init_array_start/end-delimited region) holds the ctor
    pointer when `in_array`.
    """
    TEXT, DATA, IA = 0x1000, 0x2000, 0x3000
    names = [".text", ".data"] + ([".init_array"] if init_array_section else [])
    sym_sec = 1 + len(names)
    str_sec = sym_sec + 1
    shstr_sec = str_sec + 1
    n_sections = shstr_sec + 1

    sym_names = [cbm.MARKER, cbm.CTOR]
    if not init_array_section and in_array:
        sym_names += [cbm.IA_START, cbm.IA_END]

    text_data = b"\x00" * 4
    data_data = struct.pack("<I", 0x01)
    if not init_array_section and in_array and ctor:   # ctor pointer at 0x2004
        data_data += struct.pack("<I", TEXT)
    init_data = b""
    if init_array_section:
        init_data = struct.pack("<I", TEXT) if (in_array and ctor) else struct.pack("<I", 0xDEAD0000)

    strbuf, stroff = build_strtab(sym_names)
    entries = [b"\x00" * 16]                            # null symbol (index 0)
    if marker:
        entries.append(struct.pack("<IIIBBH", stroff[cbm.MARKER],
                                   0 if marker_undef else DATA, 4,
                                   (1 << 4) | cbm.SYM_OBJECT, 0,
                                   0 if marker_undef else 2))
    if ctor:
        entries.append(struct.pack("<IIIBBH", stroff[cbm.CTOR],
                                   0 if ctor_zero else TEXT, 4,
                                   (1 << 4) | cbm.SYM_FUNC, 0,
                                   0 if ctor_undef else 1))
    if not init_array_section and in_array and ctor:
        entries.append(struct.pack("<IIIBBH", stroff[cbm.IA_START], DATA + 4, 0, 0, 0, SHN_ABS))
        entries.append(struct.pack("<IIIBBH", stroff[cbm.IA_END], DATA + 8, 0, 0, 0, SHN_ABS))
    sym_data = b"".join(entries)
    shstrbuf, shstroff = build_shstrtab([".text", ".data", ".init_array",
                                         ".symtab", ".strtab", ".shstrtab"])
    strtab_off = {n: shstroff[n] for n in shstroff if n}

    specs = [(".text", SH_PROGBITS, TEXT, len(text_data), 0x6, 0, 0, 0),
             (".data", SH_PROGBITS, DATA, len(data_data), 0x3, 0, 0, 0)]
    if init_array_section:
        specs.append((".init_array", SH_PROGBITS, IA, len(init_data), 0x3, 0, 0, 0))
    specs += [(".symtab", SH_SYMTAB, 0, len(sym_data), 0, str_sec, 1, 16),
              (".strtab", SH_STRTAB, 0, len(strbuf), 0, 0, 0, 0),
              (".shstrtab", SH_STRTAB, 0, len(shstrbuf), 0, 0, 0, 0)]
    sec_data = {".text": text_data, ".data": data_data, ".init_array": init_data,
                ".symtab": sym_data, ".strtab": strbuf, ".shstrtab": shstrbuf}

    data_region = bytearray()
    offsets = {}
    for (name, typ, addr, size, flags, link, info, entsz) in specs:
        offsets[name] = 52 + len(data_region)
        data_region += sec_data[name]
    e_shoff = 52 + len(data_region)

    shdrs = b"\x00" * 40                                # NULL section (index 0)
    for (name, typ, addr, size, flags, link, info, entsz) in specs:
        shdrs += struct.pack("<IIIIIIIIII", strtab_off[name], typ, flags, addr,
                             offsets[name], size, link, info, 0, entsz)

    ehdr = build_ehdr(machine, e_shoff, 40, n_sections, shstr_sec, cls, data_enc)
    return bytes(ehdr) + bytes(data_region) + shdrs


def _patch_u16(data, rel, val):
    b = bytearray(data)
    struct.pack_into("<H", b, rel, val)
    return bytes(b)


def _patch_u32(data, rel, val):
    b = bytearray(data)
    struct.pack_into("<I", b, rel, val)
    return bytes(b)


def _patch_section_u32(data, sec_name, rel, val):
    """Patch a u32 field (rel offset into the section header) of the named section."""
    hdr = cbm.parse_header(data)
    secs = cbm.parse_sections(data, hdr)
    s = next(x for x in secs if x["name"] == sec_name)
    base = hdr["e_shoff"] + s["i"] * hdr["e_shentsize"]
    return _patch_u32(data, base + rel, val)


class BLEGuardTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="ble-guard-")
        self.addCleanup(lambda: shutil.rmtree(self.tmp, ignore_errors=True))

    def _write(self, data, name="fw.elf"):
        p = os.path.join(self.tmp, name)
        with open(p, "wb") as f:
            f.write(data)
        return p

    def test_good_section_form_passes(self):
        data = make_elf(marker=True, ctor=True, in_array=True, init_array_section=True)
        self.assertEqual(cbm.check_ble_memory(data), [])

    def test_good_symbol_form_passes(self):
        data = make_elf(marker=True, ctor=True, in_array=True, init_array_section=False)
        self.assertEqual(cbm.check_ble_memory(data), [])

    def test_missing_marker_fails(self):
        problems = cbm.check_ble_memory(make_elf(marker=False, ctor=True, in_array=True))
        self.assertTrue(any("marker" in p for p in problems), problems)

    def test_missing_constructor_fails(self):
        problems = cbm.check_ble_memory(make_elf(marker=True, ctor=False, in_array=True))
        self.assertTrue(any("constructor" in p for p in problems), problems)

    def test_constructor_not_retained_fails(self):
        problems = cbm.check_ble_memory(
            make_elf(marker=True, ctor=True, in_array=False, init_array_section=True))
        self.assertTrue(any("NOT retained" in p for p in problems), problems)

    def test_no_init_array_fails(self):
        with self.assertRaises(cbm.BLEGuardError):
            cbm.check_ble_memory(make_elf(marker=True, ctor=True, in_array=False,
                                          init_array_section=False))

    def test_bad_magic_rejected(self):
        data = make_elf()
        data = b"NOTELF" + data[6:]
        with self.assertRaises(cbm.BLEGuardError):
            cbm.check_ble_memory(data)

    def test_big_endian_rejected(self):
        with self.assertRaises(cbm.BLEGuardError):
            cbm.check_ble_memory(make_elf(data_enc=2))

    def test_class64_rejected(self):
        with self.assertRaises(cbm.BLEGuardError):
            cbm.check_ble_memory(make_elf(cls=2))

    def test_cli_returns_0_on_good(self):
        self.assertEqual(cbm.main([self._write(make_elf())]), 0)

    def test_cli_returns_1_on_missing_file(self):
        self.assertEqual(cbm.main([os.path.join(self.tmp, "nope.elf")]), 1)

    def test_cli_returns_1_on_bad_elf(self):
        self.assertEqual(cbm.main([self._write(make_elf(marker=False), "bad.elf")]), 1)

    # ── malformed / truncated / undefined / zero hardening ──────────────────
    def test_truncated_header_rejected(self):
        with self.assertRaises(cbm.BLEGuardError):
            cbm.check_ble_memory(make_elf()[:10])

    def test_truncated_section_header_rejected(self):
        # e_shoff points far past the end of the file -> section table extends past EOF.
        with self.assertRaises(cbm.BLEGuardError):
            cbm.check_ble_memory(_patch_u32(make_elf(), 0x20, 0x7FFFFFFF))

    def test_section_entsize_below_min_rejected(self):
        with self.assertRaises(cbm.BLEGuardError):
            cbm.check_ble_memory(_patch_u16(make_elf(), 0x2E, 32))

    def test_section_table_extents_past_file_rejected(self):
        with self.assertRaises(cbm.BLEGuardError):
            cbm.check_ble_memory(_patch_u16(make_elf(), 0x30, 0xFFFF))

    def test_symbol_entsize_below_min_fails(self):
        data = _patch_section_u32(make_elf(), ".symtab", 0x24, 8)
        problems = cbm.check_ble_memory(data)
        self.assertTrue(problems, "a symtab with entsize<16 must not silently pass")

    def test_symbol_size_not_multiple_fails(self):
        data = _patch_section_u32(make_elf(), ".symtab", 0x14, 15)  # not a multiple of 16
        problems = cbm.check_ble_memory(data)
        self.assertTrue(problems, "a symtab with non-multiple size must not silently pass")

    def test_undefined_marker_fails(self):
        problems = cbm.check_ble_memory(make_elf(marker=True, marker_undef=True))
        self.assertTrue(any("undefined" in p for p in problems), problems)

    def test_undefined_ctor_fails(self):
        problems = cbm.check_ble_memory(make_elf(ctor=True, ctor_undef=True))
        self.assertTrue(any("undefined" in p for p in problems), problems)

    def test_zero_ctor_address_fails(self):
        problems = cbm.check_ble_memory(make_elf(ctor=True, ctor_zero=True))
        self.assertTrue(any("address 0" in p for p in problems), problems)

    def test_init_array_not_4_aligned_fails(self):
        data = _patch_section_u32(make_elf(), ".init_array", 0x14, 5)
        problems = cbm.check_ble_memory(data)
        self.assertTrue(any("multiple of the 4-byte pointer" in p for p in problems), problems)


if __name__ == "__main__":
    unittest.main(verbosity=2)
