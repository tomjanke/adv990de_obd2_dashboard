#!/usr/bin/env python3
"""Tests von scripts/coverage_summary.py (ohne Gerät, ohne Gradle, ohne echte Messung).

    python3 -I scripts/test_coverage_summary.py

Der JVM-Test `CoverageSetupTest` führt diese Datei aus. Die Beispieldaten sind erfunden und kurz: ein JaCoCo-XML im Aufbau der echten Berichte (Paket → Quelldatei →
Zähler) und ein paar Testprotokolle. Jede Rechnung des Skripts (Prozent, Rangliste der schwächsten Dateien, Testzahlen, Version) hat hier einen Fall, damit eine
verschobene Grenze auffällt — nur dann bleibt `docs/qa/coverage.md` zwischen zwei Messungen vergleichbar.
"""
import importlib.util
import os
import sys
import tempfile
import unittest

_HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location("coverage_summary", os.path.join(_HERE, "coverage_summary.py"))
cs = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = cs  # Dataclasses (Python 3.9) schlagen ihr Modul darin nach
_spec.loader.exec_module(cs)

# Aufbau wie ein echter JaCoCo-Bericht (mit DOCTYPE): Zähler stehen je Quelldatei, je Paket und ganz am Ende für alles.
REPORT = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
<report name="app">
 <package name="de/adv990de/obd2/data">
  <class name="de/adv990de/obd2/data/Repo" sourcefilename="Repo.kt"/>
  <sourcefile name="Repo.kt">
   <line nr="1" mi="0" ci="3"/>
   <counter type="INSTRUCTION" missed="2" covered="38"/>
   <counter type="BRANCH" missed="2" covered="6"/>
   <counter type="LINE" missed="5" covered="45"/>
  </sourcefile>
  <sourcefile name="Cache.kt">
   <counter type="LINE" missed="30" covered="0"/>
  </sourcefile>
  <counter type="BRANCH" missed="2" covered="6"/>
  <counter type="LINE" missed="35" covered="45"/>
 </package>
 <package name="de/adv990de/obd2/ui/dashboard">
  <sourcefile name="Screen.kt">
   <counter type="BRANCH" missed="40" covered="10"/>
   <counter type="LINE" missed="60" covered="40"/>
  </sourcefile>
  <sourcefile name="Tiny.kt">
   <counter type="LINE" missed="5" covered="0"/>
  </sourcefile>
  <counter type="BRANCH" missed="40" covered="10"/>
  <counter type="LINE" missed="65" covered="40"/>
 </package>
 <package name="de/adv990de/obd2/empty"/>
 <counter type="INSTRUCTION" missed="2" covered="38"/>
 <counter type="BRANCH" missed="42" covered="16"/>
 <counter type="LINE" missed="100" covered="85"/>
