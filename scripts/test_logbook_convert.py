#!/usr/bin/env python3
"""Tests von scripts/logbook_convert.py (erfundene Daten, nichts vom Nutzer).

    python3 -I scripts/test_logbook_convert.py

Der JVM-Test `LogbookConvertScriptTest` fuehrt diese Datei aus und liest die erwartete Ausgabe zusaetzlich mit dem Leser der App.
"""
import csv
import importlib.util
import io
import os
import tempfile
import unittest
from datetime import date

_HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location("logbook_convert", os.path.join(_HERE, "logbook_convert.py"))
lc = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(lc)

FIXTURES = os.path.join(_HERE, "..", "app", "src", "test", "resources", "logbook")

FUEL_HEADER = "Fahrzeug;Datum;Kilometerstand;Kosten;Liter;Verbrauch pro 100 km;Kosten pro 100 km;Preis pro Liter;Notiz\n"


def fuel(*rows):
    return FUEL_HEADER + "".join(rows)


def read_fixture(name):
    with open(os.path.join(FIXTURES, name), encoding="utf-8") as f:
        return f.read()


class FuelTest(unittest.TestCase):
    def test_a_row_is_converted_with_comma_decimals_and_no_time(self):
        rows, corrections, dropped, errors = lc.parse_fuel(fuel("Moto;12.05.23, 17:40;18204,00;31,96;15,02;0,00;0,00;2,13;\n"))
        self.assertEqual([], errors)
        self.assertEqual(1, len(rows))
        r = rows[0]
        self.assertEqual((date(2023, 5, 12), "FUEL", 18204, 15020, "1", 3196), (r["date"], r["type"], r["km"], r["ml"], r["full"], r["cost"]))
        self.assertEqual(([], []), (corrections, dropped))

    def test_every_fill_counts_as_full_because_the_source_has_no_column(self):
        rows, *_ = lc.parse_fuel(fuel("M;01.03.24, 18:00;1000,00;20,00;10,00;0;0;2,00;\nM;02.03.24, 18:00;1100,00;5,00;2,50;0;0;2,00;\n"))
        self.assertEqual(["1", "1"], [r["full"] for r in rows])

    def test_the_note_is_carried_over(self):
        rows, *_ = lc.parse_fuel(fuel("M;01.03.24, 18:00;1000,00;20,00;10,00;0;0;2,00;Autobahn\n"))
        self.assertEqual("Autobahn", rows[0]["note"])

    def test_a_cost_with_a_missing_comma_is_corrected_and_reported(self):
        rows, corrections, dropped, errors = lc.parse_fuel(fuel("M;02.03.26, 19:05;27410,00;29440,00;12,80;7,56;10741,60;2300,00;\n"))
        self.assertEqual([], errors)
        self.assertEqual(2944, rows[0]["cost"])  # 29,440 EUR -> 29,44 EUR
        self.assertEqual(1, len(corrections))
        self.assertIn("2", corrections[0])  # Zeile 2
        self.assertIn("29440", corrections[0])
        self.assertIn("29,44", corrections[0])

    def test_a_cost_that_cannot_be_corrected_stays_empty_and_is_reported(self):
        rows, corrections, dropped, errors = lc.parse_fuel(fuel("M;02.03.26, 19:05;27410,00;999999,00;10,00;7,56;0;0;\n"))
        self.assertEqual([], errors)
        self.assertIsNone(rows[0]["cost"])
        self.assertEqual([], corrections)
        self.assertEqual(1, len(dropped))

    def test_a_normal_cost_and_a_free_fill_are_left_alone(self):
        rows, corrections, dropped, _ = lc.parse_fuel(fuel("M;01.03.24, 18:00;1000,00;30,00;12,00;0;0;2,50;\nM;02.03.24, 18:00;1100,00;0,00;5,00;0;0;0;\n"))
        self.assertEqual([3000, None], [r["cost"] for r in rows])
        self.assertEqual(([], []), (corrections, dropped))

    def test_a_cheap_price_is_not_touched(self):
        rows, corrections, *_ = lc.parse_fuel(fuel("M;01.03.24, 18:00;1000,00;4,00;10,00;0;0;0,40;\n"))
        self.assertEqual(400, rows[0]["cost"])
        self.assertEqual([], corrections)

    def test_unreadable_rows_are_reported_with_their_line_number(self):
        rows, _, _, errors = lc.parse_fuel(fuel(
            "M;01.03.24, 18:00;1000,00;20,00;10,00;0;0;2;\n",
            "M;nope;1100,00;20,00;10,00;0;0;2;\n",
            "M;03.03.24, 18:00;11x0,00;20,00;10,00;0;0;2;\n",
            "M;04.03.24, 18:00;1200,50;20,00;10,00;0;0;2;\n",
            "M;05.03.24, 18:00;1300,00;20,00;0,00;0;0;2;\n",
        ))
        self.assertEqual(1, len(rows))
        self.assertEqual([3, 4, 5, 6], [int(e.split("Zeile ")[1].split(":")[0]) for e in errors])

    def test_a_missing_column_is_an_error(self):
        _, _, _, errors = lc.parse_fuel("Datum;Liter\n01.03.24, 18:00;10,00\n")
        self.assertEqual(1, len(errors))
        self.assertIn("Kilometerstand", errors[0])

    def test_blank_lines_are_ignored(self):
        rows, _, _, errors = lc.parse_fuel(fuel("M;01.03.24, 18:00;1000,00;20,00;10,00;0;0;2;\n", "\n"))
        self.assertEqual((1, []), (len(rows), errors))

    def test_decimal_helper(self):
        self.assertEqual(lc.Decimal("29440.00"), lc.parse_decimal("29440,00"))
        self.assertEqual(lc.Decimal("1234.56"), lc.parse_decimal("1.234,56"))
        self.assertEqual(lc.Decimal("12.80"), lc.parse_decimal("12.80"))
        for bad in ("abc", "", "NaN", "Infinity"):
            with self.assertRaises(ValueError):
                lc.parse_decimal(bad)


