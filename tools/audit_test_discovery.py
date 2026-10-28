#!/usr/bin/env python3
# AirTAK — tools/audit_test_discovery.py
#
# Compare the tests the source DECLARES against the tests the runner actually EXECUTED.
#
# Why this exists: JUnit 5 silently skips a @Test method whose return type is not Unit. There is
# no warning, no failure, and no entry in the results XML, so the class reports success while a
# test never ran. A green suite that quietly omits a test is worse than a red one.
#
# Comparison is by (class, method) identity rather than method name alone, because the same method
# name can exist in two classes, and name-only sets hide exactly the case this tool looks for.
#
# Usage:
#   ./tools/gradle_android.sh :app:testDebugUnitTest --no-daemon
#   python3 tools/audit_test_discovery.py

import os
import re
import sys
import xml.etree.ElementTree as ET

TEST_SRC = "android/app/src/test"
RESULTS = "android/app/build/test-results/testDebugUnitTest"

# A @Test may be followed by other annotations before the fun keyword.
TEST_ANNOTATION = re.compile(r"^\s*@Test\b")
FUN = re.compile(r"^\s*(?:@\w+(?:\([^\n]*\))?\s*)*fun\s+(\w+)\s*\(")
CLASS = re.compile(r"^\s*(?:@\w+(?:\([^\n]*\))?\s*)*(?:open\s+|abstract\s+|internal\s+|private\s+)*class\s+(\w+)")
CLASS_FINDALL = re.compile(
    r"^(?:@\w+(?:\([^\n]*\))?\s*)*(?:open\s+|abstract\s+|internal\s+|private\s+)*class\s+(\w+)",
    re.M,
)


def declared_tests(root):
    """Return {(class, method)} for every @Test in the source tree.

    Each @Test is attributed to the nearest preceding class whose name ends in Test. Test files
    here declare helper classes (fake servers, recorders) and sometimes more than one test class,
    so neither "innermost enclosing class" nor "the file's first class" is correct.
    """
    found = set()
    unmatched = []
    annotation = re.compile(r"^\s*@Test\b")
    fun = re.compile(r"^\s*fun\s+(\w+)\s*\(")
    class_decl = re.compile(r"^\s*(?:open\s+|abstract\s+|internal\s+|private\s+)*class\s+(\w+)")

    for dirpath, _dirs, files in os.walk(root):
        for name in files:
            if not name.endswith(".kt"):
                continue
            path = os.path.join(dirpath, name)
            with open(path, encoding="utf-8") as handle:
                lines = handle.read().splitlines()

            owner = None
            i = 0
            while i < len(lines):
                cd = class_decl.match(lines[i])
                if cd and cd.group(1).endswith("Test"):
                    owner = cd.group(1)
                if annotation.match(lines[i]):
                    method = None
                    # @Test and fun on the same line is legal and used in this repo.
                    same_line = re.search(r"\bfun\s+(\w+)\s*\(", lines[i])
                    if same_line:
                        found.add((owner, same_line.group(1))) if owner else unmatched.append((path, i + 1))
                        i += 1
                        continue
                    j = i + 1
                    while j < len(lines) and j < i + 8:
                        nxt = lines[j]
                        if not nxt.strip() or nxt.strip().startswith("@"):
                            j += 1
                            continue
                        fm = fun.match(nxt)
                        if fm:
                            method = fm.group(1)
                        break
                    if method and owner:
                        found.add((owner, method))
                    else:
                        unmatched.append((path, i + 1))
                i += 1
    return found, unmatched


def executed_tests(root):
    """Return {(class, method)} from the JUnit XML results."""
    if not os.path.isdir(root):
        return set()
    found = set()
    for name in os.listdir(root):
        if not name.startswith("TEST-") or not name.endswith(".xml"):
            continue
        tree = ET.parse(os.path.join(root, name))
        for case in tree.getroot().iter("testcase"):
            cls = (case.get("classname") or "").split(".")[-1]
            method = (case.get("name") or "").split("(")[0].strip()
            if cls and method:
                found.add((cls, method))
    return found


def main():
    repo = sys.argv[1] if len(sys.argv) > 1 else "."
    src_root = os.path.join(repo, TEST_SRC)
    res_root = os.path.join(repo, RESULTS)

    if not os.path.isdir(res_root):
        print("FAIL: no results directory. Run the unit tests first.")
        return 2

    declared, unmatched = declared_tests(src_root)
    executed = executed_tests(res_root)

    print("declared (class, method) pairs: %d" % len(declared))
    print("executed (class, method) pairs: %d" % len(executed))

    missing = sorted(declared - executed)
    extra = sorted(executed - declared)

    status = 0
    if missing:
        status = 1
        print("\nDECLARED BUT NEVER EXECUTED — possible silent skips:")
        for cls, method in missing:
            print("   %s.%s" % (cls, method))
    else:
        print("\nNo declared @Test was silently skipped.")

    if extra:
        # Not automatically a defect: @ParameterizedTest, @RepeatedTest and @TestFactory produce
        # executed cases that this parser does not treat as @Test declarations.
        print("\nEXECUTED WITHOUT A MATCHING @Test DECLARATION — verify these are parameterized,")
        print("repeated or factory-generated, not a parser blind spot:")
        for cls, method in extra:
            print("   %s.%s" % (cls, method))

    if unmatched:
        print("\n@Test annotations the parser could not resolve to a method (fix the parser or the code):")
        for path, line in unmatched:
            print("   %s:%d" % (path, line))
        status = 1

    return status


if __name__ == "__main__":
    sys.exit(main())
