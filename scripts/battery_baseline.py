#!/usr/bin/env python3
"""Batterie-Kennwerte je Fahrt aus der CSV, die die App exportiert (Idee F12, `docs/ideas/2026-10-17-adventure-enduro-funktionsideen.md`).

    python3 -I scripts/battery_baseline.py <session_all_….csv>

Die CSV entsteht in der App: Fahrten → Export-Symbol → Speichern oder Teilen. Das Skript liest sie nur, legt keine Kopie ab und braucht kein
Geraet. Es misst die Aufzeichnung, nicht die App — die Rechnung ist absichtlich unabhaengig vom Kotlin-Code.

**Warum die Definitionen hier stehen und getestet sind (`scripts/test_battery_baseline.py`):** ein Trend ueber Monate ist nur dann einer,
wenn jede Auswertung dieselbe Rechnung benutzt. Wer eine Konstante aendert, vergleicht danach nichts mehr mit der Basis vom 2026-10-19
(`docs/qa/2026-10-19-battery-baseline.md`) — dann die Basis mit der neuen Fassung neu rechnen.

Definitionen (die Konstanten stehen unten):
- **Zyklus:** aufeinanderfolgende CSV-Zeilen mit hoechstens `CYCLE_GAP_MS` Abstand. Seine Zeit ist die der letzten Zeile. Die Datei hat keine
  Zyklus-Kennung; der Takt der App (0,8 bis 1,5 s) liegt klar ueber dem Abstand der Zeilen eines Zyklus (unter 0,4 s).
- **Zuendung an:** Median der Spannung aller Zyklen **vor** dem ersten Zyklus mit Drehzahl ab `RUNNING_MIN_RPM`, ohne die letzten
  `REST_EXCLUDE_BEFORE_START_MS` davor (das ist das Orgeln, nicht der Ruhezustand).
- **Anlasser-Einbruch:** die tiefste Spannung im Fenster `DIP_WINDOW_MS` vor und nach dem ersten Lauf-Zyklus — aber nur, wenn sie mehr als
  `DIP_MIN_DEPTH_V` unter „Zuendung an“ liegt. Es ist die tiefste **erfasste** Probe: bei einem Takt von rund einer Sekunde wird das Orgeln
  zufaellig getroffen, der Wert ist keine Messung der Anlasser-Spitze und von Fahrt zu Fahrt nicht vergleichbar.
- **Laufend:** Median der Zyklen mit Drehzahl ab `RUNNING_MIN_RPM`, ab `RUN_SETTLE_MS` nach dem ersten Lauf-Zyklus.
- **Abstellen:** der erste Zyklus nach dem Lauf mit Drehzahl unter `RUNNING_MIN_RPM` und die Probe, die `STOP_LATE_AT_MS` danach am naechsten
  liegt (hoechstens `STOP_LATE_TOLERANCE_MS` daneben).
- **Standzeit:** Abstand zwischen dem Ende der Fahrt mit der naechstkleineren Id und dem Beginn dieser Fahrt. Wurde eine Fahrt dazwischen
  geloescht, ist die Standzeit zu lang.
- **0 V** heisst „keine Antwort“ und ist kein Wert.

Datenschutz: die Fahrtzeiten sind persoenliche Daten. Der Bericht nennt je Fahrt nur das Datum (UTC), nie die Uhrzeit; die CSV selbst gehoert nicht ins
Repository.

Grenzen: die Spannung des Steuergeraets ist nicht die Klemmenspannung; ohne Spannungs-Kachel fragt die App sie seit 1.36.0 nur in jedem
4. Zyklus ab (dann wird der Einbruch fast nie getroffen); eine Aussage zur Batteriegesundheit braucht ein Multimeter als Gegenprobe.
"""
from __future__ import annotations

import math
import os
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from statistics import median
from typing import Callable, Dict, Iterable, List, Optional, Tuple

PID_RPM = "010C"
PID_COOLANT = "0105"
PID_VOLTAGE = "0142"

CYCLE_GAP_MS = 450
RUNNING_MIN_RPM = 500.0
REST_EXCLUDE_BEFORE_START_MS = 3_000
DIP_WINDOW_MS = 3_000
DIP_MIN_DEPTH_V = 0.5
RUN_SETTLE_MS = 15_000
STOP_LATE_AT_MS = 15_000
STOP_LATE_TOLERANCE_MS = 2_000

Row = Tuple[int, str, float]


