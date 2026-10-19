#!/usr/bin/env python3
"""Tests von scripts/battery_baseline.py (ohne Geraet, ohne adb).

    python3 -I scripts/test_battery_baseline.py

Der JVM-Test `BatteryBaselineScriptTest` fuehrt diese Datei aus. Die Beispieldaten sind erfunden und kurz; jede Definition des Skripts
(Zyklus, Zuendung an, Einbruch, laufend, Abstellen, Standzeit) hat hier mindestens einen Fall, damit eine verschobene Grenze auffaellt —
nur dann sind Auswertungen spaeterer Exporte mit der Basis vom 2026-10-19 vergleichbar.
"""
import importlib.util
import os
import sys
import tempfile
import unittest

_HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location("battery_baseline", os.path.join(_HERE, "battery_baseline.py"))
bb = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = bb  # Dataclasses (Python 3.9) schlagen ihr Modul darin nach
_spec.loader.exec_module(bb)

RPM, COOLANT, VOLT, GEAR = "010C", "0105", "0142", "GEAR"


def cycle(t_ms, rpm=None, volt=None, cool=None):
    """Die Zeilen eines Zyklus wie in der Aufzeichnung: die Werte folgen im Abstand von ein paar Zehntelsekunden."""
    rows = []
    if volt is not None:
        rows.append((t_ms, VOLT, volt))
    if rpm is not None:
        rows.append((t_ms + 100, RPM, rpm))
    if cool is not None:
        rows.append((t_ms + 200, COOLANT, cool))
    return rows


def flat(*cycles):
    return [row for c in cycles for row in c]


def trip(pre=5, dip=None, run=20, stop=0, start_ms=0, pre_volt=12.0, run_volt=13.5, stop_volts=(), cool=19.0):
    """Eine Fahrt im Sekundentakt: `pre` Zyklen Zuendung an, optional ein Einbruch-Zyklus (0 rpm) direkt vor dem Lauf, `run` Zyklen laufend, dann Abstellen."""
    cs, t = [], start_ms
    for _ in range(pre):
        cs.append(cycle(t, rpm=0.0, volt=pre_volt, cool=cool))
        t += 1000
    if dip is not None:
        cs.append(cycle(t, rpm=0.0, volt=dip, cool=cool))
        t += 1000
    for _ in range(run):
        cs.append(cycle(t, rpm=1500.0, volt=run_volt, cool=cool))
        t += 1000
    for v in stop_volts:
        cs.append(cycle(t, rpm=0.0, volt=v, cool=cool))
        t += 1000
    return flat(*cs)


def analyse(rows):
    return bb.analyse_session(1, bb.group_cycles(rows))


class ParseCsvTest(unittest.TestCase):
    def test_skips_the_header_and_groups_rows_by_session_in_file_order(self):
        lines = [
            "timestamp,pid,value,name,source,session",
            "1000,010C,0.0,RPM,ECU,45",
            "1100,0142,12.0,Control Module Voltage,ECU,45",
            "9000,010C,0.0,RPM,ECU,46",
        ]
        s = bb.parse_csv(lines)
        self.assertEqual([45, 46], sorted(s))
        self.assertEqual([(1000, RPM, 0.0), (1100, VOLT, 12.0)], s[45])
        self.assertEqual([(9000, RPM, 0.0)], s[46])

    def test_ignores_broken_lines_and_values_that_are_not_numbers(self):
        lines = [
            "",
            "kaputt",
            "abc,010C,1.0,RPM,ECU,1",
            "1000,010C,NaN,RPM,ECU,1",
            "1100,010C,inf,RPM,ECU,1",
            "1200,010C,zwei,RPM,ECU,1",
            "1300,010C,800.0,RPM,ECU,1",
            "1400,010C,800.0,RPM,ECU,notanid",
        ]
        self.assertEqual({1: [(1300, RPM, 800.0)]}, bb.parse_csv(lines))

    def test_an_export_from_before_the_session_column_is_one_session(self):
        # bis 1.33.1 hatte die Datei fuenf Spalten (eine einzelne Fahrt)
        s = bb.parse_csv(["timestamp,pid,value,name,source", "1000,010C,0.0,RPM,ECU", "2000,010C,5.0,RPM,ECU"])
        self.assertEqual({0: [(1000, RPM, 0.0), (2000, RPM, 5.0)]}, s)


