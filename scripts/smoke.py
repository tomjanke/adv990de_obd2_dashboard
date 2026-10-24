#!/usr/bin/env python3
"""Kompakter Release-Smoke-Test per uiautomator am angeschlossenen Geraet.

    python3 -u scripts/smoke.py <versionName> <versionCode> [--package de.adv990de.obd2]
    python3 -u scripts/smoke.py <versionName> <versionCode> --simulator [--package de.adv990de.obd2.debug]

Prueft: die App startet und zeigt das Dashboard, die Reiter Fahrten/Einstellungen oeffnen (in Fahrten die Bereiche Aktuell/Historie/Fahrten), der Beenden-Dialog erscheint
(und wird abgebrochen), App-Info nennt Version und Build, und Logcat enthaelt keinen Absturz. Endet mit "ALL PASS" bzw. "FAILURES"
(Exit-Code 0 bzw. 1).

Mit --simulator zusaetzlich der Datenfluss (nur im DEBUG-Build, der Simulator "OBD Sim" steht nur dort in der Geraeteliste; das ist dann
auch das Standard-Paket): verbinden, auf Werte warten (Verbindungspunkt "verbunden", Drehzahl als Zahl, kein Band "Vorschau"/"warte auf
Werte"/"Keine aktuellen Werte"), per "Trennen" wieder trennen und pruefen, dass die Verbindung danach NICHT von selbst zurueckkommt.
Voraussetzung: die Debug-App hat BLUETOOTH_CONNECT und POST_NOTIFICATIONS (sonst fragt Android beim Verbinden).

Grenzen (aus den Fallen im Projekt-Memory):
 - Das Geraet muss ENTSPERRT sein. Das Skript weckt es nur (KEYCODE_WAKEUP) und bricht bei gesperrtem Bildschirm ab.
 - Ein Dump dauert ~3 s, ein Lauf rund eine Minute. Mit `python3 -u` starten, wenn die Ausgabe umgeleitet wird.
 - Es tippt per TEXT, nie per Koordinate (die Ausrichtung wechselt, Auto-Drehen ist an). Nur das Wischen in der Einstellungsliste rechnet in
   Koordinaten, und zwar aus der Groesse des AKTUELLEN Bildschirms (aus dem uiautomator-Baum, nicht aus `wm size`: das meldet immer die
   Hochformat-Groesse) — der Lauf geht im Hoch- wie im Querformat.
 - BACK nur dort, wo nachweislich ein Dialog/eine Unterseite offen ist: auf einem Hauptreiter beendet es die App.
 - Es prueft NICHT: einen echten Adapter, Wetter, Gaenge — dafuer gibt es die Instrumentation-Tests und die Fahrt. Messwerte und
   Verbindung prueft es nur mit --simulator, und auch dort nur gegen den Simulator, nie gegen ein Fahrzeug.
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
    spot = find_text(dump_nodes(), text)
    if spot:
        tap(*spot)
    return spot is not None


# --- Reine Entscheidungslogik (ohne Geraet, getestet in scripts/test_smoke.py) ---------------------------------------------------------

def find_text(nodes, text):
    """Mitte (x, y) des ersten sichtbaren Knotens mit genau diesem Text, sonst None.

    Eine unter die App-Leiste gescrollte Zeile bleibt als 1-px-Streifen im Baum: nur Knoten mit Hoehe >= 20 px zaehlen.
    """
    for n in nodes:
        if n["t"] == text and n["y2"] - n["y1"] >= 20:
            return n["cx"], n["cy"]
    return None


def screen_size_from_nodes(nodes):
    """(Breite, Hoehe) des AKTUELLEN Bildschirms aus dem uiautomator-Baum (der Wurzelknoten deckt ihn ab), None bei leerem Baum.

    Nicht `wm size`: das meldet immer die Hochformat-Groesse (1008x2244), auch wenn das Geraet quer steht — dort lagen die Wischpunkte
    ausserhalb des Bildschirms, die Liste scrollte nie, und die Suche nach "App-Info" lief ins Leere.
    """
    if not nodes:
        return None
    return max(n["x2"] for n in nodes), max(n["y2"] for n in nodes)


# Zwei Wischbahnen in Bruchteilen der Bildschirmhoehe: erst schnell und weit nach oben (die Liste laeuft nach unten), dann in kleinen Schritten —
# ein Fling ueberspringt jedes Suchband. Alle Punkte liegen zwischen 0,30 und 0,70 der Hoehe, also auf der Liste: die App-Leiste endet bei rund
# 0,21 (Querformat gemessen), das Hauptmenue beginnt bei rund 0,80.
FAST_SWIPES = 8


def scroll_points(width, height):
    """[(x1, y1, x2, y2, ms)] fuer den schnellen und den feinen Wisch auf einem Bildschirm dieser Groesse."""
    x = width // 2
    return [
        (x, int(height * 0.70), x, int(height * 0.30), 150),
        (x, int(height * 0.62), x, int(height * 0.40), 600),
    ]


def scroll_stalled(before, after):
    """Hat sich nach dem Wischen nichts bewegt (Ende der Liste oder ein Wischen, das die Liste nicht traf)?

    Verglichen werden Text, Beschreibung und senkrechte Lage der sichtbaren Knoten (Streifen unter 20 px zaehlen nicht).
    """
    def shape(nodes):
        return [(n["t"], n["d"], n["y1"], n["y2"]) for n in nodes if n["y2"] - n["y1"] >= 20]
    return shape(before) == shape(after)


SIMULATOR_ROW = "OBD Sim"
# Baender des Dashboards, die sagen: noch keine (frischen) Werte — Vorschau (offline/verbinde), warte auf Werte, veraltet, eingefroren. Grossgeschrieben,
# weil uiautomator den DARGESTELLTEN Text liefert ("VORSCHAU \u00b7 NICHT VERBUNDEN"); verglichen wird ohne Beachtung der Schreibweise (die lauten Baender
# "Keine aktuellen Werte" und "Werte eingefroren" stehen seit 1.41.1 in Normalschrift im Dashboard).
NO_DATA_BANDS = ("VORSCHAU", "VERBUNDEN \u00b7 WARTE", "KEINE AKTUELLEN", "WERTE EINGEFROREN")


def bluetooth_state(texts):
    """'connected' / 'disconnected' aus der Beschreibung des Verbindungspunkts im Dashboard, None wenn er nicht zu sehen ist."""
    if "Bluetooth verbunden" in texts:
        return "connected"
    if "Bluetooth getrennt" in texts:
        return "disconnected"
    return None


def live_rpm(texts):
    """Die Zahl aus der Beschreibung des Drehzahlbalkens ("Drehzahl 2800 rpm"); None bei "Drehzahl nicht verfuegbar"."""
    for text in texts:
        match = re.fullmatch(r"Drehzahl (\d+) rpm", text.strip())
        if match:
            return int(match.group(1))
    return None


def no_data_bands(texts):
    """Die Baender unter den Texten, die "noch keine (frischen) Werte" sagen."""
    return [text for text in texts if text.upper().startswith(NO_DATA_BANDS)]


def reconnecting(texts):
    """Das Dashboard zeigt einen Verbindungsversuch ("VORSCHAU \u00b7 VERBINDUNGSVERSUCH 2/5" bzw. "\u2026 VERBINDE \u2026", nach eingefrorenen Werten "WERTE EINGEFROREN \u00b7 VERBINDE NEU")."""
    return any(
        "VERBINDUNGSVERSUCH" in text.upper()
        or text.upper().startswith("VORSCHAU \u00b7 VERBINDE")
        or text.upper().startswith("WERTE EINGEFROREN \u00b7 VERBINDE")
        for text in texts
    )


def data_flows(texts):
    """Werte fliessen: Verbindung steht, der Balken zeigt eine Zahl und kein Band sagt "noch keine Werte"."""
    return bluetooth_state(texts) == "connected" and live_rpm(texts) is not None and not no_data_bands(texts)


def row_button(nodes, row_text, button_text):
    """Mitte (x, y) der Schaltflaeche `button_text` in der Zeile `row_text`, sonst None.

    Eine Zeile ist rund 220 px hoch: Die Schaltflaeche gehoert zur Zeile, wenn ihre Mitte hoechstens zwei Texthoehen von der Mitte
    des Zeilentitels entfernt liegt. Hat die Zeile die Schaltflaeche nicht (verbunden zeigt "Trennen", nicht "Verbinden"), darf es NICHT
    die der Nachbarzeile werden — deshalb gibt es kein "naechste Schaltflaeche", sondern eine feste Grenze. Streifen unter 20 px Hoehe
    (unter die App-Leiste gescrollt) zaehlen nicht.
    """
    visible = [n for n in nodes if n["y2"] - n["y1"] >= 20]
    for row in (n for n in visible if n["t"] == row_text):
        limit = 2 * (row["y2"] - row["y1"])
        for button in (n for n in visible if n["t"] == button_text):
            if abs(button["cy"] - row["cy"]) <= limit:
                return button["cx"], button["cy"]
    return None


def resolve_package(package, simulator):
    """Der Simulator gibt es nur im Debug-Build: mit --simulator ist dessen Paket der Standard."""
    return package or ("de.adv990de.obd2.debug" if simulator else "de.adv990de.obd2")


def simulator_package_error(package, simulator):
    """Meldung, wenn --simulator mit einem Paket ohne Simulator kombiniert wird, sonst None."""
    if simulator and not package.endswith(".debug"):
        return f"--simulator braucht den Debug-Build (Paket auf .debug endend), nicht {package}: der Simulator steht nur dort in der Geraeteliste."
    return None


def poll(fn, timeout, interval, sleep=time.sleep, now=time.monotonic):
    """Ruft fn bis zur ersten wahren Antwort (die zurueckkommt) oder bis zur Frist (dann die letzte Antwort)."""
    deadline = now() + timeout
    while True:
        value = fn()
        if value or now() >= deadline:
            return value
        sleep(interval)


def simulator_button(label):
    """Schaltflaeche `label` ("Verbinden"/"Trennen") in der Zeile des Simulators antippen; False, wenn die Zeile sie nicht hat."""
    spot = row_button(dump_nodes(), SIMULATOR_ROW, label)
    if spot:
        tap(*spot)
    return spot is not None


def run_simulator_checks(check, alive):
    """Verbinden, Werte, Trennen, kein Reconnect — gegen den Simulator des Debug-Builds (Geraet entsperrt, Dashboard offen)."""
    tap_text("Einstellungen")
    time.sleep(2)
    check("Simulator-Zeile \"" + SIMULATOR_ROW + "\" bietet Verbinden an", simulator_button("Verbinden"))

    connected = poll(lambda: row_button(dump_nodes(), SIMULATOR_ROW, "Trennen"), timeout=20, interval=1)
    check("Simulator verbunden (Zeile zeigt Trennen)", connected)

    tap_text("Live")
    live = poll(lambda: data_flows(texts()), timeout=25, interval=1)
    t = texts()
    check("Dashboard zeigt Werte vom Simulator", live,
          f"Verbindung {bluetooth_state(t)}, Drehzahl {live_rpm(t)}, Baender {no_data_bands(t)}")

    tap_text("Einstellungen")
    time.sleep(2)
    check("Simulator-Zeile bietet Trennen an", simulator_button("Trennen"))
    disconnected = poll(lambda: row_button(dump_nodes(), SIMULATOR_ROW, "Verbinden"), timeout=15, interval=1)
    check("Simulator getrennt (Zeile zeigt wieder Verbinden)", disconnected)

    tap_text("Live")
    time.sleep(10)   # laenger als die Basisverzoegerung des Reconnects (3 s): nach "Trennen" darf KEIN Versuch folgen
    t = texts()
    check("Nach Trennen kein Reconnect", bluetooth_state(t) == "disconnected" and not reconnecting(t),
          f"Verbindung {bluetooth_state(t)}, Baender {no_data_bands(t)}")
    check("App laeuft nach dem Simulator-Lauf", alive())


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("version")
    parser.add_argument("code")
    parser.add_argument("--package", default=None, help="Standard: de.adv990de.obd2, mit --simulator de.adv990de.obd2.debug")
    parser.add_argument("--simulator", action="store_true", help="zusaetzlich den Datenfluss gegen den ECU-Simulator pruefen (Debug-Build)")
    args = parser.parse_args()
    package = resolve_package(args.package, args.simulator)
    error = simulator_package_error(package, args.simulator)
    if error:
        print(error, file=sys.stderr)
        return 2

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

    for tab in ("Fahrten", "Einstellungen"):
        ok = tap_text(tab)
        time.sleep(2.5)
        check(f"Reiter {tab} oeffnet", ok and alive())
        if tab == "Fahrten":
            # Seit 1.33.0 sind DTCs und Verlauf EIN Reiter mit dem Umschalter "Fahrten | Aktuell | Historie". Ohne Fahrzeug liest "Aktuell"
            # nichts ("Nicht verbunden"); es zaehlt, dass jeder Bereich oeffnet und die App weiterlaeuft.
            for section in ("Aktuell", "Historie", "Fahrten"):
                ok = tap_text(section)
                time.sleep(1.5)
                check(f"Bereich {section} oeffnet", ok and alive())
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

    if args.simulator:
        run_simulator_checks(check, alive)

    tap_text("Einstellungen")
    time.sleep(2)
    nodes = dump_nodes()
    size = screen_size_from_nodes(nodes)
    found = False
    stalled = False
    if size:
        fast, fine = scroll_points(*size)
        for step in range(40):
            spot = find_text(nodes, "App-Info")
            if spot:
                tap(*spot)
                found = True
                break
            for _ in range(FAST_SWIPES if step == 0 else 1):
                swipe(*(fast if step == 0 else fine))
            time.sleep(0.8)
            before, nodes = nodes, dump_nodes()
            if scroll_stalled(before, nodes):   # Ende der Liste oder Wischen ohne Wirkung: nicht weitere 40 Schritte suchen
                stalled = True
                break
    check("Zeile App-Info gefunden", found,
          "" if found else (f"Bildschirm {size}, " + ("die Liste bewegte sich nicht mehr" if stalled else "nicht gefunden")))
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
