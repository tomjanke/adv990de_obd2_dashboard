#!/usr/bin/env python3
"""Testabdeckung als Markdown aus dem JaCoCo-XML-Bericht (`docs/qa/coverage.md`).

    python3 -I scripts/coverage_summary.py <coverageReport.xml> --mode combined|jvm [--out docs/qa/coverage.md]
        [--jvm-results DIR] [--device-results DIR] [--gradle-log DATEI] [--gradle app/build.gradle.kts] [--date JJJJ-MM-TT]

Den Aufruf macht `scripts/coverage.sh` (Gradle misst, dieses Skript schreibt). Es liest nur Dateien, braucht kein Gerät und kein Gradle und schreibt die
Ausgabe erst, wenn der Bericht gelesen und alles gerechnet ist (eine halbe oder fehlerhafte Messung überschreibt nie den letzten Stand).

Was gerechnet wird (Tests: `scripts/test_coverage_summary.py`):
- **Zeilenabdeckung** je Paket und gesamt = abgedeckte Zeilen ÷ alle Zeilen mit Code, **kaufmännisch auf eine Stelle gerundet** (45/80 = 56,3 %, nicht 56,2 %); dazu
  Zweige. Ohne Zeilen gibt es keine Prozentzahl („–“), nie 0.
- **Gesamt** ist der Zähler am Ende des Berichts, keine Summe über die Zeilen der Tabelle.
- **Schwächste Dateien:** ab `MIN_LINES_RANKED` Zeilen, die niedrigste Abdeckung zuerst, bei Gleichstand die mit mehr nicht abgedeckten Zeilen.
- **Testzahlen** aus den Protokollen des Laufs (Fehler und Fehlschläge zusammen); fehlende oder unlesbare Protokolle sind „unbekannt“, nie 0.
- **Nicht zugeordnete Klassen:** Gradle meldet Klassen, deren Bytecode zur Laufzeit ein anderer war als der im Bericht (Hilt ändert `@AndroidEntryPoint`-Klassen); sie zählen als
  nicht abgedeckt, das Skript führt sie auf.

Datenschutz: der Bericht enthält nur Paket- und Dateinamen und Zahlen, nichts Persönliches.
"""
from __future__ import annotations

import argparse
import os
import re
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from datetime import datetime, timezone

MIN_LINES_RANKED = 20  # kleinere Dateien kommen nicht in die Liste der schwächsten (eine Datei mit 5 Zeilen sagt nichts)
WEAKEST_LIMIT = 15
MODES = ("combined", "jvm")


@dataclass(frozen=True)
class Counter:
    missed: int
    covered: int

    @property
    def total(self) -> int:
        return self.missed + self.covered

    @property
    def percent(self):
        """Prozent mit einer Nachkommastelle, kaufmännisch gerundet (ganzzahlig gerechnet: Gleitkomma rundet 56,25 auf 56,2); `None` ohne Zeilen."""
        if self.total == 0:
            return None
        tenths = (2000 * self.covered + self.total) // (2 * self.total)
        return tenths / 10


@dataclass(frozen=True)
class Package:
    name: str  # mit Punkten: de.adv990de.obd2.data
    lines: Counter
    branches: Counter


@dataclass(frozen=True)
class SourceFile:
    package: str  # mit Punkten
    name: str
    lines: Counter
    branches: Counter


@dataclass(frozen=True)
class Report:
    lines: Counter
    branches: Counter
    packages: list
    files: list


@dataclass(frozen=True)
class TestCounts:
    tests: int
    failed: int  # Fehler und Fehlschläge zusammen
    skipped: int = 0


def format_percent(value) -> str:
    return "–" if value is None else f"{value:.1f}".replace(".", ",") + " %"


def _counter(element, kind: str) -> Counter:
    for child in element.findall("counter"):
        if child.get("type") == kind:
            return Counter(missed=int(child.get("missed")), covered=int(child.get("covered")))
    return Counter(0, 0)


def parse_report(xml_text: str) -> Report:
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError as e:
        raise ValueError(f"der JaCoCo-Bericht ist kein lesbares XML: {e}") from e
    if root.tag != "report":
        raise ValueError(f"kein JaCoCo-Bericht (Wurzelelement '{root.tag}')")
    packages, files = [], []
    for package in root.findall("package"):
        name = package.get("name", "").replace("/", ".")
        lines = _counter(package, "LINE")
        if lines.total == 0:
            continue
        packages.append(Package(name, lines, _counter(package, "BRANCH")))
        for source in package.findall("sourcefile"):
            files.append(SourceFile(name, source.get("name", ""), _counter(source, "LINE"), _counter(source, "BRANCH")))
    return Report(_counter(root, "LINE"), _counter(root, "BRANCH"), packages, files)


