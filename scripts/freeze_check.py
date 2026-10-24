#!/usr/bin/env python3
"""Abnahme der lauten Anzeige bei eingefrorenen Werten, gegen den ECU-Simulator des Debug-Builds mit eingebautem Einfrieren (Spezifikation 2026-10-24).

    ./gradlew assembleDebug -PsimFreezeAfterSeconds=40
    adb install -r app/build/outputs/apk/debug/app-debug.apk
    python3 -u scripts/freeze_check.py [--package de.adv990de.obd2.debug] [--seconds 240] [--shots DIR]

Der Simulator friert 40 s nach der ersten Simulator-Verbindung des Prozesses ein (Property `simFreezeAfterSeconds`, `SimulatorFreeze`): er antwortet weiter, aber mit demselben Wert,
auch nach einem Neuverbinden — wie bei den Fahrten 52, 60 und 67 bis 69. Das Skript verbindet ihn, liest das Dashboard alle paar Sekunden (Band, Striche, Drehzahl) und prueft die Reihenfolge:

  1. erst fliessen Werte (Drehzahl als Zahl),
  2. dann kommt das laute Band "Werte eingefroren" (die Sperre: die App hat schon einmal neu verbunden),
  3. in der Sperre steht keine Fahrzeugzahl mehr (Drehzahl nicht verfuegbar, Striche in den Kacheln),
  4. und es kommen keine Zahlen zurueck (der Simulator bleibt eingefroren).
"Werte eingefroren · verbinde neu" (waehrend des Neuverbindens, rund 5 s) wird mitgezaehlt, ist aber kein Muss: die Abtastung dauert ~4,5 s je Bild und kann das Fenster verpassen.

Mit --shots DIR liegt je Abtastung ein Bildschirmfoto im Ordner (zum Ablesen der Hoehe und des Kontrasts des Bandes). Endet mit "ALL PASS" bzw. "FAILURES" (Exit-Code 0 bzw. 1).

Grenzen: das Geraet muss ENTSPERRT sein (die Sperre wird nicht umgangen); nur der Debug-Build hat den Simulator; die Zeiten haengen am Watchdog (Standard 20 s) — ein Lauf dauert 2 bis 4 Minuten.
Es prueft NICHT: einen echten Adapter. Ob ein echtes Steuergeraet oder ein echter Adapter so einfrieren, wie der Simulator es nachbildet, zeigt nur die Fahrt.
"""
import argparse
import os
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import smoke  # noqa: E402  (Hilfsfunktionen: adb, dump_nodes, tap_text, ...)

LIVE = "live"
FROZEN = "frozen"
RECONNECT_FROZEN = "reconnect-frozen"
SILENT = "silent"
PREVIEW = "preview"
OTHER = "other"


def classify(texts):
    """Der Zustand des Dashboards aus den Texten eines uiautomator-Abzugs. Das laute Band steht nicht in Grossbuchstaben (anders als die schmalen Baender): es wird ohne
    Beachtung der Schreibweise verglichen. Im Querformat steht die Detailzeile im selben Text hinter dem Titel ("Werte eingefroren   Fahrzeugwerte ...")."""
    upper = [t.upper().strip() for t in texts]
    if any(t.startswith("WERTE EINGEFROREN · VERBINDE") for t in upper):
        return RECONNECT_FROZEN
    if any(t.startswith("WERTE EINGEFROREN") for t in upper):
        return FROZEN
    if any(t.startswith("KEINE AKTUELLEN WERTE") for t in upper):
        return SILENT
    if any(t.startswith(("VORSCHAU", "VERBUNDEN · WARTE")) for t in upper):
        return PREVIEW
    if smoke.data_flows(texts):
        return LIVE
    return OTHER


def dash_count(texts):
    """Wie viele Kacheln "--" zeigen (Wert ohne Messung)."""
    return sum(1 for t in texts if t.strip() == "--")


def sample(texts):
    """Eine Abtastung: Zustand, Drehzahl (None = nicht verfuegbar) und Zahl der Striche."""
    return dict(state=classify(texts), rpm=smoke.live_rpm(texts), dashes=dash_count(texts))