class GroupCyclesTest(unittest.TestCase):
    def test_rows_closer_than_the_gap_are_one_cycle_and_the_cycle_ends_with_its_last_row(self):
        cs = bb.group_cycles(cycle(1000, rpm=800.0, volt=12.4, cool=30.0))
        self.assertEqual(1, len(cs))
        self.assertEqual(1200, cs[0].at_ms)
        self.assertEqual((800.0, 12.4, 30.0), (cs[0].rpm, cs[0].volt, cs[0].coolant))

    def test_a_pause_longer_than_450_ms_starts_a_new_cycle(self):
        # absolute Zahlen, nicht gegen die Konstante: die Zeilen eines Zyklus liegen unter 0,4 s, die Zyklen mindestens 0,8 s auseinander
        base = [(0, RPM, 1.0)]
        self.assertEqual(1, len(bb.group_cycles(base + [(400, VOLT, 12.0)])))
        self.assertEqual(1, len(bb.group_cycles(base + [(450, VOLT, 12.0)])))
        self.assertEqual(2, len(bb.group_cycles(base + [(451, VOLT, 12.0)])))

    def test_a_clock_that_jumps_backwards_starts_a_new_cycle(self):
        self.assertEqual(2, len(bb.group_cycles([(100_000, RPM, 1.0), (1_000, RPM, 2.0)])))

    def test_other_pids_do_not_change_the_cycle_and_a_missing_value_stays_none(self):
        cs = bb.group_cycles([(0, GEAR, 3.0), (50, RPM, 900.0)])
        self.assertEqual(1, len(cs))
        self.assertEqual((900.0, None, None), (cs[0].rpm, cs[0].volt, cs[0].coolant))

    def test_no_rows_no_cycles(self):
        self.assertEqual([], bb.group_cycles([]))


class RunningThresholdTest(unittest.TestCase):
    def _first_run_at(self, rpm):
        rows = flat(*[cycle(t * 1000, rpm=0.0, volt=12.0) for t in range(5)], cycle(5000, rpm=rpm, volt=13.0),
                    *[cycle(6000 + t * 1000, rpm=1500.0, volt=13.5) for t in range(20)])
        return analyse(rows)

    def test_the_engine_runs_from_500_rpm(self):
        self.assertEqual("started", self._first_run_at(500.0).kind)
        # der Zyklus mit 500 rpm endet bei 5100 ms und ist der Beginn des Laufs: Zuendung an endet 3 s davor, es bleiben die Zyklen bei 100 und 1100 ms
        self.assertEqual(2, self._first_run_at(500.0).rest_n)

    def test_cranking_below_500_rpm_is_not_running(self):
        # 499,9 und 400 rpm (Orgeln) sind noch nicht der Lauf: er beginnt erst im naechsten Zyklus (endet bei 6100 ms), es bleiben die Zyklen bei 100, 1100 und 2100 ms
        for rpm in (499.9, 400.0):
            r = self._first_run_at(rpm)
            self.assertEqual("started", r.kind)
            self.assertEqual(3, r.rest_n, f"bei {rpm} rpm")