def weakest_files(report: Report, min_lines: int = MIN_LINES_RANKED, limit: int = WEAKEST_LIMIT) -> list:
    ranked = [f for f in report.files if f.lines.total >= min_lines]
    ranked.sort(key=lambda f: (f.lines.percent, -f.lines.missed))
    return ranked[:limit]


def count_tests(directory):
    """Summe aller Testprotokolle (`*.xml`) unter [directory]; `None`, wenn es das Verzeichnis oder Protokolle darin nicht gibt."""
    if not directory or not os.path.isdir(directory):
        return None
    tests = failed = skipped = 0
    found = False
    for folder, _, names in os.walk(directory):
        for name in sorted(names):
            if not name.endswith(".xml"):
                continue
            try:
                root = ET.parse(os.path.join(folder, name)).getroot()
            except ET.ParseError as e:
                raise ValueError(f"Testprotokoll {name} ist nicht lesbar: {e}") from e
            suites = root.findall("testsuite") if root.tag == "testsuites" else [root]
            for suite in suites:
                found = True
                tests += int(suite.get("tests", 0))
                failed += int(suite.get("failures", 0)) + int(suite.get("errors", 0))
                skipped += int(suite.get("skipped", 0))
    return TestCounts(tests, failed, skipped) if found else None


def parse_mismatches(log_text: str) -> list:
    """Klassen, die Gradle als „passt nicht zu den Ausführungsdaten“ gemeldet hat, einmal je Klasse, sortiert, mit Punkten."""
    names = set(re.findall(r"Execution data for class (\S+) does not match", log_text))
    return sorted(n.replace("/", ".") for n in names)


def read_version(gradle_text: str):
    name = re.search(r'val appVersionName = "([^"]+)"', gradle_text)
    code = re.search(r"val appVersionCode = (\d+)", gradle_text)
    if not name or not code:
        raise ValueError("appVersionName/appVersionCode stehen nicht in der Build-Datei")
    return name.group(1), code.group(1)


def _lines_text(c: Counter) -> str:
    return f"{c.covered} / {c.total}"


def _tests_text(label: str, counts) -> str:
    if counts is None:
        return f"- {label}: nicht gelaufen oder ohne Protokoll"
    return f"- {label}: {counts.tests} ({counts.failed} fehlgeschlagen, {counts.skipped} übersprungen)"