def evaluate(samples):
    """Die Pruefungen ueber alle Abtastungen: Liste von (Name, bestanden, Hinweis)."""
    states = [s["state"] for s in samples]
    first_live = states.index(LIVE) if LIVE in states else None
    first_frozen = states.index(FROZEN) if FROZEN in states else None
    frozen = [s for s in samples if s["state"] == FROZEN]
    after_frozen = samples[first_frozen:] if first_frozen is not None else []
    return [
        ("Erst fliessen Werte (Drehzahl als Zahl)", first_live is not None, f"{states.count(LIVE)} Abtastungen live"),
        ("Das laute Band \"Werte eingefroren\" erscheint", first_frozen is not None, f"{len(frozen)} Abtastungen"),
        ("Es erscheint nach den Werten, nicht davor",
         first_live is not None and first_frozen is not None and first_live < first_frozen, f"live ab {first_live}, eingefroren ab {first_frozen}"),
        ("In der Sperre steht keine Drehzahl", bool(frozen) and all(s["rpm"] is None for s in frozen), str([s["rpm"] for s in frozen][:5])),
        ("In der Sperre zeigen die Kacheln Striche", bool(frozen) and all(s["dashes"] >= 1 for s in frozen), str([s["dashes"] for s in frozen][:5])),
        ("Nach der Sperre kommen keine Zahlen zurueck (der Simulator bleibt eingefroren)", bool(after_frozen) and LIVE not in [s["state"] for s in after_frozen], ""),
    ]


def locked():
    return "isKeyguardShowing=true" in smoke.adb("shell", "dumpsys", "window").replace(" ", "")


def screenshot(path):
    with open(path, "wb") as f:
        subprocess.run(["adb", "exec-out", "screencap", "-p"], stdout=f)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--package", default="de.adv990de.obd2.debug")
    parser.add_argument("--seconds", type=int, default=240, help="so lange abtasten (nach dem Verbinden)")
    parser.add_argument("--shots", default=None, help="Ordner fuer ein Bildschirmfoto je Abtastung")
    args = parser.parse_args()
    if not args.package.endswith(".debug"):
        print("Nur der Debug-Build hat den Simulator (Paket ...debug).", file=sys.stderr)
        return 2
    smoke.adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    if locked():
        print("Geraet gesperrt (isKeyguardShowing=true): bitte entsperren, die Sperre wird nicht umgangen.", file=sys.stderr)
        return 2
    if args.shots:
        os.makedirs(args.shots, exist_ok=True)

    smoke.adb("shell", "am", "force-stop", args.package)
    time.sleep(1)
    smoke.adb("shell", "am", "start", "-n", args.package + "/de.adv990de.obd2.MainActivity")
    time.sleep(5)
    smoke.tap_text("Einstellungen")
    time.sleep(2)
    if not smoke.simulator_button("Verbinden"):
        print("FAIL Simulator-Zeile bietet kein Verbinden an", file=sys.stderr)
        return 1
    started = time.monotonic()
    if not smoke.poll(lambda: smoke.row_button(smoke.dump_nodes(), smoke.SIMULATOR_ROW, "Trennen"), timeout=20, interval=1):
        print("FAIL Simulator nicht verbunden", file=sys.stderr)
        return 1
    smoke.tap_text("Live")

    samples = []
    while time.monotonic() - started < args.seconds:
        t = time.monotonic() - started
        if args.shots:
            screenshot(os.path.join(args.shots, f"{len(samples):03d}_t{int(t):03d}.png"))
        texts = [n["t"] or n["d"] for n in smoke.dump_nodes()]
        s = sample(texts)
        samples.append(s)
        print(f"t=+{t:5.1f}s  {s['state']:<17} Drehzahl={s['rpm']}  Striche={s['dashes']}", flush=True)

    ok_all = True
    for name, ok, info in evaluate(samples):
        ok_all &= bool(ok)
        print(("PASS " if ok else "FAIL ") + name + (" - " + info if info else ""), flush=True)
    seen = sum(1 for s in samples if s["state"] == RECONNECT_FROZEN)
    print(f"INFO \"Werte eingefroren · verbinde neu\" in {seen} Abtastungen gesehen (kein Muss)")
    print("ALL PASS" if ok_all else "FAILURES")
    return 0 if ok_all else 1


if __name__ == "__main__":
    sys.exit(main())