class IgnitionOnTest(unittest.TestCase):
    def test_the_median_of_the_samples_before_the_start_is_the_ignition_on_voltage(self):
        r = analyse(trip(pre=5, pre_volt=12.0))
        self.assertEqual("started", r.kind)
        self.assertEqual(12.0, r.rest_v)
        self.assertEqual(5 - 3, r.rest_n)  # die letzten 3 s vor dem Start zaehlen nicht mehr als Zuendung an

    def test_the_last_three_seconds_before_the_start_are_cranking_not_ignition_on(self):
        r = analyse(trip(pre=8, dip=7.0, pre_volt=12.0))
        self.assertEqual(12.0, r.rest_v)  # der Einbruch (7,0 V) zieht den Median nicht herunter
        self.assertEqual(6, r.rest_n)  # 8 Zyklen Zuendung an: die letzten beiden und der Einbruch-Zyklus liegen im Fenster

    def test_samples_with_a_running_engine_are_never_ignition_on(self):
        # die Zyklen ab der ersten Drehzahl gehoeren nicht dazu, auch wenn sie vor dem Fenster liegen wuerden
        r = analyse(trip(pre=5, run=30, pre_volt=12.0, run_volt=13.5))
        self.assertEqual(12.0, r.rest_v)


class DipTest(unittest.TestCase):
    def test_the_deepest_sample_around_the_start_is_the_dip_with_its_distance_to_the_first_running_cycle(self):
        r = analyse(trip(pre=6, dip=8.1, pre_volt=11.9))
        self.assertEqual(8.1, r.dip_v)
        self.assertEqual(-1.0, r.dip_dt_s)

    def _trip_with_a_low_sample_at(self, offset_ms):
        # sechs Zyklen Zuendung an (12,0 V), der Lauf beginnt bei 8100 ms (Zyklus ab 8000), die tiefe Probe liegt `offset_ms` daneben
        rows = flat(*[cycle(t * 1000, rpm=0.0, volt=12.0) for t in range(6)], cycle(7000, rpm=0.0, volt=12.0),
                    cycle(8000, rpm=1500.0, volt=13.5), *[cycle(9000 + t * 1000, rpm=1500.0, volt=13.5) for t in range(25)])
        low_at = 8100 + offset_ms
        rows = [r for r in rows if not (low_at - 450 <= r[0] <= low_at + 450)]
        rows = sorted(rows + cycle(low_at - 100, rpm=1500.0 if offset_ms >= 0 else 0.0, volt=8.0))
        return analyse(rows)

    def test_the_dip_window_reaches_three_seconds_before_and_after_the_start(self):
        before_in = self._trip_with_a_low_sample_at(-2000)
        self.assertEqual(8.0, before_in.dip_v)
        self.assertEqual(-2.0, before_in.dip_dt_s)
        after_in = self._trip_with_a_low_sample_at(+2000)
        self.assertEqual(8.0, after_in.dip_v)
        self.assertEqual(2.0, after_in.dip_dt_s)

    def test_a_low_sample_outside_three_seconds_is_not_the_dip(self):
        self.assertIsNone(self._trip_with_a_low_sample_at(-4000).dip_v)
        self.assertIsNone(self._trip_with_a_low_sample_at(+4000).dip_v)

    def test_a_flat_voltage_means_no_dip_was_caught(self):
        r = analyse(trip(pre=6, pre_volt=11.9))
        self.assertIsNone(r.dip_v)

    def test_the_dip_must_be_more_than_half_a_volt_below_ignition_on(self):
        self.assertIsNone(analyse(trip(pre=6, dip=11.5, pre_volt=12.0)).dip_v)  # genau 0,5 V: kein Einbruch
        self.assertEqual(11.4, analyse(trip(pre=6, dip=11.4, pre_volt=12.0)).dip_v)

    def test_a_dip_in_the_first_running_cycle_counts(self):
        # Fahrt 55: der Einbruch fiel in denselben Zyklus wie die erste Drehzahl
        rows = flat(*[cycle(t * 1000, rpm=0.0, volt=12.0) for t in range(6)], cycle(6000, rpm=739.0, volt=6.8),
                    *[cycle(7000 + t * 1000, rpm=1700.0, volt=13.5) for t in range(20)])
        r = analyse(rows)
        self.assertEqual(6.8, r.dip_v)
        self.assertEqual(0.0, r.dip_dt_s)

    def test_without_ignition_on_samples_there_is_nothing_to_compare_with(self):
        rows = flat(cycle(0, rpm=0.0, volt=6.8), *[cycle(1000 + t * 1000, rpm=1500.0, volt=13.5) for t in range(20)])
        self.assertIsNone(analyse(rows).dip_v)