</report>
"""

# Die Meldung, die Gradle schreibt, wenn der Bytecode zur Laufzeit ein anderer war als der im Bericht.
MISMATCH_LOG = (
    "[ant:jacocoReport] Classes in bundle 'app' do not match with execution data. For report generation the same class files must be used as at runtime.\n"
    "[ant:jacocoReport] Execution data for class de/adv990de/obd2/ObdForegroundService does not match.\n"
    "[ant:jacocoReport] Execution data for class de/adv990de/obd2/MainActivity does not match.\n"
    "[ant:jacocoReport] Execution data for class de/adv990de/obd2/ObdForegroundService does not match.\n"
    "BUILD SUCCESSFUL in 22s\n"
)


def write(path, text):
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def suite_xml(tests, failures=0, errors=0, skipped=0):
    return f'<?xml version="1.0" encoding="UTF-8"?><testsuite name="x" tests="{tests}" skipped="{skipped}" failures="{failures}" errors="{errors}"></testsuite>'


class PercentTest(unittest.TestCase):
    def test_percent_is_covered_over_all_lines_with_one_decimal(self):
        self.assertEqual(80.0, cs.Counter(missed=2, covered=8).percent)
        self.assertEqual(33.3, cs.Counter(missed=2, covered=1).percent)

    def test_a_half_rounds_up_like_everywhere_in_the_docs_not_to_even(self):
        self.assertEqual(56.3, cs.Counter(missed=35, covered=45).percent)  # 56,25 %

    def test_no_lines_means_no_percentage_not_zero_and_not_a_crash(self):
        self.assertIsNone(cs.Counter(missed=0, covered=0).percent)

    def test_the_percentage_is_written_with_a_comma_like_the_rest_of_the_docs(self):
        self.assertEqual("80,0 %", cs.format_percent(80.0))
        self.assertEqual("–", cs.format_percent(None))


class ParseTest(unittest.TestCase):
    def setUp(self):
        self.report = cs.parse_report(REPORT)

    def test_the_total_is_the_counter_at_the_end_of_the_report_not_a_sum_of_something_else(self):
        self.assertEqual(cs.Counter(100, 85), self.report.lines)
        self.assertEqual(cs.Counter(42, 16), self.report.branches)

    def test_packages_use_dots_and_carry_their_own_counters(self):
        names = [p.name for p in self.report.packages]
        self.assertEqual(["de.adv990de.obd2.data", "de.adv990de.obd2.ui.dashboard"], names)
        data = self.report.packages[0]
        self.assertEqual(cs.Counter(35, 45), data.lines)
        self.assertEqual(cs.Counter(2, 6), data.branches)

    def test_a_package_without_lines_is_left_out_instead_of_showing_a_zero_row(self):
        self.assertNotIn("de.adv990de.obd2.empty", [p.name for p in self.report.packages])

    def test_source_files_carry_their_package_and_line_counter(self):
        files = {(f.package, f.name): f.lines for f in self.report.files}
        self.assertEqual(cs.Counter(5, 45), files[("de.adv990de.obd2.data", "Repo.kt")])
        self.assertEqual(cs.Counter(30, 0), files[("de.adv990de.obd2.data", "Cache.kt")])
        self.assertEqual(4, len(files))

    def test_a_file_without_a_branch_counter_has_zero_branches(self):
        cache = next(f for f in self.report.files if f.name == "Cache.kt")
        self.assertEqual(cs.Counter(0, 0), cache.branches)

    def test_a_broken_report_is_an_error_not_an_empty_result(self):
        with self.assertRaises(ValueError):
            cs.parse_report("<report><package name='x'>")
        with self.assertRaises(ValueError):
            cs.parse_report("<notareport/>")


class WeakestFilesTest(unittest.TestCase):
    def test_files_under_the_minimum_size_are_not_ranked(self):
        weakest = cs.weakest_files(cs.parse_report(REPORT), min_lines=20, limit=10)
        self.assertNotIn("Tiny.kt", [f.name for f in weakest])

    def test_the_lowest_percentage_comes_first_then_the_more_missed_lines(self):
        weakest = cs.weakest_files(cs.parse_report(REPORT), min_lines=20, limit=10)
        self.assertEqual(["Cache.kt", "Screen.kt", "Repo.kt"], [f.name for f in weakest])

    def test_the_list_is_cut_at_the_limit(self):
        self.assertEqual(1, len(cs.weakest_files(cs.parse_report(REPORT), min_lines=20, limit=1)))


class TestCountsTest(unittest.TestCase):
    def test_sums_every_suite_in_every_file_below_the_directory(self):
        with tempfile.TemporaryDirectory() as d:
            os.makedirs(os.path.join(d, "sub"))
            write(os.path.join(d, "TEST-a.xml"), suite_xml(10, failures=1, skipped=2))
            write(os.path.join(d, "sub", "TEST-b.xml"), suite_xml(5, errors=3))
            write(os.path.join(d, "notes.txt"), "ignored")
            self.assertEqual(cs.TestCounts(tests=15, failed=4, skipped=2), cs.count_tests(d))

    def test_a_directory_that_does_not_exist_is_unknown_not_zero(self):
        self.assertIsNone(cs.count_tests("/nonexistent/for/sure"))
        self.assertIsNone(cs.count_tests(None))

    def test_a_directory_without_results_is_unknown_as_well(self):
        with tempfile.TemporaryDirectory() as d:
            self.assertIsNone(cs.count_tests(d))

    def test_a_wrapping_testsuites_element_is_not_counted_twice(self):
        with tempfile.TemporaryDirectory() as d:
            write(os.path.join(d, "TEST-a.xml"), 
                '<testsuites><testsuite tests="4" failures="0" errors="0" skipped="0"/><testsuite tests="6" failures="1" errors="0" skipped="0"/></testsuites>'
            )
            self.assertEqual(cs.TestCounts(tests=10, failed=1, skipped=0), cs.count_tests(d))

    def test_an_unreadable_result_file_is_an_error_not_a_silently_smaller_count(self):
        with tempfile.TemporaryDirectory() as d:
            write(os.path.join(d, "TEST-a.xml"), "<testsuite tests=")
            with self.assertRaises(ValueError):
                cs.count_tests(d)


class MismatchTest(unittest.TestCase):
    def test_the_classes_gradle_could_not_match_are_listed_once_each_with_dots(self):
        self.assertEqual(["de.adv990de.obd2.MainActivity", "de.adv990de.obd2.ObdForegroundService"], cs.parse_mismatches(MISMATCH_LOG))

    def test_a_clean_log_has_none(self):
        self.assertEqual([], cs.parse_mismatches("BUILD SUCCESSFUL in 22s"))


class VersionTest(unittest.TestCase):
    def test_the_version_is_read_from_the_build_file(self):
        gradle = '    val appVersionCode = 144\n    val appVersionName = "1.44.6"\n'
        self.assertEqual(("1.44.6", "144"), cs.read_version(gradle))

    def test_a_build_file_without_a_version_is_an_error(self):
        with self.assertRaises(ValueError):
            cs.read_version("android { }")


def render(**overrides):
    args = dict(
        report=cs.parse_report(REPORT), mode="combined", version=("1.44.6", "144"), date="2026-10-26",
        jvm_tests=cs.TestCounts(2901, 0, 0), device_tests=cs.TestCounts(788, 0, 0), mismatches=(),
    )
    args.update(overrides)
    return cs.render(**args)


class RenderTest(unittest.TestCase):
    def test_the_total_and_the_run_are_named_at_the_top(self):
        text = render()
        self.assertIn("2026-10-26", text)
        self.assertIn("1.44.6", text)
        self.assertIn("85 von 185 Zeilen", text)
        self.assertIn("45,9 %", text)  # 85 / 185
        self.assertIn("2901", text)
        self.assertIn("788", text)

    def test_the_table_has_a_row_per_package_with_lines_and_branches(self):
        text = render()
        self.assertIn("| `de.adv990de.obd2.data` | 45 / 80 | 56,3 % | 75,0 % |", text)  # 45/80 Zeilen, 6/8 Zweige
        self.assertIn("| `de.adv990de.obd2.ui.dashboard` | 40 / 105 | 38,1 % | 20,0 % |", text)

    def test_the_weakest_files_are_listed_with_their_package_path(self):
        text = render()
        self.assertIn("`de/adv990de/obd2/data/Cache.kt`", text)
        self.assertLess(text.index("Cache.kt"), text.index("Screen.kt"))

    def test_a_jvm_only_run_says_it_is_a_lower_bound(self):
        text = render(mode="jvm", device_tests=None)
        self.assertIn("Untergrenze", text)
        self.assertIn("nicht gelaufen", text)  # die Geräte-Tests

    def test_a_combined_run_does_not_call_itself_a_lower_bound(self):
        self.assertNotIn("Untergrenze", render())

    def test_failing_tests_put_a_warning_at_the_top(self):
        text = render(jvm_tests=cs.TestCounts(2901, 3, 0))
        self.assertIn("Achtung", text)
        self.assertIn("3 Tests fehlgeschlagen", text)

    def test_one_failing_test_is_written_in_the_singular(self):
        self.assertIn("1 Test fehlgeschlagen", render(jvm_tests=cs.TestCounts(2901, 1, 0)))

    def test_green_tests_have_no_warning(self):
        self.assertNotIn("Achtung", render())

    def test_the_limits_are_always_written_down(self):
        text = render()
        for expected in ("Zeilenabdeckung", "nicht, ob", "Hilt", "Compose", "scripts/coverage.sh"):
            self.assertIn(expected, text)

    def test_classes_without_a_match_are_named_with_what_it_means(self):
        text = render(mismatches=["de.adv990de.obd2.MainActivity"])
        self.assertIn("`de.adv990de.obd2.MainActivity`", text)
        self.assertIn("nicht zugeordnet", text)

    def test_there_is_no_such_section_without_mismatches(self):
        self.assertNotIn("nicht zugeordnet", render())


class MainTest(unittest.TestCase):
    def run_main(self, d, extra=()):
        xml = os.path.join(d, "report.xml")
        write(xml, REPORT)
        gradle = os.path.join(d, "build.gradle.kts")
        write(gradle, 'val appVersionCode = 144\nval appVersionName = "1.44.6"\n')
        out = os.path.join(d, "coverage.md")
        code = cs.main([xml, "--mode", "jvm", "--gradle", gradle, "--date", "2026-10-26", "--out", out, *extra])
        return code, out

    def test_writes_the_markdown_file_on_success(self):
        with tempfile.TemporaryDirectory() as d:
            code, out = self.run_main(d)
            self.assertEqual(0, code)
            self.assertIn("1.44.6", read(out))

    def test_the_gradle_log_adds_the_classes_without_a_match(self):
        with tempfile.TemporaryDirectory() as d:
            log = os.path.join(d, "gradle.log")
            write(log, MISMATCH_LOG)
            code, out = self.run_main(d, extra=["--gradle-log", log])
            self.assertEqual(0, code)
            self.assertIn("`de.adv990de.obd2.MainActivity`", read(out))

    def test_a_missing_report_is_an_error_and_writes_no_file(self):
        with tempfile.TemporaryDirectory() as d:
            out = os.path.join(d, "coverage.md")
            code = cs.main([os.path.join(d, "missing.xml"), "--mode", "jvm", "--out", out])
            self.assertEqual(2, code)
            self.assertFalse(os.path.exists(out))

    def test_a_broken_report_leaves_an_existing_file_untouched(self):
        with tempfile.TemporaryDirectory() as d:
            out = os.path.join(d, "coverage.md")
            write(out, "alter Stand")
            xml = os.path.join(d, "report.xml")
            write(xml, "<report>")
            self.assertEqual(2, cs.main([xml, "--mode", "jvm", "--out", out]))
            self.assertEqual("alter Stand", read(out))

    def test_an_unknown_mode_is_refused(self):
        with self.assertRaises(SystemExit):
            cs.main(["x.xml", "--mode", "everything"])


if __name__ == "__main__":
    unittest.main()
