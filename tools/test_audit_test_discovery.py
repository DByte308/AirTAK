#!/usr/bin/env python3
# AirTAK — tools/test_audit_test_discovery.py
# Regression tests for the test-discovery audit, tools/audit_test_discovery.py.
#
# These run offline and stdlib-only. Nothing here may touch the network, the real
# android/app/src/test tree, or the real build output. Every scenario builds a throwaway
# FixtureRepo in a temp dir and points the audit at it via REPO_ROOT.
#
# What this suite pins down (and why):
#   * A JUnit Gradle task that reports success while executing zero tests ("XML0") must be
#     rejected by the guard, specifically in the BOTH-EMPTY case (declared == 0 AND executed == 0).
#     That is the false-green reproduced in DByte308/AirTAK#12: when declared is NONZERO the
#     unpatched audit already returns rc1 via the declared-not-executed mismatch; only an empty
#     suite slips through as rc0.
#   * A skipped testcase in the results XML must NOT be counted as executed. JUnit emits the
#     testcase node for a skipped test that was never attempted, so counting it masks a declared
#     test that silently never ran. A <failure>/<error> testcase IS counted (the test was
#     discovered and at least attempted; Gradle fails the build on it), and no claim is made about
#     WHY it failed. (Skipped-as-executed is a defect; exposed here as a known-failing regression.)
#   * A declared-but-never-executed class+method must fail the audit (this is the audit's
#     original purpose).
#   * A legitimate parameterized-only suite that executes >0 tests while declaring 0 @Test must
#     NOT be forced to fail just because `declared == 0`. (Boundary fixture; see test.)
#
# Execution: python3 tools/test_audit_test_discovery.py
# Exit 0 on success, nonzero if any assertion fails.

import importlib.util
import os
import shutil
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))

_spec = importlib.util.spec_from_file_location(
    "audit_test_discovery", os.path.join(HERE, "audit_test_discovery.py")
)
audit = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(audit)


# The audit reads `<REPO>/android/app/src/test` and
# `<REPO>/android/app/build/test-results/testDebugUnitTest`. A fixture just needs those two
# directories under a throwaway root; the rest of an Android project is not consulted.
class FixtureRepo:
    def __init__(self, root):
        self.root = root
        self.src = os.path.join(root, "android", "app", "src", "test")
        self.results = os.path.join(root, "android", "app", "build", "test-results", "testDebugUnitTest")

    @classmethod
    def make(cls):
        root = tempfile.mkdtemp(prefix="atd_fixture_")
        self = cls(root)
        os.makedirs(self.src, exist_ok=True)
        os.makedirs(self.results, exist_ok=True)
        return self

    def write_kt(self, relpath, text):
        path = os.path.join(self.src, relpath)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as h:
            h.write(text)

    def write_xml(self, filename, text):
        path = os.path.join(self.results, filename)
        with open(path, "w", encoding="utf-8") as h:
            h.write(text)

    def declared(self):
        return audit.declared_tests(self.root)[0]

    def executed(self):
        return audit.executed_tests(self.results)

    def destroy(self):
        shutil.rmtree(self.root, ignore_errors=True)


def _testsuite(name, cases):
    """Render a minimal JUnit XML result file with the given testcase bodies."""
    parts = ['<?xml version="1.0" encoding="UTF-8"?>']
    parts.append('<testsuite name="%s" tests="%d" failures="0" errors="0" skipped="0">'
                 % (name, len(cases)))
    for classname, method, body in cases:
        parts.append('<testcase classname="%s" name="%s">%s</testcase>'
                     % (classname, method, body))
    parts.append('</testsuite>')
    return "\n".join(parts)