class RunningTest(unittest.TestCase):
    def test_the_median_starts_fifteen_seconds_after_the_first_running_cycle(self):
        # 15 Zyklen laufend mit 13,0 V (Einschwingen), danach 10 Zyklen mit 14,0 V: gezaehlt werden nur die zweiten
        rows = flat(*[cycle(t * 1000, rpm=0.0, volt=12.0) for t in range(5)],
                    *[cycle(5000 + t * 1000, rpm=1500.0, volt=13.0) for t in range(15)],
                    *[cycle(20000 + t * 1000, rpm=1500.0, volt=14.0) for t in range(10)])
        r = analyse(rows)
        self.assertEqual(14.0, r.run_v)
        self.assertEqual(10, r.run_n)

    def test_the_boundary_is_exactly_fifteen_seconds(self):
        def with_sample_at(t_sample):
            # erster Lauf-Zyklus endet bei 5100 ms, die geprueften Zyklen enden 100 ms nach ihrem Beginn
            return analyse(flat(*[cycle(t * 1000, rpm=0.0, volt=12.0) for t in range(5)],
                                cycle(5000, rpm=1500.0, volt=13.0), cycle(t_sample, rpm=1500.0, volt=14.0)))
        self.assertIsNone(with_sample_at(19999).run_v)  # endet bei 20099 ms, eine Millisekunde zu frueh
        self.assertEqual(14.0, with_sample_at(20000).run_v)  # endet bei 20100 ms = erster Lauf-Zyklus + 15 s

    def test_a_short_trip_has_no_running_value(self):
        r = analyse(trip(pre=5, run=5))
        self.assertIsNone(r.run_v)
        self.assertEqual(0, r.run_n)

    def test_cycles_without_a_running_engine_do_not_count_for_the_running_voltage(self):
        # Drehzahl fiel unter 500: diese Zyklen sind Abstellen, nicht laufend
        r = analyse(trip(pre=5, run=25, stop_volts=(13.0, 12.9), run_volt=13.5))
        self.assertEqual(13.5, r.run_v)


class StopTest(unittest.TestCase):
    def test_the_first_cycle_without_a_running_engine_and_the_one_fifteen_seconds_later(self):
        decay = [13.1 - 0.075 * i for i in range(20)]
        r = analyse(trip(pre=5, run=25, stop_volts=decay))
        self.assertEqual(13.1, r.stop_first_v)
        self.assertAlmostEqual(13.1 - 0.075 * 15, r.stop_after15_v, places=6)

    def _stop_with_one_late_sample_at(self, offset_ms):
        # erster Zyklus ohne Drehzahl endet bei 30100 ms; die einzige spaetere Probe endet `offset_ms` neben 45100 ms (= +15 s)
        rows = flat(*[cycle(t * 1000, rpm=0.0, volt=12.0) for t in range(5)],
                    *[cycle(5000 + t * 1000, rpm=1500.0, volt=13.5) for t in range(25)],
                    cycle(30000, rpm=0.0, volt=13.1), cycle(45000 + offset_ms, rpm=0.0, volt=11.9))
        return analyse(rows)

    def test_the_late_sample_may_be_up_to_two_seconds_beside_fifteen_seconds(self):
        self.assertEqual(11.9, self._stop_with_one_late_sample_at(+1900).stop_after15_v)
        self.assertEqual(11.9, self._stop_with_one_late_sample_at(-1900).stop_after15_v)

    def test_a_sample_more_than_two_seconds_away_is_no_late_value(self):
        self.assertIsNone(self._stop_with_one_late_sample_at(+2100).stop_after15_v)
        self.assertIsNone(self._stop_with_one_late_sample_at(-2100).stop_after15_v)

    def test_no_stop_when_the_engine_runs_until_the_end(self):
        r = analyse(trip(pre=5, run=25))
        self.assertIsNone(r.stop_first_v)
        self.assertIsNone(r.stop_after15_v)

    def test_a_stop_that_is_recorded_for_less_than_fifteen_seconds_has_no_late_value(self):
        r = analyse(trip(pre=5, run=25, stop_volts=(13.0, 12.9, 12.8)))
        self.assertEqual(13.0, r.stop_first_v)
        self.assertIsNone(r.stop_after15_v)