class FluidsTest(unittest.TestCase):
    def parse(self, text, date_for=None):
        return lc.parse_fluids(text, date_for or {})

    def test_the_date_formats_of_the_list(self):
        rows, errors = self.parse("T\n1000 7.7.24 100ml Kühlflüssigkeit\n1100 05.07.2025 200ml Öl\n1200 05.072025 200ml Öl\n1300 12.08.24 150ml Öl\n")
        self.assertEqual([], errors)
        self.assertEqual([date(2024, 7, 7), date(2025, 7, 5), date(2025, 7, 5), date(2024, 8, 12)], [r["date"] for r in rows])

    def test_kinds_amounts_and_stands(self):
        rows, errors = self.parse("T\n28410 7.7.24 100ml Kühlflüssigkeit\n28733 9.8.24 125ml Öl\n")
        self.assertEqual([], errors)
        self.assertEqual([("COOLANT", 28410, 100), ("OIL", 28733, 125)], [(r["type"], r["km"], r["ml"]) for r in rows])

    def test_a_line_without_a_date_is_an_error_unless_a_date_is_given(self):
        text = "Titel\n27150 200ml Öl\n"
        _, errors = self.parse(text)
        self.assertEqual(1, len(errors))
        self.assertIn("27150", errors[0])
        self.assertIn("--date-for", errors[0])
        rows, errors = self.parse(text, {27150: date(2024, 5, 14)})
        self.assertEqual(([], date(2024, 5, 14)), (errors, rows[0]["date"]))

    def test_the_title_line_is_skipped_but_other_unreadable_lines_are_reported(self):
        rows, errors = self.parse("Öl & Kühlflüssigkeit KTM\n1000 5.1.24 100ml Öl\nkaputt\n1100 6.1.24 100ml Öl\n")
        self.assertEqual(2, len(rows))
        self.assertEqual(1, len(errors))
        self.assertIn("Zeile 3", errors[0])

    def test_a_first_line_that_starts_with_a_number_is_data_not_a_title(self):
        _, errors = self.parse("1000 kaputt\n1100 6.1.24 100ml Öl\n")
        self.assertEqual(1, len(errors))
        self.assertIn("Zeile 1", errors[0])

    def test_an_unknown_kind_and_an_impossible_date_are_errors(self):
        _, errors = self.parse("T\n1000 5.1.24 100ml Bremsflüssigkeit\n1100 31.2.24 100ml Öl\n")
        self.assertEqual(2, len(errors))

    def test_oil_and_coolant_at_the_same_stand_are_two_rows(self):
        rows, errors = self.parse("T\n30960 16.04.25 50ml Kühlflüssigkeit\n30960 16.04.2025 250ml Öl\n")
        self.assertEqual(([], 2), (errors, len(rows)))