def render(report: Report, mode: str, version, date: str, jvm_tests, device_tests, mismatches=()) -> str:
    name, code = version
    out = []
    out.append("# Testabdeckung")
    out.append("")
    out.append("> Erzeugt von `scripts/coverage.sh` — nicht von Hand ändern. Neu messen: `scripts/coverage.sh` (JVM- und Geräte-Tests, rund 17 Minuten, Telefon wach und entsperrt) "
               "oder `scripts/coverage.sh --jvm-only` (wenige Minuten, nur die JVM-Tests).")
    out.append("")
    failed = sum(c.failed for c in (jvm_tests, device_tests) if c is not None)
    if failed:
        out.append(f"> **Achtung:** {failed} {'Test' if failed == 1 else 'Tests'} fehlgeschlagen — die Zahl ist unvollständig und nicht vergleichbar.")
        out.append("")
    method = "JVM- und Geräte-Tests (Debug-Build, JaCoCo)" if mode == "combined" else "nur JVM-Tests (Debug-Build, JaCoCo)"
    out.append(f"**Stand:** {date} · **Version:** {name} (versionCode {code}) · **Messweise:** {method}")
    out.append("")
    if mode == "jvm":
        out.append("> **Untergrenze:** es zählen nur die JVM-Tests. Die Oberfläche (Compose) läuft fast nur über die Geräte-Tests; die Zahl liegt deshalb deutlich unter der echten Abdeckung.")
        out.append("")
    out.append(f"**Gesamt: {format_percent(report.lines.percent)} der Zeilen ({report.lines.covered} von {report.lines.total} Zeilen)**, "
               f"{format_percent(report.branches.percent)} der Zweige ({report.branches.covered} von {report.branches.total}).")
    out.append("")
    out.append("Tests im Lauf:")
    out.append("")
    out.append(_tests_text("JVM-Tests", jvm_tests))
    out.append(_tests_text("Geräte-Tests", device_tests))
    out.append("")
    out.append("## Je Paket")
    out.append("")
    out.append("| Paket | Zeilen | Zeilen % | Zweige % |")
    out.append("|---|---|---|---|")
    for p in sorted(report.packages, key=lambda p: p.name):
        out.append(f"| `{p.name}` | {_lines_text(p.lines)} | {format_percent(p.lines.percent)} | {format_percent(p.branches.percent)} |")
    out.append("")
    weakest = weakest_files(report)
    out.append(f"## Dateien mit der niedrigsten Abdeckung (ab {MIN_LINES_RANKED} Zeilen)")
    out.append("")
    out.append("| Datei | Zeilen | Zeilen % |")
    out.append("|---|---|---|")
    for f in weakest:
        path = f.package.replace(".", "/") + "/" + f.name
        out.append(f"| `{path}` | {_lines_text(f.lines)} | {format_percent(f.lines.percent)} |")
    out.append("")
    if mismatches:
        out.append("## Klassen, die nicht zugeordnet werden konnten")
        out.append("")
        out.append("Der Bytecode dieser Klassen war zur Laufzeit ein anderer als der im Bericht (Hilt ändert `@AndroidEntryPoint`-Klassen beim Bauen). Sie sind nicht zugeordnet und zählen als nicht abgedeckt — "
                   "die Zahl oben ist um ihren Anteil zu niedrig:")
        out.append("")
        out.extend(f"- `{m}`" for m in mismatches)
        out.append("")
    out.append("## Was die Zahl aussagt und was nicht")
    out.append("")
    out.append("- Es ist eine **Zeilenabdeckung**: sie sagt, welche Zeilen ein Test durchläuft, nicht, ob er sie auch prüft. Für die Qualität der Tests sind die Mutationsproben bei den "
               "Änderungen die bessere Stichprobe (sie stehen in `CLAUDE.md` beim jeweiligen Abschnitt).")
    out.append("- **Nicht gemessen** ist erzeugter Code: Hilt/Dagger, Room (`*_Impl`), `R` und `BuildConfig`. Er würde die Zahl verzerren. Die Ausnahmen stehen in `app/build.gradle.kts` (`generatedCode`).")
    out.append("- **Compose-Lambdas** (`ComposableSingletons`) sind dabei: ihre Rümpfe sind der echte Quelltext der Bildschirme. Compose erzeugt dazu viele Zweige, deshalb ist die **Zweigabdeckung** "
               "weniger aussagekräftig als die der Zeilen.")
    out.append("- Gemessen wird der **Debug-Build**; Tests laufen auf dem Testgerät in einer Ausrichtung (Geräte-Tests, die eine bestimmte Ausrichtung brauchen, erzwingen sie selbst).")
    out.append("- Ein Stand ist eine **Momentaufnahme**: er gilt für die genannte Version und wird nur auf Zuruf neu gemessen, nicht bei jedem Release.")
    out.append("")
    return "\n".join(out)


def _read(path: str) -> str:
    with open(path, encoding="utf-8") as f:
        return f.read()


def main(argv=None) -> int:
    here = os.path.dirname(os.path.abspath(__file__))
    parser = argparse.ArgumentParser(description="Testabdeckung als Markdown aus dem JaCoCo-XML")
    parser.add_argument("report", help="coverageReport.xml")
    parser.add_argument("--mode", required=True, choices=MODES)
    parser.add_argument("--out", help="Zieldatei (sonst Standardausgabe)")
    parser.add_argument("--jvm-results", help="Verzeichnis mit den Protokollen der JVM-Tests")
    parser.add_argument("--device-results", help="Verzeichnis mit den Protokollen der Geräte-Tests")
    parser.add_argument("--gradle-log", help="Ausgabe des Gradle-Laufs (für Klassen ohne Zuordnung)")
    parser.add_argument("--gradle", default=os.path.join(here, "..", "app", "build.gradle.kts"), help="Build-Datei mit der Version")
    parser.add_argument("--date", default=datetime.now(timezone.utc).date().isoformat())
    args = parser.parse_args(argv)
    try:
        if not os.path.isfile(args.report):
            raise ValueError(f"Bericht nicht gefunden: {args.report}")
        report = parse_report(_read(args.report))
        version = read_version(_read(args.gradle))
        jvm_tests = count_tests(args.jvm_results)
        device_tests = count_tests(args.device_results) if args.mode == "combined" else None
        mismatches = parse_mismatches(_read(args.gradle_log)) if args.gradle_log and os.path.isfile(args.gradle_log) else []
        text = render(report, args.mode, version, args.date, jvm_tests, device_tests, mismatches)
    except (ValueError, OSError) as e:
        print(f"Fehler: {e}", file=sys.stderr)
        return 2
    if args.out:
        tmp = args.out + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            f.write(text)
        os.replace(tmp, args.out)
    else:
        sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