class KindsOfTripsTest(unittest.TestCase):
    def test_a_trip_without_any_voltage_is_named_as_such(self):
        rows = flat(*[cycle(t * 1000, rpm=1500.0, cool=30.0) for t in range(5)])
        r = analyse(rows)
        self.assertEqual("no_voltage", r.kind)
        self.assertEqual(30.0, r.coolant_start)

    def test_a_trip_where_the_engine_never_ran_has_only_the_ignition_on_value(self):
        r = analyse(flat(*[cycle(t * 1000, rpm=0.0, volt=12.2 - 0.1 * t) for t in range(3)]))
        self.assertEqual("never_ran", r.kind)
        self.assertEqual(12.1, round(r.rest_v, 6))
        self.assertIsNone(r.dip_v)
        self.assertIsNone(r.run_v)

    def test_a_trip_that_began_with_a_running_engine_has_no_recorded_start(self):
        r = analyse(flat(*[cycle(t * 1000, rpm=1500.0, volt=13.5) for t in range(30)]))
        self.assertEqual("running_at_start", r.kind)
        self.assertIsNone(r.rest_v)
        self.assertIsNone(r.dip_v)
        self.assertEqual(13.5, r.run_v)

    def test_zero_volts_is_a_missing_answer_not_a_battery_value(self):
        rows = flat(*[cycle(t * 1000, rpm=0.0, volt=0.0) for t in range(4)])
        self.assertEqual("no_voltage", analyse(rows).kind)

    def test_start_time_duration_and_cycle_count(self):
        r = analyse(trip(pre=5, run=20, start_ms=50_000))
        self.assertEqual(50_000, r.start_ms)
        self.assertEqual(25, r.cycles)
        self.assertAlmostEqual(24.2, r.duration_s, places=3)  # vom ersten bis zum letzten Zeilen-Zeitstempel (+0,2 s im letzten Zyklus)

    def test_an_empty_session_is_skipped_by_analyse_all(self):
        self.assertEqual([], bb.analyse_all({1: []}))


class IdleTimeTest(unittest.TestCase):
    def test_idle_time_is_the_gap_between_the_end_of_the_previous_trip_and_the_start(self):
        a = trip(pre=3, run=2, start_ms=1_000_000)
        b = trip(pre=3, run=2, start_ms=1_000_000 + 3_600_000 * 3)
        res = bb.analyse_all({7: b, 5: a})  # die Reihenfolge der Ids entscheidet, nicht die der Eingabe
        self.assertEqual([5, 7], [r.session for r in res])
        self.assertIsNone(res[0].idle_h)
        end_a = max(row[0] for row in a)
        self.assertAlmostEqual((b[0][0] - end_a) / 3_600_000, res[1].idle_h, places=6)

    def test_the_previous_trip_is_the_next_lower_id_even_with_gaps_in_the_ids(self):
        a = trip(pre=3, run=2, start_ms=0)
        b = trip(pre=3, run=2, start_ms=100_000)
        c = trip(pre=3, run=2, start_ms=200_000)
        res = bb.analyse_all({10: a, 20: b, 30: c})
        self.assertAlmostEqual((b[0][0] - max(r[0] for r in a)) / 3_600_000, res[1].idle_h, places=6)
        self.assertAlmostEqual((c[0][0] - max(r[0] for r in b)) / 3_600_000, res[2].idle_h, places=6)


    def test_a_negative_gap_after_a_clock_jump_is_no_idle_time(self):
        a = trip(pre=3, run=2, start_ms=5_000_000)
        b = trip(pre=3, run=2, start_ms=1_000_000)  # laut Zeitstempel VOR dem Ende der vorigen Fahrt
        self.assertIsNone(bb.analyse_all({1: a, 2: b})[1].idle_h)


