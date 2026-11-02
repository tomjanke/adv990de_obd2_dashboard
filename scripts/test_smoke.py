#!/usr/bin/env python3
"""Tests der reinen Entscheidungslogik von scripts/smoke.py (ohne Geraet, ohne adb).

    python3 -I scripts/test_smoke.py

Der JVM-Test `SmokeScriptTest` fuehrt diese Datei aus. Was hier nicht steht, laeuft nur am Geraet: das Tippen und Warten selbst.
"""
import importlib.util
import os
import tempfile
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
                     "Verbunden · warte auf Werte", "Keine aktuellen Werte", "Werte eingefroren",
                     "Werte eingefroren · verbinde neu 1/5"):
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
        self.assertTrue(smoke.reconnecting(["Werte eingefroren · verbinde neu 2/5"]))
        self.assertFalse(smoke.reconnecting(["Werte eingefroren"]))

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


PORTRAIT = (1008, 2244)    # Pixel 8 Pro aufrecht
LANDSCAPE = (2244, 1008)   # gedreht: `wm size` meldet trotzdem immer 1008x2244 — daher kommt die Groesse aus dem Baum


def tree(width, height, *children):
    """Ein uiautomator-Baum: der Wurzelknoten deckt den ganzen Bildschirm, darunter die uebrigen Knoten."""
    return [node("", 0, height, x1=0, x2=width), *children]


class FindTextTest(unittest.TestCase):
    def test_finds_the_middle_of_the_first_node_with_exactly_that_text(self):
        nodes = [node("App-Logs", 400, 560), node("App-Info", 600, 760, x1=0, x2=200)]
        self.assertEqual((100, 680), smoke.find_text(nodes, "App-Info"))

    def test_needs_the_whole_text_not_a_part(self):
        self.assertIsNone(smoke.find_text([node("App-Info Version", 400, 560)], "App-Info"))

    def test_ignores_slivers_scrolled_under_the_app_bar(self):
        self.assertIsNone(smoke.find_text([node("App-Info", 180, 181)], "App-Info"))
        self.assertIsNotNone(smoke.find_text([node("App-Info", 180, 200)], "App-Info"))

    def test_missing_text_gives_none(self):
        self.assertIsNone(smoke.find_text([], "App-Info"))


class ScreenSizeTest(unittest.TestCase):
    def test_follows_the_rotation_of_the_screen(self):
        self.assertEqual(PORTRAIT, smoke.screen_size_from_nodes(tree(*PORTRAIT, node("Live", 2000, 2100))))
        self.assertEqual(LANDSCAPE, smoke.screen_size_from_nodes(tree(*LANDSCAPE, node("Live", 800, 900))))

    def test_an_empty_tree_has_no_size(self):
        self.assertIsNone(smoke.screen_size_from_nodes([]))


