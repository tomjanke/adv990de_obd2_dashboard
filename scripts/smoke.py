#!/usr/bin/env python3
"""Kompakter Release-Smoke-Test per uiautomator am angeschlossenen Geraet.

    python3 -u scripts/smoke.py <versionName> <versionCode> [--package de.adv990de.obd2]

Prueft: die App startet und zeigt das Dashboard, die Reiter DTCs/Verlauf/Einstellungen oeffnen, der Beenden-Dialog erscheint
(und wird abgebrochen), App-Info nennt Version und Build, und Logcat enthaelt keinen Absturz. Endet mit "ALL PASS" bzw. "FAILURES"
(Exit-Code 0 bzw. 1).

Grenzen (aus den Fallen im Projekt-Memory):
 - Das Geraet muss ENTSPERRT sein. Das Skript weckt es nur (KEYCODE_WAKEUP) und bricht bei gesperrtem Bildschirm ab.
 - Ein Dump dauert ~3 s, ein Lauf rund eine Minute. Mit `python3 -u` starten, wenn die Ausgabe umgeleitet wird.
 - Es tippt per TEXT, nie per Koordinate (die Ausrichtung wechselt, Auto-Drehen ist an).
 - BACK nur dort, wo nachweislich ein Dialog/eine Unterseite offen ist: auf einem Hauptreiter beendet es die App.
 - Es prueft NICHT: Messwerte, Verbindung zum Adapter, Wetter, Gaenge — dafuer gibt es die Instrumentation-Tests und die Fahrt.
"""
import argparse
import os
import re
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET


def adb(*args):
    return subprocess.run(["adb", *args], capture_output=True, text=True).stdout


def dump_nodes():
    local = os.path.join(tempfile.gettempdir(), "obd2_smoke_ui.xml")
    adb("shell", "uiautomator", "dump", "/sdcard/obd2_smoke_ui.xml")
    adb("pull", "/sdcard/obd2_smoke_ui.xml", local)
    nodes = []
    for n in ET.parse(local).getroot().iter("node"):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
        nodes.append(dict(t=n.get("text") or "", d=n.get("content-desc") or "",
                          x1=x1, y1=y1, x2=x2, y2=y2, cx=(x1 + x2) // 2, cy=(y1 + y2) // 2))
    return nodes


def texts():
    return [n["t"] or n["d"] for n in dump_nodes()]


def tap(x, y):
    adb("shell", "input", "tap", str(x), str(y))


def swipe(x1, y1, x2, y2, ms):
    adb("shell", "input", "swipe", str(x1), str(y1), str(x2), str(y2), str(ms))


def tap_text(text):
    # Eine unter die App-Leiste gescrollte Zeile bleibt als 1-px-Streifen im Baum: nur Knoten mit Hoehe >= 20 px zaehlen.
    for n in dump_nodes():
        if n["t"] == text and n["y2"] - n["y1"] >= 20:
            tap(n["cx"], n["cy"])
            return True
    return False


def screen_size():
    w, h = adb("shell", "wm", "size").split()[-1].split("x")
    return int(w), int(h)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("version")
    parser.add_argument("code")
    parser.add_argument("--package", default="de.adv990de.obd2")
    args = parser.parse_args()
    package = args.package

    adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    if "isKeyguardShowing=true" in adb("shell", "dumpsys", "window").replace(" ", ""):
        print("Geraet gesperrt (isKeyguardShowing=true): bitte entsperren, die Sperre wird nicht umgangen.", file=sys.stderr)
        return 2

    results = []

    def check(name, ok, info=""):
        results.append(bool(ok))
        print(("PASS " if ok else "FAIL ") + name + (" - " + info if info else ""), flush=True)

    def alive():
        return adb("shell", "pidof", package).strip() != ""

    adb("logcat", "-c")
    adb("shell", "am", "force-stop", package)
    time.sleep(1)
    adb("shell", "am", "start", "-n", package + "/de.adv990de.obd2.MainActivity")
    time.sleep(5)
    t = texts()
    check("App startet, Dashboard sichtbar", alive() and "Live" in t and any("DREHZAHL" in x.upper() for x in t), f"{len(t)} Texte")

    for tab in ("DTCs", "Verlauf", "Einstellungen"):
        ok = tap_text(tab)
        time.sleep(2.5)
        check(f"Reiter {tab} oeffnet", ok and alive())
    tap_text("Live")
    time.sleep(2)
    check("Reiter Live zurueck", any("DREHZAHL" in x.upper() for x in texts()))

    tap_text("Beenden")
    time.sleep(1.5)
    dialog = [x for x in texts() if "beenden" in x.lower()]
    check("Beenden-Dialog erscheint", len(dialog) >= 2, str(dialog[:3]))
    if not tap_text("Abbrechen"):
        adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(1)
    check("Dialog geschlossen, App laeuft", alive() and any("DREHZAHL" in x.upper() for x in texts()))

    tap_text("Einstellungen")
    time.sleep(2)
    w, h = screen_size()
    found = False
    for step in range(40):
        if tap_text("App-Info"):
            found = True
            break
        if step == 0:
            for _ in range(8):      # erst schnell nach oben, dann in kleinen Schritten: ein Fling ueberspringt jedes Suchband
                swipe(w // 2, int(h * 0.7), w // 2, int(h * 0.3), 150)
        else:
            swipe(w // 2, int(h * 0.62), w // 2, int(h * 0.40), 600)
        time.sleep(0.8)
    check("Zeile App-Info gefunden", found)
    time.sleep(2)
    t = texts()
    check(f"App-Info nennt Version {args.version}", any(args.version in x for x in t), str([x for x in t if args.version in x][:2]))
    check(f"App-Info nennt Build {args.code}", any(args.code == x.strip() for x in t) or any(args.code in x for x in t),
          str([x for x in t if args.code in x][:2]))
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(1)

    log = adb("logcat", "-d", "-b", "crash") + adb("logcat", "-d", "-s", "AndroidRuntime:E")
    bad = [line for line in log.splitlines() if "FATAL EXCEPTION" in line or ("Process " + package) in line]
    check("Logcat ohne Absturz", not bad, str(bad[:2]))
    check("Prozess lebt am Ende", alive())

    ok = all(results)
    print("ALL PASS" if ok else "FAILURES", flush=True)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