class ConvertTest(unittest.TestCase):
    def test_the_sample_files_give_the_expected_csv(self):
        result = lc.convert(read_fixture("sample_fuel.csv"), read_fixture("sample_fluids.txt"), {1100: date(2024, 3, 5)})
        self.assertEqual([], result.errors)
        self.assertEqual(read_fixture("sample_expected.csv"), result.csv)

    def test_the_report_names_counts_corrections_and_dropped_costs(self):
        result = lc.convert(read_fixture("sample_fuel.csv"), read_fixture("sample_fluids.txt"), {1100: date(2024, 3, 5)})
        report = "\n".join(result.report)
        self.assertIn("4", report)  # vier Tankungen
        self.assertIn("27500", report)  # die Korrektur
        self.assertIn("999999", report)  # die verworfenen Kosten

    def test_a_note_with_a_comma_or_quote_survives_the_csv(self):
        text = fuel('M;01.03.24, 18:00;1000,00;20,00;10,00;0;0;2;"voll, Shell ""24"""\n')
        result = lc.convert(text, None, {})
        rows = list(csv.reader(io.StringIO(result.csv)))
        self.assertEqual('voll, Shell "24"', rows[1][6])

    def test_either_file_alone_is_fine(self):
        self.assertEqual([], lc.convert(None, "T\n1000 5.1.24 100ml Öl\n", {}).errors)
        self.assertEqual([], lc.convert(fuel("M;01.03.24, 18:00;1000,00;20,00;10,00;0;0;2;\n"), None, {}).errors)

    def test_rows_are_sorted_by_date_then_stand_then_kind(self):
        text = "T\n1200 9.1.24 50ml Öl\n1000 5.1.24 50ml Öl\n1000 5.1.24 20ml Kühlflüssigkeit\n"
        lines = lc.convert(None, text, {}).csv.splitlines()[1:]
        self.assertEqual(["2024-01-05,COOLANT,1000,20,,,", "2024-01-05,OIL,1000,50,,,", "2024-01-09,OIL,1200,50,,,"], lines)


class CommandLineTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)

    def path(self, name):
        return os.path.join(self.dir.name, name)

    def write(self, name, text):
        with open(self.path(name), "w", encoding="utf-8") as f:
            f.write(text)
        return self.path(name)

    def test_success_writes_the_file_and_exits_zero(self):
        out = self.path("out.csv")
        code = lc.main(["--fuel", self.write("f.csv", read_fixture("sample_fuel.csv")), "--fluids", self.write("o.txt", read_fixture("sample_fluids.txt")),
                        "--date-for", "1100=2024-03-05", "--out", out])
        self.assertEqual(0, code)
        with open(out, encoding="utf-8") as f:
            self.assertEqual(read_fixture("sample_expected.csv"), f.read())

    def test_an_error_writes_nothing_and_exits_one(self):
        out = self.path("out.csv")
        code = lc.main(["--fluids", self.write("o.txt", "T\n1100 150ml Öl\n"), "--out", out])
        self.assertEqual(1, code)
        self.assertFalse(os.path.exists(out))

    def test_a_byte_order_mark_at_the_start_of_a_file_is_ignored(self):
        out = self.path("out.csv")
        path = self.path("f.csv")
        with open(path, "w", encoding="utf-8-sig") as f:
            f.write(fuel("M;01.03.24, 18:00;1000,00;20,00;10,00;0;0;2;\n"))
        self.assertEqual(0, lc.main(["--fuel", path, "--out", out]))

    def test_a_bad_date_for_argument_is_refused(self):
        for bad in ("1100", "x=2024-03-05", "1100=05.03.2024", "1100=2024-13-01"):
            with self.assertRaises(SystemExit):
                lc.main(["--fluids", self.write("o.txt", "T\n"), "--date-for", bad, "--out", self.path("o.csv")])

    def test_one_input_is_required(self):
        with self.assertRaises(SystemExit):
            lc.main(["--out", self.path("o.csv")])


if __name__ == "__main__":
    unittest.main(verbosity=1)
