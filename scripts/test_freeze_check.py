#!/usr/bin/env python3
"""Tests fuer die reinen Teile von freeze_check.py (Zustand aus den Texten, Auswertung) — ohne Geraet. Lauf: python3 -I scripts/test_freeze_check.py"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import freeze_check as fc  # noqa: E402

LIVE = ["Bluetooth verbunden", "Drehzahl 2800 rpm", "COOLANT", "87"]


class ClassifyTest(unittest.TestCase):
    def test_live_needs_the_link_a_number_and_no_band(self):
        self.assertEqual(fc.LIVE, fc.classify(LIVE))

    def test_the_loud_frozen_band_in_portrait_has_title_and_detail_as_separate_texts(self):
        texts = ["Bluetooth verbunden", "Drehzahl nicht verfügbar", "Werte eingefroren", "Fahrzeugwerte werden nicht angezeigt und nicht aufgezeichnet", "--", "--"]
        self.assertEqual(fc.FROZEN, fc.classify(texts))

    def test_in_landscape_the_detail_stands_behind_the_title_in_one_text(self):
        texts = ["Bluetooth verbunden", "Werte eingefroren   Fahrzeugwerte werden nicht angezeigt und nicht aufgezeichnet", "--"]
        self.assertEqual(fc.FROZEN, fc.classify(texts))

    def test_the_reconnect_after_frozen_values_is_not_the_hold(self):
        self.assertEqual(fc.RECONNECT_FROZEN, fc.classify(["Werte eingefroren · verbinde neu 1/5", "--"]))
        self.assertEqual(fc.RECONNECT_FROZEN, fc.classify(["Werte eingefroren · verbinde neu …"]))

    def test_silent_and_the_small_preview_bands_are_their_own_states(self):
        self.assertEqual(fc.SILENT, fc.classify(["Bluetooth verbunden", "Keine aktuellen Werte"]))
        for band in ("VORSCHAU · NICHT VERBUNDEN", "VERBUNDEN · WARTE AUF WERTE", "VORSCHAU · VERBINDUNGSVERSUCH 2/5"):
            self.assertEqual(fc.PREVIEW, fc.classify([band]), band)

    def test_nothing_recognisable_is_other(self):
        self.assertEqual(fc.OTHER, fc.classify(["Einstellungen"]))
        self.assertEqual(fc.OTHER, fc.classify([]))

    def test_the_loud_band_beats_a_live_looking_rpm_text(self):
        self.assertEqual(fc.FROZEN, fc.classify(LIVE + ["Werte eingefroren"]))


class SampleTest(unittest.TestCase):
    def test_counts_the_dashes_and_reads_the_rpm(self):
        s = fc.sample(["Bluetooth verbunden", "Drehzahl 1612 rpm", "--", " -- ", "87"])
        self.assertEqual(dict(state=fc.LIVE, rpm=1612, dashes=2), s)

    def test_a_missing_rpm_is_none(self):
        self.assertIsNone(fc.sample(["Bluetooth verbunden", "Drehzahl nicht verfügbar", "--"])["rpm"])


def s(state, rpm=None, dashes=0):
    return dict(state=state, rpm=rpm, dashes=dashes)


GOOD = [s(fc.LIVE, 2800), s(fc.LIVE, 3000), s(fc.RECONNECT_FROZEN, None, 3), s(fc.LIVE, 8500), s(fc.FROZEN, None, 3), s(fc.FROZEN, None, 3)]


class EvaluateTest(unittest.TestCase):
    def verdicts(self, samples):
        return [ok for _, ok, _ in fc.evaluate(samples)]

    def test_the_expected_sequence_passes_every_check(self):
        self.assertTrue(all(self.verdicts(GOOD)), fc.evaluate(GOOD))

    def test_no_loud_band_at_all_fails(self):
        self.assertFalse(all(self.verdicts([s(fc.LIVE, 2800)] * 4)))

    def test_a_band_without_values_before_it_fails_the_order(self):
        self.assertFalse(all(self.verdicts([s(fc.FROZEN, None, 3), s(fc.FROZEN, None, 3)])))

    def test_a_rpm_number_inside_the_hold_fails(self):
        self.assertFalse(all(self.verdicts(GOOD[:4] + [s(fc.FROZEN, 8500, 3)])))

    def test_a_hold_without_dashes_fails(self):
        self.assertFalse(all(self.verdicts(GOOD[:4] + [s(fc.FROZEN, None, 0)])))

    def test_numbers_coming_back_after_the_hold_fail(self):
        self.assertFalse(all(self.verdicts(GOOD + [s(fc.LIVE, 8500)])))

    def test_no_samples_at_all_fail_every_check(self):
        self.assertFalse(any(self.verdicts([])))


if __name__ == "__main__":
    unittest.main()