@dataclass
class Cycle:
    at_ms: int  # Zeitstempel der letzten Zeile des Zyklus
    first_ms: int  # Zeitstempel der ersten Zeile
    rpm: Optional[float] = None
    volt: Optional[float] = None
    coolant: Optional[float] = None


@dataclass
class TripResult:
    session: int
    start_ms: int
    end_ms: int
    cycles: int
    voltage_samples: int
    kind: str  # started | never_ran | running_at_start | no_voltage
    coolant_start: Optional[float] = None
    rest_v: Optional[float] = None
    rest_n: int = 0
    dip_v: Optional[float] = None
    dip_dt_s: Optional[float] = None
    run_v: Optional[float] = None
    run_n: int = 0
    stop_first_v: Optional[float] = None
    stop_after15_v: Optional[float] = None
    idle_h: Optional[float] = None

    @property
    def duration_s(self) -> float:
        return (self.end_ms - self.start_ms) / 1000


def parse_csv(lines: Iterable[str]) -> Dict[int, List[Row]]:
    """Zeilen je Fahrt in Dateireihenfolge (die App schreibt `ORDER BY id`). Kopfzeile, kaputte Zeilen und nicht endliche Werte entfallen.
    Eine Datei ohne Spalte `session` (bis 1.33.1) ist eine einzelne Fahrt (Id 0)."""
    sessions: Dict[int, List[Row]] = {}
    for line in lines:
        p = line.strip().split(",")
        if len(p) < 5 or not p[0].isdigit():
            continue
        try:
            value = float(p[2])
            session = int(p[5]) if len(p) >= 6 else 0
        except ValueError:
            continue
        if not math.isfinite(value):
            continue
        sessions.setdefault(session, []).append((int(p[0]), p[1], value))
    return sessions


def group_cycles(rows: List[Row]) -> List[Cycle]:
    cycles: List[Cycle] = []
    current: Optional[Cycle] = None
    last_ts: Optional[int] = None
    for ts, pid, value in rows:
        if current is None or last_ts is None or abs(ts - last_ts) > CYCLE_GAP_MS:
            current = Cycle(at_ms=ts, first_ms=ts)
            cycles.append(current)
        current.at_ms = ts
        last_ts = ts
        if pid == PID_RPM and current.rpm is None:
            current.rpm = value
        elif pid == PID_VOLTAGE and current.volt is None and value > 0:
            current.volt = value
        elif pid == PID_COOLANT and current.coolant is None:
            current.coolant = value
    return cycles


def _running(c: Cycle) -> bool:
    return c.rpm is not None and c.rpm >= RUNNING_MIN_RPM


def _median(values: List[float]) -> Optional[float]:
    return median(values) if values else None


def analyse_session(session: int, cycles: List[Cycle]) -> TripResult:
    start = cycles[0].first_ms
    end = cycles[-1].at_ms
    volts = [c for c in cycles if c.volt is not None]
    coolant_start = next((c.coolant for c in cycles if c.coolant is not None), None)
    result = TripResult(session, start, end, len(cycles), len(volts), "no_voltage", coolant_start=coolant_start)
    if not volts:
        return result
    first_run = next((i for i, c in enumerate(cycles) if _running(c)), None)
    if first_run is None:
        result.kind = "never_ran"
        result.rest_v = _median([c.volt for c in volts])
        result.rest_n = len(volts)
        return result
    t_run = cycles[first_run].at_ms
    run = [c.volt for c in cycles if c.volt is not None and _running(c) and c.at_ms >= t_run + RUN_SETTLE_MS]
    result.run_v, result.run_n = _median(run), len(run)
    if first_run == 0:
        result.kind = "running_at_start"
        return result
    result.kind = "started"
    rest = [c.volt for c in cycles if c.volt is not None and c.at_ms < t_run - REST_EXCLUDE_BEFORE_START_MS]
    result.rest_v, result.rest_n = _median(rest), len(rest)
    window = [c for c in cycles if c.volt is not None and t_run - DIP_WINDOW_MS <= c.at_ms <= t_run + DIP_WINDOW_MS]
    if window and result.rest_v is not None:
        deepest = min(window, key=lambda c: c.volt)
        if deepest.volt < result.rest_v - DIP_MIN_DEPTH_V:
            result.dip_v = deepest.volt
            result.dip_dt_s = (deepest.at_ms - t_run) / 1000
    stop = next((c for c in cycles[first_run:] if c.rpm is not None and c.rpm < RUNNING_MIN_RPM), None)
    if stop is not None:
        result.stop_first_v = stop.volt
        target = stop.at_ms + STOP_LATE_AT_MS
        late = [c for c in cycles if c.volt is not None and abs(c.at_ms - target) <= STOP_LATE_TOLERANCE_MS]
        if late:
            result.stop_after15_v = min(late, key=lambda c: (abs(c.at_ms - target), c.at_ms)).volt
    return result