class DeclaredParse(unittest.TestCase):
    """The source-declaration parser: @Test attribution, same-line, multiline, lookahead."""

    def test_attributes_to_nearest_preceding_test_class(self):
        f = FixtureRepo.make()
        try:
            f.write_kt("com/airtak/a/FooTest.kt", """\
package com.airtak.a

class FakeServer {  // helper class, must not capture the @Test
}

class FooTest {
    @Test fun one() {}
}
""")
            pairs = f.declared()
            self.assertIn(("com.airtak.a.FooTest", "one"), pairs, "helper class must not capture the @Test")
            self.assertNotIn(("com.airtak.a.FakeServer", "one"), pairs)
        finally:
            f.destroy()

    def test_same_line_test_fun_is_legal(self):
        f = FixtureRepo.make()
        try:
            f.write_kt("com/airtak/a/BarTest.kt", """\
package com.airtak.a
class BarTest {
    @Test fun inline() {}
}
""")
            self.assertIn(("com.airtak.a.BarTest", "inline"), f.declared())
        finally:
            f.destroy()

    def test_multiline_annotation_then_fun(self):
        f = FixtureRepo.make()
        try:
            f.write_kt("com/airtak/a/BazTest.kt", """\
package com.airtak.a
class BazTest {
    @Test
    fun spaced() {}
}
""")
            self.assertIn(("com.airtak.a.BazTest", "spaced"), f.declared())
        finally:
            f.destroy()

    def test_lookahead_beyond_eight_lines_is_unmatched(self):
        # Between @Test and fun, the parser skips blanks/annotations with an 8-line lookahead.
        f = FixtureRepo.make()
        try:
            body_lines = ["    @Test"] + ["    // filler"] * 9 + ["    fun tooFar() {}"] + ["}"]
            f.write_kt("com/airtak/a/DeepTest.kt", "package com.airtak.a\nclass DeepTest {\n" +
                       "\n".join(body_lines[:-1]) + "\n}\n")
            declared, unmatched = audit.declared_tests(f.root)
            self.assertNotIn(("DeepTest", "tooFar"), declared)
            self.assertEqual(1, len(unmatched), "a @Test beyond lookahead becomes unmatched")
        finally:
            f.destroy()


class ExecutedParse(unittest.TestCase):
    """The results-XML parser: what counts as executed, package handling, malformed input."""

    def test_normal_pass_counts_as_executed(self):
        f = FixtureRepo.make()
        try:
            f.write_xml("TEST-X.xml", _testsuite("com.airtak.x", [("FooTest", "passes()", "")]))
            self.assertEqual({("FooTest", "passes")}, f.executed())
        finally:
            f.destroy()

    def test_name_truncates_at_first_open_paren(self):
        f = FixtureRepo.make()
        try:
            f.write_xml("TEST-X.xml",
                        _testsuite("com.airtak.x", [("FooTest", "display(has args)()", "")]))
            self.assertIn(("FooTest", "display"), f.executed())
        finally:
            f.destroy()

    def test_skipped_testcase_must_not_count_as_executed(self):
        # DEFECT: the current parser counts a <skipped/> testcase as executed, which can mask a
        # declared test that never ran. This test pins the EXPECTED (correct) behaviour.
        f = FixtureRepo.make()
        try:
            f.write_xml("TEST-X.xml", _testsuite("com.airtak.x", [
                ("FooTest", "passes()", ""),
                ("FooTest", "skipped()", "<skipped/>"),
            ]))
            self.assertEqual({("FooTest", "passes")}, f.executed(),
                             "skipped testcases must NOT count as executed")
        finally:
            f.destroy()

    def test_failure_and_error_testcases_do_count_as_executed(self):
        # A failed/errored <testcase> RAN (Gradle fails the build on it). The audit is
        # discovery-only; it should NOT treat failure/error as "never executed", because that
        # would double-report a mismatch Gradle already flags. Only <skipped> is the silent danger.
        f = FixtureRepo.make()
        try:
            f.write_xml("TEST-X.xml", _testsuite("com.airtak.x", [
                ("FooTest", "passes()", ""),
                ("FooTest", "fails()", '<failure message="boom"/>'),
                ("FooTest", "errors()", '<error type="x"/>'),
            ]))
            self.assertEqual({("FooTest", "passes"), ("FooTest", "fails"), ("FooTest", "errors")},
                             f.executed(),
                             "failure/error testcases DID run; they count as executed")
        finally:
            f.destroy()

    def test_empty_results_dir_yields_empty_set(self):
        f = FixtureRepo.make()
        try:
            self.assertEqual(set(), f.executed())
        finally:
            f.destroy()

    def test_malformed_xml_must_not_crash_cleanly(self):
        # DEFECT: current parser throws ET.ParseError instead of reporting a clean nonzero exit.
        f = FixtureRepo.make()
        try:
            f.write_xml("TEST-BAD.xml", '<?xml version="1.0"?><testsuite><testcase classname="c" name="n"')
            with self.assertRaises(ET.ParseError):
                f.executed()
        finally:
            f.destroy()

    def test_package_collision_identity_is_preserved(self):
        # FIXED BEHAVIOR (was a KNOWN LIMITATION): two packages with the same simple class+method
        # must remain DISTINCT identities once the parser keys on the fully-qualified classname.
        f = FixtureRepo.make()
        try:
            f.write_xml("TEST-A.xml", _testsuite("com.airtak.a", [("com.airtak.a.FooTest", "same()", "")]))
            f.write_xml("TEST-B.xml", _testsuite("com.airtak.b", [("com.airtak.b.FooTest", "same()", "")]))
            got = f.executed()
            self.assertEqual({("com.airtak.a.FooTest", "same"), ("com.airtak.b.FooTest", "same")}, got,
                             "distinct packages must NOT collapse onto one (class, method)")
        finally:
            f.destroy()