class ReportTest(unittest.TestCase):
    def _report(self):
        res = bb.analyse_all({
            1: trip(pre=6, dip=8.1, pre_volt=11.9, run=40, stop_volts=[13.1 - 0.075 * i for i in range(20)], start_ms=0),
            2: trip(pre=6, pre_volt=12.1, run=40, start_ms=10_000_000),
            3: flat(*[cycle(t * 1000, rpm=0.0, volt=12.0) for t in range(4)]),
        })
        return bb.format_report(res)

    def test_one_line_per_trip_with_the_numbers(self):
        report = self._report()
        for expected in ("11.9", "8.1", "12.1", "13.5"):
            self.assertIn(expected, report)

    def test_the_report_names_the_date_of_a_trip_but_never_the_time_of_day(self):
        # 1_000_000_000_000 ms = 09.09.2001 01:46:40 UTC: die Uhrzeit darf nirgends stehen (persoenliche Daten, s. Kopf des Skripts)
        report = bb.format_report(bb.analyse_all({1: trip(pre=6, run=30, start_ms=1_000_000_000_000)}))
        self.assertIn("09.09.", report)
        self.assertNotIn("01:46", report)
        self.assertNotIn("2001", report)

    def test_no_line_ends_with_blanks(self):
        for line in self._report().splitlines():
            self.assertEqual(line.rstrip(), line)

    def test_missing_values_are_a_dash_never_a_zero(self):
        for line in self._report().splitlines():
            self.assertNotIn(" 0.0 ", line)
        self.assertIn("–", self._report())

    def test_the_summary_names_ranges_and_how_many_starts_showed_a_dip(self):
        report = self._report()
        self.assertIn("Einbruch erfasst: 1 von 2 Starts", report)
        self.assertIn("Zündung an:", report)
        self.assertIn("laufend:", report)

    def test_idle_time_is_shown_in_minutes_below_an_hour_and_in_hours_above(self):
        res = bb.analyse_all({
            1: trip(pre=3, run=2, start_ms=0),
            2: trip(pre=3, run=2, start_ms=5 * 60_000),
            3: trip(pre=3, run=2, start_ms=5 * 60_000 + 3_600_000 * 62),
        })
        report = bb.format_report(res)
        self.assertIn("5 min", report)
        self.assertIn("62.0 h", report)

    def test_an_empty_input_still_reports_something(self):
        self.assertIn("keine Fahrten", bb.format_report([]))


class MainTest(unittest.TestCase):
    def test_reads_a_file_and_prints_the_report(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "all.csv")
            lines = ["timestamp,pid,value,name,source,session"]
            lines += [f"{t},{pid},{val},x,ECU,9" for t, pid, val in trip(pre=6, pre_volt=12.0, run=30)]
            with open(path, "w") as f:
                f.write("\n".join(lines))
            out = []
            self.assertEqual(0, bb.main([path], out=out.append))
            self.assertIn("12.0", "\n".join(out))

    def test_a_missing_file_or_a_missing_argument_is_a_usage_error(self):
        out = []
        self.assertEqual(2, bb.main([], out=out.append))
        self.assertEqual(2, bb.main(["/does/not/exist.csv"], out=out.append))
        self.assertTrue(out)


if __name__ == "__main__":
    unittest.main()