def analyse_all(sessions: Dict[int, List[Row]]) -> List[TripResult]:
    results: List[TripResult] = []
    previous_end: Optional[int] = None
    for sid in sorted(sessions):
        cycles = group_cycles(sessions[sid])
        if not cycles:
            continue
        result = analyse_session(sid, cycles)
        if previous_end is not None and result.start_ms >= previous_end:
            result.idle_h = (result.start_ms - previous_end) / 3_600_000
        previous_end = result.end_ms
        results.append(result)
    return results


def _num(v: Optional[float], width: int = 5, decimals: int = 1) -> str:
    return "–".rjust(width) if v is None else f"{v:{width}.{decimals}f}"


def _idle(hours: Optional[float]) -> str:
    if hours is None:
        return ""
    return f"{hours * 60:.0f} min" if hours < 1 else f"{hours:.1f} h"


def _range(values: List[float]) -> str:
    return "–" if not values else f"{min(values):.1f} bis {max(values):.1f} V"


def format_report(results: List[TripResult]) -> str:
    if not results:
        return "keine Fahrten in der Datei"
    lines = [
        "Fahrt  Datum    Dauer  Zykl.  Spannung  Kühl.  Zündung an (n)   Anlasser-Einbruch (zum Lauf)  laufend (n)    Abstellen: erste / nach 15 s  Standzeit",
        "                  [s]            -werte  [°C]       [V]                 [V]                      [V]                    [V]                  davor",
    ]
    for r in results:
        begin = datetime.fromtimestamp(r.start_ms / 1000, timezone.utc).strftime("%d.%m.")
        head = f"{r.session:5}  {begin}  {r.duration_s:6.0f}  {r.cycles:5}  {r.voltage_samples:8}  {_num(r.coolant_start, 5, 0)}"
        if r.kind == "no_voltage":
            lines.append(f"{head}  keine Spannung aufgezeichnet")
            continue
        if r.kind == "never_ran":
            lines.append(f"{head}  {_num(r.rest_v)} ({r.rest_n:3})   Motor nie gelaufen")
            continue
        if r.kind == "running_at_start":
            lines.append(f"{head}  Start nicht aufgezeichnet (Motor lief schon)       {_num(r.run_v)} ({r.run_n:4})")
            continue
        rest = f"{_num(r.rest_v)} ({r.rest_n:3})"
        dip = f"{_num(r.dip_v)} ({r.dip_dt_s:+.1f} s)" if r.dip_v is not None else "nicht erfasst"
        run = f"{_num(r.run_v)} ({r.run_n:4})"
        stop = f"{_num(r.stop_first_v)} / {_num(r.stop_after15_v)}" if r.stop_first_v is not None or r.stop_after15_v is not None else ""
        idle = _idle(r.idle_h)
        lines.append(f"{head}  {rest}   {dip:25}  {run}   {stop:22}  {idle}")
    started = [r for r in results if r.kind == "started"]
    dips = [r.dip_v for r in started if r.dip_v is not None]
    lines += [
        "",
        f"Fahrten: {len(results)}, davon mit aufgezeichnetem Start: {len(started)}",
        f"Zündung an: {_range([r.rest_v for r in started if r.rest_v is not None])}",
        f"laufend: {_range([r.run_v for r in results if r.run_v is not None])}",
        f"Einbruch erfasst: {len(dips)} von {len(started)} Starts ({_range(dips)})",
    ]
    return "\n".join(line.rstrip() for line in lines)


def main(argv: List[str], out: Callable[[str], None] = print) -> int:
    if len(argv) != 1 or not os.path.isfile(argv[0]):
        out("Aufruf: python3 -I scripts/battery_baseline.py <session_all_….csv>")
        return 2
    with open(argv[0], encoding="utf-8-sig", errors="replace") as f:
        sessions = parse_csv(f)
    out(format_report(analyse_all(sessions)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