def _main_rc(root):
    """Run the audit's main() against a fixture root; return (exit_code, captured_stdout)."""
    import io
    from contextlib import redirect_stdout

    old = sys.argv
    sys.argv = ["audit_test_discovery", root]
    buf = io.StringIO()
    try:
        with redirect_stdout(buf):
            rc = audit.main()
    finally:
        sys.argv = old
    return rc, buf.getvalue()


class ZeroTestGate(unittest.TestCase):
    """The core regression: a task that reports success while executing zero tests must be caught.

    CRITICAL DISTINCTION the reviewer flagged: the unpatched audit catches a NONZERO declared suite
    that runs zero tests (via the declared-not-executed mismatch, rc1). It is BLIND ONLY to the
    BOTH-EMPTY case (declared == 0 AND executed == 0), where `declared - executed` is empty and it
    returns rc0. The guard must fix the BOTH-EMPTY case without regressing either the nonzero or
    the parameterized-only regimes.
    """

    def test_both_empty_is_the_specific_hole(self):
        # THE HOLE (DByte308/AirTAK#12): source declares zero tests AND nothing executed. The
        # unpatched audit returns rc0 (green) because declared - executed is empty. The guard must
        # reject this. Asserted on the audit's overall verdict (main() exit code).
        f = FixtureRepo.make()
        try:
            rc, out = _main_rc(f.root)
            self.assertNotEqual(rc, 0,
                                "REGRESSION HOLE: zero declared AND zero executed must be rejected")
            self.assertIn("ZERO TESTS EXECUTED", out)
        finally:
            f.destroy()

    def test_unpatched_audit_is_green_on_both_empty(self):
        # Evidence that the both-empty case is genuinely a pre-existing hole, not a strawman.
        # Replicate the OLD (pre-patch) verdict algorithm inline: it had no zero-test gate, so for
        # both-empty it computes `missing = declared - executed = empty` -> status 0 (green). This
        # documents WHY the guard is needed, without depending on a transient file or over-claiming
        # the nonzero case.
        f = FixtureRepo.make()
        try:
            declared = audit.declared_tests(f.root)[0]
            # Old executed_tests counted every <testcase> (no skipped filter); on an EMPTY results
            # dir it is empty regardless.
            executed = set()
            missing = declared - executed
            # old main(): status stays 0 unless missing/unmatched non-empty -> both-empty is green.
            old_verdict = 1 if missing else 0
            self.assertEqual(0, len(declared), "both-empty: no declared @Test")
            self.assertEqual(0, old_verdict, "both-empty: OLD algorithm was GREEN (the hole)")
        finally:
            f.destroy()

    def test_nonzero_declared_zero_executed_is_already_rc1_unpatched(self):
        # Reviewer's evidence point: with a NONZERO declared suite that runs zero tests, the
        # unpatched audit already returns rc1 (declared-not-executed). The guard must not over-claim
        # it fixed something the old code already caught; it is redundant-but-harmless here since it
        # fires the zero-test gate first.
        f = FixtureRepo.make()
        try:
            f.write_kt("com/airtak/a/FooTest.kt",
                       "package com.airtak.a\nclass FooTest {\n    @Test fun a() {}\n}\n")
            declared, _ = audit.declared_tests(f.root)
            executed = audit.executed_tests(f.results)
            self.assertGreater(len(declared), 0)
            self.assertEqual(0, len(executed))
            missing = declared - executed
            self.assertTrue(missing, "NONZERO declared + zero executed is a mismatch (already caught)")
            rc, out = _main_rc(f.root)
            self.assertNotEqual(rc, 0, "nonzero declared + zero executed must be rc!=0")
        finally:
            f.destroy()

    def test_results_dir_exists_but_zero_test_xml_is_the_hole(self):
        # mi50 case (b): the results dir exists but contains no TEST-*.xml at all. Same false green.
        f = FixtureRepo.make()
        try:
            # results dir exists (created by FixtureRepo.make) but empty -> no TEST-*.xml.
            rc, out = _main_rc(f.root)
            self.assertNotEqual(rc, 0, "zero TEST-*.xml must be rejected")
        finally:
            f.destroy()

    def test_executed_zero_must_fail_even_if_declared_nonzero(self):
        # declared > 0 but nothing executed. The zero-test gate fires first (rc != 0). The audit
        # still rejects it (that is the invariant); it just reports via the ZERO TESTS message
        # rather than the mismatch block, because zero-executed is the stronger, earlier failure.
        f = FixtureRepo.make()
        try:
            f.write_kt("com/airtak/a/FooTest.kt", "package com.airtak.a\nclass FooTest {\n    @Test fun t() {}\n}\n")
            rc, out = _main_rc(f.root)
            self.assertNotEqual(rc, 0, "zero executed tests must never be green")
            self.assertIn("ZERO TESTS EXECUTED", out)
        finally:
            f.destroy()

    def test_parameterized_only_suite_must_not_fail_on_declared_zero(self):
        # BOUNDARY: a legitimate parameterized-only suite executes >0 tests but declares no @Test.
        # The guard must NOT blanket-fail `declared == 0`; it must only fail when executed == 0.
        # (The audit treats `extra` = executed-without-declared as informational, by design.)
        f = FixtureRepo.make()
        try:
            f.write_xml("TEST-P.xml", _testsuite("com.airtak.p", [
                ("ParamTest", "case[0]", ""),
                ("ParamTest", "case[1]", ""),
            ]))
            declared = f.declared()
            executed = f.executed()
            self.assertEqual(0, len(declared), "parameterized-only: zero declared @Test is expected")
            self.assertGreater(len(executed), 0, "executed > 0 so this is NOT a zero-test regression")
            # extra (executed - declared) is informational, never fatal.
            self.assertTrue(executed - declared)
            rc, out = _main_rc(f.root)
            self.assertEqual(rc, 0,
                             "parameterized-only suite executing >0 must NOT fail on declared==0")
        finally:
            f.destroy()


