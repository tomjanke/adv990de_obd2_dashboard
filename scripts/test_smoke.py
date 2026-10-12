#!/usr/bin/env python3
"""Tests der reinen Entscheidungslogik von scripts/smoke.py (ohne Geraet, ohne adb).

    python3 -I scripts/test_smoke.py

Der JVM-Test `SmokeScriptTest` fuehrt diese Datei aus. Was hier nicht steht, laeuft nur am Geraet: das Tippen und Warten selbst.
"""
import importlib.util
import os
import unittest

_HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location("smoke", os.path.join(_HERE, "smoke.py"))
smoke = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(smoke)


def node(text, y1, y2, x1=0, x2=100):
    return dict(t=text, d="", x1=x1, y1=y1, x2=x2, y2=y2, cx=(x1 + x2) // 2, cy=(y1 + y2) // 2)


LIVE = ["Live", "Bluetooth verbunden", "Drehzahl 2800 rpm", "COOLANT", "87"]


class BluetoothStateTest(unittest.TestCase):
    def test_reads_the_status_icon_description(self):
        self.assertEqual("connected", smoke.bluetooth_state(["Live", "Bluetooth verbunden"]))
        self.assertEqual("disconnected", smoke.bluetooth_state(["Live", "Bluetooth getrennt"]))

    def test_is_unknown_without_the_icon(self):
        self.assertIsNone(smoke.bluetooth_state(["Einstellungen", "Verbindung"]))
        self.assertIsNone(smoke.bluetooth_state([]))


class LiveRpmTest(unittest.TestCase):
    def test_reads_the_number_from_the_bar_description(self):
        self.assertEqual(2800, smoke.live_rpm(["x", "Drehzahl 2800 rpm"]))
        self.assertEqual(0, smoke.live_rpm(["Drehzahl 0 rpm"]))

    def test_unavailable_is_not_a_number(self):
        self.assertIsNone(smoke.live_rpm(["Drehzahl nicht verfügbar"]))
        self.assertIsNone(smoke.live_rpm([]))


class DataFlowsTest(unittest.TestCase):
    def test_connected_with_a_rpm_number_and_no_band_is_live(self):
        self.assertTrue(smoke.data_flows(LIVE))

    def test_every_band_means_no_data_yet(self):
        for band in ("Vorschau · nicht verbunden", "Vorschau · Verbindungsversuch 2/5", "Vorschau · verbinde …",
                     "Verbunden · warte auf Werte", "Keine aktuellen Werte"):
            self.assertFalse(smoke.data_flows(LIVE + [band]), band)

    def test_the_bands_are_drawn_in_capitals(self):
        # uiautomator liefert den DARGESTELLTEN Text: das Band steht dort als "VORSCHAU · NICHT VERBUNDEN" (am Pixel gemessen).
        for band in ("VORSCHAU · NICHT VERBUNDEN", "VERBUNDEN · WARTE AUF WERTE", "KEINE AKTUELLEN WERTE"):
            self.assertFalse(smoke.data_flows(LIVE + [band]), band)
            self.assertEqual([band], smoke.no_data_bands(LIVE + [band]))
        self.assertEqual([], smoke.no_data_bands(LIVE))

    def test_needs_the_link_and_a_number(self):
        self.assertFalse(smoke.data_flows(["Bluetooth getrennt", "Drehzahl 2800 rpm"]))
        self.assertFalse(smoke.data_flows(["Bluetooth verbunden", "Drehzahl nicht verfügbar"]))
        self.assertFalse(smoke.data_flows([]))


class ReconnectingTest(unittest.TestCase):
    def test_sees_an_attempt_or_a_connecting_band_as_drawn(self):
        self.assertTrue(smoke.reconnecting(["VORSCHAU · VERBINDUNGSVERSUCH 2/5"]))
        self.assertTrue(smoke.reconnecting(["VORSCHAU · VERBINDE …"]))

    def test_a_clean_disconnect_is_not_reconnecting(self):
        self.assertFalse(smoke.reconnecting(["VORSCHAU · NICHT VERBUNDEN", "Bluetooth getrennt"]))
        self.assertFalse(smoke.reconnecting([]))


class RowButtonTest(unittest.TestCase):
    # Zwei Zeilen im Abstand von 220 px: die Simulator-Zeile oben, ein echter Adapter darunter.
    def nodes(self, sim_button, other_button="Verbinden"):
        return [
            node("OBD Sim", 400, 470), node("SIMULATOR", 470, 520), node(sim_button, 395, 515, x1=700, x2=1000),
            node("OBDII", 620, 690), node("CLASSIC", 690, 740), node(other_button, 615, 735, x1=700, x2=1000),
        ]

    def test_finds_the_button_in_the_same_row(self):
        self.assertEqual((850, 455), smoke.row_button(self.nodes("Verbinden"), "OBD Sim", "Verbinden"))

    def test_never_takes_the_button_of_another_row(self):
        # Die Simulator-Zeile ist verbunden ("Trennen"); "Verbinden" gehoert nur der Zeile darunter.
        nodes = self.nodes("Trennen")
        self.assertIsNone(smoke.row_button(nodes, "OBD Sim", "Verbinden"))
        self.assertEqual((850, 455), smoke.row_button(nodes, "OBD Sim", "Trennen"))

    def test_ignores_slivers_scrolled_under_the_app_bar(self):
        nodes = [node("OBD Sim", 400, 410), node("Verbinden", 395, 405)]
        self.assertIsNone(smoke.row_button(nodes, "OBD Sim", "Verbinden"))

    def test_missing_row_gives_none(self):
        self.assertIsNone(smoke.row_button([node("Verbinden", 0, 100)], "OBD Sim", "Verbinden"))


class ArgumentsTest(unittest.TestCase):
    def test_the_simulator_defaults_to_the_debug_package(self):
        self.assertEqual("de.adv990de.obd2.debug", smoke.resolve_package(None, simulator=True))
        self.assertEqual("de.adv990de.obd2", smoke.resolve_package(None, simulator=False))
        self.assertEqual("de.adv990de.obd2.other", smoke.resolve_package("de.adv990de.obd2.other", simulator=False))

    def test_the_simulator_exists_only_in_the_debug_build(self):
        self.assertIsNotNone(smoke.simulator_package_error("de.adv990de.obd2", simulator=True))
        self.assertIn("debug", smoke.simulator_package_error("de.adv990de.obd2", simulator=True))
        self.assertIsNone(smoke.simulator_package_error("de.adv990de.obd2.debug", simulator=True))
        self.assertIsNone(smoke.simulator_package_error("de.adv990de.obd2", simulator=False))


class PollTest(unittest.TestCase):
    def test_returns_the_first_truthy_value(self):
        values = iter([None, 0, "ok", "later"])
        clock = [0.0]
        result = smoke.poll(lambda: next(values), timeout=30, interval=2,
                            sleep=lambda s: clock.__setitem__(0, clock[0] + s), now=lambda: clock[0])
        self.assertEqual("ok", result)
        self.assertEqual(4.0, clock[0])

    def test_gives_up_after_the_timeout_with_the_last_value(self):
        clock = [0.0]
        calls = []
        result = smoke.poll(lambda: calls.append(1) or None, timeout=5, interval=2,
                            sleep=lambda s: clock.__setitem__(0, clock[0] + s), now=lambda: clock[0])
        self.assertIsNone(result)
        self.assertEqual(4, len(calls))  # t = 0, 2, 4, 6 -> nach der Frist (>= 5) keine weitere Pruefung


if __name__ == "__main__":
    unittest.main()