class ScrollPointsTest(unittest.TestCase):
    # Gemessen am Pixel 8 Pro im Querformat (Bildschirmfoto): die App-Leiste endet bei rund 0,21 der Hoehe, das Hauptmenue beginnt bei rund 0,80.
    # Ein Wischen, das ausserhalb dieses Bandes beginnt, greift die Leiste oder das Menue statt der Liste.
    TOP, BOTTOM = 0.21, 0.79

    def test_every_swipe_stays_on_the_list_in_both_orientations(self):
        for width, height in (PORTRAIT, LANDSCAPE):
            for x1, y1, x2, y2, _ms in smoke.scroll_points(width, height):
                self.assertEqual(width // 2, x1)
                self.assertEqual(width // 2, x2)
                for y in (y1, y2):
                    self.assertTrue(self.TOP * height < y < self.BOTTOM * height, f"{width}x{height}: y={y} liegt nicht auf der Liste")

    def test_the_first_swipe_goes_up_the_list_and_the_next_ones_are_small(self):
        fast, fine = smoke.scroll_points(*LANDSCAPE)
        self.assertGreater(fast[1] - fast[3], fine[1] - fine[3])   # schnell: weit nach oben; fein: ein Fling ueberspringt sonst jedes Suchband
        self.assertGreater(fast[1], fast[3])                       # nach oben wischen = die Liste laeuft nach unten
        self.assertGreater(fine[1], fine[3])
        self.assertLess(fast[4], fine[4])                          # der schnelle Wisch ist kuerzer (Fling), der feine langsam

    def test_the_size_must_be_the_current_one_not_the_physical_portrait_size(self):
        # Der Fehler bis jetzt: `wm size` meldet im Querformat 1008x2244; 0,7 x 2244 = 1570 liegt auf einem 1008 px hohen Bildschirm ausserhalb.
        _, y_start, _, _, _ = smoke.scroll_points(*PORTRAIT)[0]
        self.assertGreater(y_start, LANDSCAPE[1])


class ScrollStalledTest(unittest.TestCase):
    def test_the_same_screen_after_the_swipe_means_the_list_did_not_move(self):
        before = tree(*LANDSCAPE, node("App-Logs", 400, 560), node("App-Info", 600, 760))
        after = tree(*LANDSCAPE, node("App-Logs", 400, 560), node("App-Info", 600, 760))
        self.assertTrue(smoke.scroll_stalled(before, after))

    def test_a_row_that_moved_is_not_stalled_even_with_the_same_texts(self):
        before = tree(*LANDSCAPE, node("App-Logs", 400, 560), node("App-Info", 600, 760))
        after = tree(*LANDSCAPE, node("App-Logs", 300, 460), node("App-Info", 500, 660))
        self.assertFalse(smoke.scroll_stalled(before, after))

    def test_a_new_row_is_not_stalled(self):
        before = tree(*LANDSCAPE, node("App-Logs", 400, 560))
        after = tree(*LANDSCAPE, node("App-Logs", 400, 560), node("App-Info", 600, 760))
        self.assertFalse(smoke.scroll_stalled(before, after))

    def test_slivers_under_the_app_bar_do_not_count_as_movement(self):
        before = tree(*LANDSCAPE, node("App-Logs", 400, 560), node("Fahrzeuginfo", 180, 181))
        after = tree(*LANDSCAPE, node("App-Logs", 400, 560), node("Fahrzeuginfo", 182, 183))
        self.assertTrue(smoke.scroll_stalled(before, after))


class LogbookAddButtonTest(unittest.TestCase):
    def test_sees_the_button_by_the_description_uiautomator_shows(self):
        # Am Geraet (Release 1.42.0): der Knopf erscheint mit der Beschreibung des Symbols, nicht mit seinem Text "Eintrag".
        self.assertTrue(smoke.logbook_add_button(["Logbuch", "Eintrag hinzuf\u00fcgen", "Live"]))

    def test_sees_the_button_by_its_label_too(self):
        self.assertTrue(smoke.logbook_add_button(["Logbuch", "Eintrag"]))

    def test_the_hint_of_the_empty_list_is_not_the_button(self):
        self.assertFalse(smoke.logbook_add_button(["Noch keine Eintr\u00e4ge. Mit \u201eEintrag\u201c kommt der erste hinzu."]))

    def test_no_button_without_the_logbook(self):
        self.assertFalse(smoke.logbook_add_button(["Fahrten", "Aktuell", "Historie"]))


class FakeClock:
    """Eine Uhr, die nur durch sleep() vergeht — wie in PollTest."""

    def __init__(self):
        self.t = 0.0

    def sleep(self, seconds):
        self.t += seconds

    def now(self):
        return self.t


class TapUntilTest(unittest.TestCase):
    def run_tap_until(self, taps, seen, **kwargs):
        """`taps` = die Antworten von tap() der Reihe nach, `seen` = Funktion von der Zeit (und der Zahl der Tipps) zur Erwartung."""
        clock = FakeClock()
        tapped = []

        def tap():
            tapped.append(clock.t)
            return taps[min(len(tapped), len(taps)) - 1]

        result = smoke.tap_until(tap, lambda: seen(clock.t, len(tapped)), sleep=clock.sleep, now=clock.now, **kwargs)
        return result, tapped

    def test_one_tap_is_enough_when_the_goal_shows_at_once(self):
        result, tapped = self.run_tap_until([True], lambda t, n: True)
        self.assertEqual((True, 1), result)
        self.assertEqual(1, len(tapped))

    def test_a_goal_that_shows_a_little_later_does_not_tap_again(self):
        # Der Wechsel des Bereichs braucht einen Moment (Animation): innerhalb der Frist wird nur gewartet, nicht erneut getippt.
        result, tapped = self.run_tap_until([True], lambda t, n: t >= 1.5, wait=4.0, interval=0.5)
        self.assertEqual((True, 1), result)
        self.assertEqual(1, len(tapped))

    def test_a_goal_that_never_shows_is_tapped_again_and_then_given_up(self):
        result, tapped = self.run_tap_until([True], lambda t, n: False, attempts=3, wait=2.0, interval=0.5)
        self.assertEqual((False, 3), result)
        self.assertEqual(3, len(tapped))

    def test_a_second_tap_can_make_the_goal_show(self):
        # Der erste Tipp traf ein sich bewegendes Ziel: erst nach dem zweiten steht der Bereich da.
        result, tapped = self.run_tap_until([True], lambda t, n: n >= 2, attempts=3, wait=1.0, interval=0.5)
        self.assertEqual((True, 2), result)
        self.assertEqual(2, len(tapped))

    def test_nothing_to_tap_is_tried_again_without_waiting_for_the_goal(self):
        # Der Text stand beim ersten Abruf nicht im Baum (Uebergang, fehlgeschlagener Dump): erneut versuchen, nicht auf ein Ziel warten, das nie angetippt wurde.
        result, tapped = self.run_tap_until([False, True], lambda t, n: n >= 2, attempts=3, wait=10.0, interval=0.5)
        self.assertEqual((True, 2), result)
        self.assertEqual([0.0, 0.5], tapped)

    def test_nothing_to_tap_at_all_gives_up_after_the_attempts(self):
        result, tapped = self.run_tap_until([False], lambda t, n: True, attempts=3, interval=0.5)
        self.assertEqual((False, 3), result)
        self.assertEqual(3, len(tapped))


class NodesFromFileTest(unittest.TestCase):
    def write(self, text):
        handle = tempfile.NamedTemporaryFile("w", suffix=".xml", delete=False, encoding="utf-8")
        handle.write(text)
        handle.close()
        self.addCleanup(os.remove, handle.name)
        return handle.name

    def test_reads_text_description_and_bounds(self):
        path = self.write(
            '<?xml version="1.0" encoding="UTF-8"?><hierarchy>'
            '<node text="Logbuch" content-desc="" bounds="[10,20][110,60]"/>'
            '<node text="" content-desc="Eintrag hinzuf\u00fcgen" bounds="[0,100][200,300]"/></hierarchy>'
        )
        nodes = smoke.nodes_from_file(path)
        self.assertEqual(["Logbuch", ""], [n["t"] for n in nodes])
        self.assertEqual("Eintrag hinzuf\u00fcgen", nodes[1]["d"])
        self.assertEqual((60, 40), (nodes[0]["cx"], nodes[0]["cy"]))

    def test_a_missing_file_is_an_empty_tree_not_an_error(self):
        self.assertEqual([], smoke.nodes_from_file(os.path.join(tempfile.gettempdir(), "obd2_smoke_does_not_exist.xml")))

    def test_a_broken_file_is_an_empty_tree_not_an_error(self):
        self.assertEqual([], smoke.nodes_from_file(self.write("<hierarchy><node")))


if __name__ == "__main__":
    unittest.main()