class CliMain(unittest.TestCase):
    """End-to-end main()/CLI fixtures: these assert the audit's overall exit-code contract.

    Contract: 0 clean · 1 declared-not-executed OR unresolvable @Test OR zero-tests-executed ·
    2 results dir missing. `extra` (executed without a declaration) is informational-only and never
    fatal (parameterized/repeated/factory).
    """

    def test_valid_matching_nonzero_is_rc0(self):
        # All declared @Test appear in the results XML, @Test count == executed count == nonzero.
        f = FixtureRepo.make()
        try:
            f.write_kt("com/airtak/a/FooTest.kt",
                       "package com.airtak.a\nclass FooTest {\n    @Test fun a() {}\n    @Test fun b() {}\n}\n")
            f.write_xml("TEST-A.xml", _testsuite("com.airtak.a", [
                ("com.airtak.a.FooTest", "a()", ""),
                ("com.airtak.a.FooTest", "b()", ""),
            ]))
            rc, out = _main_rc(f.root)
            self.assertEqual(0, rc, "valid matching nonzero suite must be clean (rc0)")
            self.assertIn("declared (class, method) pairs: 2", out)
            self.assertIn("No declared @Test was silently skipped.", out)
        finally:
            f.destroy()

    def test_partial_mismatch_is_rc1(self):
        # Some declared @Test are missing from the results XML -> declared-not-executed -> rc1.
        f = FixtureRepo.make()
        try:
            f.write_kt("com/airtak/a/FooTest.kt",
                       "package com.airtak.a\nclass FooTest {\n    @Test fun a() {}\n    @Test fun b() {}\n}\n")
            f.write_xml("TEST-A.xml", _testsuite("com.airtak.a", [("com.airtak.a.FooTest", "a()", "")]))
            rc, out = _main_rc(f.root)
            self.assertEqual(1, rc, "declared-but-not-executed must be rc1")
            self.assertIn("DECLARED BUT NEVER EXECUTED", out)
            self.assertIn("com.airtak.a.FooTest.b", out)
        finally:
            f.destroy()

    def test_absent_results_dir_is_rc2(self):
        # No results directory at all -> the audit never parses results -> rc2.
        f = FixtureRepo.make()
        try:
            shutil.rmtree(f.results, ignore_errors=True)  # ensure ABSENT (not empty)
            self.assertFalse(os.path.isdir(f.results))
            rc, out = _main_rc(f.root)
            self.assertEqual(2, rc, "absent results dir must be rc2")
            self.assertIn("FAIL: no results directory", out)
        finally:
            f.destroy()

    def test_unmatched_annotation_is_rc1(self):
        # A @Test the parser cannot attribute to a method -> unresolvable -> rc1.
        f = FixtureRepo.make()
        try:
            # More than 8 lines between @Test and fun -> beyond lookahead -> unmatched.
            f.write_kt("com/airtak/a/DeepTest.kt",
                       "package com.airtak.a\nclass DeepTest {\n" +
                       "    @Test\n" + "    // filler\n" * 9 + "    fun tooFar() {}\n}\n")
            f.write_xml("TEST-A.xml", _testsuite("com.airtak.a", [("com.airtak.a.DeepTest", "tooFar()", "")]))
            rc, out = _main_rc(f.root)
            self.assertEqual(1, rc, "unresolvable @Test must be rc1")
            self.assertIn("could not resolve", out)
        finally:
            f.destroy()

    def test_extra_cases_warning_is_rc0(self):
        # Executed cases without a matching @Test declaration (parameterized/repeated/factory) are
        # informational only and MUST NOT be fatal. declared==0 but executed>0 stays rc0.
        f = FixtureRepo.make()
        try:
            f.write_xml("TEST-P.xml", _testsuite("com.airtak.p", [
                ("ParamTest", "case[0]", ""),
                ("ParamTest", "case[1]", ""),
            ]))
            rc, out = _main_rc(f.root)
            self.assertEqual(0, rc, "extra (executed-without-declaration) must not be fatal")
            self.assertIn("EXECUTED WITHOUT A MATCHING @Test DECLARATION", out)
        finally:
            f.destroy()


