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

    Keys are the FULLY-QUALIFIED class identity: `package + '.' + class` when the file declares a
    package, else just the simple class name. This matches how JUnit writes the `classname` the
    results-XML parser reads, so `declared - executed` compares like with like (see the
    package-collision note: before this, two packages with the same simple class+method collapsed
    into one identity and masked a real missing test).
    """
    found = set()
    unmatched = []
    annotation = re.compile(r"^\s*@Test\b")
    fun = re.compile(r"^\s*fun\s+(\w+)\s*\(")
    class_decl = re.compile(r"^\s*(?:open\s+|abstract\s+|internal\s+|private\s+)*class\s+(\w+)")
    package_decl = re.compile(r"^\s*package\s+([\w.]+)\s*$")

    for dirpath, _dirs, files in os.walk(root):
        for name in files:
            if not name.endswith(".kt"):
                continue
            path = os.path.join(dirpath, name)
            with open(path, encoding="utf-8") as handle:
                lines = handle.read().splitlines()

            pkg = None
            for line in lines[:20]:
                pm = package_decl.match(line)
                if pm:
                    pkg = pm.group(1)
                    break

            def fq(owner):
                return "%s.%s" % (pkg, owner) if pkg else owner

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
                        found.add((fq(owner), same_line.group(1))) if owner else unmatched.append((path, i + 1))
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
                        found.add((fq(owner), method))
                    else:
                        unmatched.append((path, i + 1))
                i += 1
    return found, unmatched


def executed_tests(root):
    """Return {(class, method)} from the JUnit XML results.

    A <skipped> testcase is deliberately NOT counted as executed: JUnit emits the testcase node
    for a skipped test, but it was never attempted, so counting it as executed would hide a
    declared test that silently never reached execution. A <failure> or <error> testcase IS
    counted, because the test was discovered and at least attempted (Gradle fails the build on
    it); the audit is discovery-only and must not double-report a mismatch Gradle already
    surfaces. This makes no claim about why it failed: a setup error also counts as discovered.
    """
    if not os.path.isdir(root):
        return set()
    found = set()
    for name in os.listdir(root):
        if not name.startswith("TEST-") or not name.endswith(".xml"):
            continue
        tree = ET.parse(os.path.join(root, name))
        for case in tree.getroot().iter("testcase"):
            if case.find("skipped") is not None:
                continue
            # Key on the FULLY-QUALIFIED classname (JUnit writes `com.pkg.Class`) so identity
            # matches the declared_tests() package-qualified keys. Do NOT reduce to the last dot
            # segment: two packages with the same simple class+method would collapse.
            cls = (case.get("classname") or "").strip()
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

    # Zero-test gate (DByte308/AirTAK#12): a Gradle task can report success while executing zero
    # tests (the test task is not wired to useJUnitPlatform()). This must be rejected, not green.
    # Reject ONLY when executed == 0, regardless of declared. A legitimate parameterized-only suite
    # still executes >0 testcases so it is NOT flagged. Note: we must not key this off a non-empty
    # results dir, because Gradle leaves a `binary/` subdirectory there even when zero TEST-*.xml
    # are produced.
    if not executed:
        print("\nFAIL: ZERO TESTS EXECUTED.")
        print("executed() returned no (class, method) pairs. If Gradle reported success, the test")
        print("task is likely not wired to useJUnitPlatform(), so an empty or invalid suite")
        print("reports green. Zero executed tests must be treated as a failure.")
        return 1

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