class PackageIdentity(unittest.TestCase):
    """Package-qualified identity: the fix that stops two packages with the same simple class+method
    from collapsing into one identity. This is the regression that the earlier KNOWN LIMITATION
    described; now it must be preserved.
    """

    def _bidir_fixture(self, alpha_runs, beta_runs):
        f = FixtureRepo.make()
        for pkg in ("alpha", "beta"):
            f.write_kt("com/airtak/%s/FooTest.kt" % pkg,
                       "package com.airtak.%s\nclass FooTest {\n    @Test fun same() {}\n}\n" % pkg)
        cases = []
        if alpha_runs:
            cases.append(("com.airtak.alpha.FooTest", "same()", ""))
        if beta_runs:
            cases.append(("com.airtak.beta.FooTest", "same()", ""))
        f.write_xml("TEST-A.xml", _testsuite("com.airtak.alpha", cases))
        return f

    def test_only_alpha_runs_must_fail_rc1(self):
        # alpha.FooTest.same declares + executes, beta.FooTest.same declares but never runs (silently
        # skipped). Before the fix both collapsed to (FooTest, same), the audit reported clean (rc0);
        # now the fully-qualified identity keeps beta's missing test visible -> it must FAIL rc1.
        f = self._bidir_fixture(alpha_runs=True, beta_runs=False)
        try:
            declared = f.declared()
            self.assertEqual({("com.airtak.alpha.FooTest", "same"),
                              ("com.airtak.beta.FooTest", "same")}, declared)
            rc, out = _main_rc(f.root)
            self.assertEqual(1, rc, "beta's missing test must be flagged (was masked by collision)")
            self.assertIn("com.airtak.beta.FooTest.same", out)
        finally:
            f.destroy()

    def test_both_packages_run_passes_rc0(self):
        # Both alpha and beta execute `same`; both declare it. No mismatch -> rc0.
        f = self._bidir_fixture(alpha_runs=True, beta_runs=True)
        try:
            rc, out = _main_rc(f.root)
            self.assertEqual(0, rc, "both packages run their test -> clean")
            self.assertIn("No declared @Test was silently skipped.", out)
        finally:
            f.destroy()

    def test_default_package_matches_simple_classname(self):
        # A file with NO package statement: declared keys on the simple class name, and JUnit's
        # default-package classname is also the bare simple name -> they match -> rc0.
        f = FixtureRepo.make()
        try:
            f.write_kt("FooTest.kt", "class FooTest {\n    @Test fun plain() {}\n}\n")
            f.write_xml("TEST-A.xml", _testsuite("np", [("FooTest", "plain()", "")]))
            rc, out = _main_rc(f.root)
            self.assertEqual(0, rc, "default package must match simple classname")
            self.assertIn("No declared @Test was silently skipped.", out)
        finally:
            f.destroy()

    def test_multiple_top_level_classes_same_file(self):
        # Two top-level test classes in ONE file, same package; both run -> each attributed under its
        # FQN -> rc0. (The owner-attribution walk attributes each @Test to the nearest preceding
        # *Test class, so FooTest and BarTest are kept separate.)
        f = FixtureRepo.make()
        try:
            f.write_kt("multi/MultiTest.kt", """\
package com.airtak.multi
class FooTest {
    @Test fun a() {}
}
class BarTest {
    @Test fun b() {}
}
""")
            f.write_xml("TEST-A.xml", _testsuite("com.airtak.multi", [
                ("com.airtak.multi.FooTest", "a()", ""),
                ("com.airtak.multi.BarTest", "b()", ""),
            ]))
            rc, out = _main_rc(f.root)
            self.assertEqual(0, rc, "multiple top-level classes in one file, both run -> clean")
        finally:
            f.destroy()

    def test_no_general_kotlin_parser_fqn_annotation_boundary(self):
        # BOUNDARY (reviewer): a fully-qualified `@org.junit.jupiter.api.Test` is NOT matched by the
        # `@Test\b` regex, so that method is neither counted as declared nor flagged unmatched. This
        # pins the documented limit: the parser handles `@Test` / `@Test(...)` forms, not
        # FQN-qualified annotation names. (No general Kotlin parser expansion requested.)
        f = FixtureRepo.make()
        try:
            f.write_kt("FqnTest.kt", "class FqnTest {\n    @org.junit.jupiter.api.Test fun f() {}\n}\n")
            declared, unmatched = audit.declared_tests(f.root)
            self.assertNotIn(("FqnTest", "f"), declared, "FQN @org.junit...Test is not matched")
            self.assertEqual([], unmatched, "FQN annotation is not unmatched either (unseen)")
        finally:
            f.destroy()


if __name__ == "__main__":
    unittest.main(verbosity=2)
