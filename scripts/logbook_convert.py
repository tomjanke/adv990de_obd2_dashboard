#!/usr/bin/env python3
"""Einmaliger Konverter fuer den Altbestand des Logbuchs: macht aus einem Tankprotokoll (Semikolon, Dezimalkomma, Datum mit Uhrzeit) und einer
Freitext-Liste fuer Oel und Kuehlfluessigkeit das CSV, das die App importiert (Spalten date,type,odometer_km,amount_ml,full_tank,cost_eur,note; die Spalte brand der App, nur fuer Reifenwechsel, ist optional und fehlt hier).

    python3 -I scripts/logbook_convert.py --fuel Protokoll.csv --fluids Liste.txt --out logbook_import.csv [--date-for 27150=2024-05-14]

Tankprotokoll: Spalten nach Namen (Datum, Kilometerstand, Kosten, Liter, optional Notiz); jede Tankung gilt als VOLL (die Quelle hat keine Spalte
dafuer, ihre Verbrauchswerte beruhen darauf), die Uhrzeit entfaellt. Unplausible Kosten (Preis je Liter ueber 5 Euro): wird der Preis nach Teilen durch
1000 plausibel (ein fehlendes Komma), wird so korrigiert und die Korrektur AUSGEGEBEN; sonst bleiben die Kosten leer, auch das wird ausgegeben.
Liste: Zeilen `km [Datum] Menge ml Oel|Kuehlfluessigkeit` (Datum wie 7.7.24, 05.07.2025, 05.072025); eine erste Zeile ohne Zahl ist ein Titel. Fehlt
einer Zeile das Datum, nennt `--date-for KM=JJJJ-MM-TT` es, sonst ist es ein Fehler. Bei JEDEM Fehler wird nichts geschrieben (Exit-Code 1).

Das Skript legt nichts im Projekt ab; Eingabe und Ausgabe liegen dort, wo der Nutzer sie angibt. Die echten Daten kommen nie ins Repository.
"""
import argparse
import csv
import io
import re
import sys
from collections import namedtuple
from datetime import date, datetime
from decimal import ROUND_HALF_UP, Decimal, InvalidOperation

COLUMNS = ["date", "type", "odometer_km", "amount_ml", "full_tank", "cost_eur", "note"]
MIN_PRICE = Decimal("0.50")
MAX_PRICE = Decimal("5.00")
MAX_COST = Decimal("999.99")
FUEL_REQUIRED = ("Datum", "Kilometerstand", "Kosten", "Liter")
FUEL_DATE_FORMATS = ("%d.%m.%y, %H:%M", "%d.%m.%y %H:%M", "%d.%m.%Y, %H:%M", "%d.%m.%Y %H:%M", "%d.%m.%y", "%d.%m.%Y")
KINDS = {"öl": "OIL", "kühlflüssigkeit": "COOLANT"}
FLUID_LINE = re.compile(r"^\s*(\d+)\s+(?:(\d{1,2})\.(\d{1,2})\.?(\d{4}|\d{2})\s+)?(\d+)\s*ml\s+(\S+)\s*$", re.IGNORECASE)

Result = namedtuple("Result", "csv report errors")


def parse_decimal(text):
    """Zahl mit Dezimalkomma ("29440,00", "1.234,56") oder Punkt ("12.80"); ValueError, wenn es keine endliche Zahl ist."""
    t = (text or "").strip()
    if "," in t:
        t = t.replace(".", "").replace(",", ".")
    try:
        value = Decimal(t)
    except InvalidOperation:
        raise ValueError(f"keine Zahl: {text!r}")
    if not value.is_finite():
        raise ValueError(f"keine Zahl: {text!r}")
    return value


def parse_fuel_date(text):
    t = (text or "").strip()
    for fmt in FUEL_DATE_FORMATS:
        try:
            return datetime.strptime(t, fmt).date()
        except ValueError:
            pass
    raise ValueError(f"Datum nicht lesbar: {text!r}")


def euro(cents):
    return f"{cents // 100},{cents % 100:02d}"


def resolve_cost(cost, liters):
    """(Kosten oder None, Status): Status ist None, "corrected" (um den Faktor 1000 korrigiert) oder "dropped" (unplausibel, bleibt leer)."""
    if cost <= 0:
        return None, None
    if cost / liters <= MAX_PRICE and cost <= MAX_COST:
        return cost, None
    corrected = cost / 1000
    if MIN_PRICE <= corrected / liters <= MAX_PRICE and corrected <= MAX_COST:
        return corrected, "corrected"
    return None, "dropped"


def parse_fuel(text):
    """Liest das Tankprotokoll: (Zeilen, Korrekturen, verworfene Kosten, Fehler); eine Zeile ist ein dict mit date, type, km, ml, full, cost (Cent), note."""
    rows, corrections, dropped, errors = [], [], [], []
    reader = csv.DictReader(io.StringIO(text), delimiter=";")
    missing = [c for c in FUEL_REQUIRED if c not in (reader.fieldnames or [])]
    if missing:
        return rows, corrections, dropped, [f"Tankprotokoll: Spalte fehlt: {', '.join(missing)}"]
    for line, rec in enumerate(reader, start=2):
        if not any((v or "").strip() for v in rec.values() if isinstance(v, str)):
            continue
        try:
            day = parse_fuel_date(rec["Datum"])
            km = parse_decimal(rec["Kilometerstand"])
            if km != km.to_integral_value() or km < 0:
                raise ValueError(f"Kilometerstand keine ganze Zahl: {rec['Kilometerstand']!r}")
            liters = parse_decimal(rec["Liter"])
            ml = int((liters * 1000).to_integral_value(ROUND_HALF_UP))
            if ml <= 0:
                raise ValueError(f"Liter nicht groesser als 0: {rec['Liter']!r}")
            cost, status = resolve_cost(parse_decimal(rec["Kosten"]), Decimal(ml) / 1000)
        except ValueError as e:
            errors.append(f"Tankprotokoll Zeile {line}: {e}")
            continue
        cents = int((cost * 100).quantize(Decimal(1), ROUND_HALF_UP)) if cost is not None else None
        if status == "corrected":
            corrections.append(f"Tankprotokoll Zeile {line}: Kosten {rec['Kosten'].strip()} -> {euro(cents)} (Preis je Liter war unplausibel, Faktor 1000)")
        elif status == "dropped":
            dropped.append(f"Tankprotokoll Zeile {line}: Kosten {rec['Kosten'].strip()} nicht plausibel, bleiben leer")
        rows.append({"date": day, "type": "FUEL", "km": int(km), "ml": ml, "full": "1", "cost": cents, "note": (rec.get("Notiz") or "").strip()})
    return rows, corrections, dropped, errors


def parse_fluids(text, date_for):
    """Liest die Liste fuer Oel und Kuehlfluessigkeit: (Zeilen, Fehler). `date_for` ordnet einem Kilometerstand ein Datum zu (fuer Zeilen ohne Datum)."""
    rows, errors = [], []
    first = True
    for number, raw in enumerate(text.splitlines(), start=1):
        if not raw.strip():
            continue
        is_first, first = first, False
        m = FLUID_LINE.match(raw)
        if not m:
            if is_first and not raw.lstrip()[:1].isdigit():
                continue  # Titelzeile
            errors.append(f"Liste Zeile {number}: nicht lesbar: {raw.strip()!r}")
            continue
        km, d, mo, y, ml, kind = m.groups()
        km, ml = int(km), int(ml)
        kind_name = KINDS.get(kind.casefold())
        if kind_name is None:
            errors.append(f"Liste Zeile {number}: unbekannte Art {kind!r} (erwartet Öl oder Kühlflüssigkeit)")
            continue
        if d is None:
            day = date_for.get(km)
            if day is None:
                errors.append(f"Liste Zeile {number}: kein Datum bei Stand {km}; mit --date-for {km}=JJJJ-MM-TT angeben")
                continue
        else:
            year = int(y) + 2000 if len(y) == 2 else int(y)
            try:
                day = date(year, int(mo), int(d))
            except ValueError:
                errors.append(f"Liste Zeile {number}: Datum nicht lesbar: {d}.{mo}.{y}")
                continue
        rows.append({"date": day, "type": kind_name, "km": km, "ml": ml, "full": None, "cost": None, "note": ""})
    return rows, errors


def cost_text(cents):
    return "" if cents is None else f"{cents // 100}.{cents % 100:02d}"


def convert(fuel_text, fluids_text, date_for):
    """Beide Quellen zu einem CSV im Format der App; Zeilen nach Datum, Stand und Art sortiert. Bei Fehlern ist das CSV leer."""
    rows, report, errors = [], [], []
    if fuel_text is not None:
        fuel_rows, corrections, dropped, fuel_errors = parse_fuel(fuel_text)
        rows += fuel_rows
        errors += fuel_errors
        report.append(f"Tankungen: {len(fuel_rows)}")
        report += corrections + dropped
    if fluids_text is not None:
        fluid_rows, fluid_errors = parse_fluids(fluids_text, date_for)
        rows += fluid_rows
        errors += fluid_errors
        report.append(f"Öl: {sum(r['type'] == 'OIL' for r in fluid_rows)}, Kühlflüssigkeit: {sum(r['type'] == 'COOLANT' for r in fluid_rows)}")
    if errors:
        return Result("", report, errors)
    rows.sort(key=lambda r: (r["date"], r["km"], r["type"]))
    out = io.StringIO()
    writer = csv.writer(out, lineterminator="\n")
    writer.writerow(COLUMNS)
    for r in rows:
        writer.writerow([r["date"].isoformat(), r["type"], r["km"], r["ml"], r["full"] or "", cost_text(r["cost"]), r["note"]])
    return Result(out.getvalue(), report, errors)


def parse_date_for(values):
    result = {}
    for value in values:
        km_text, sep, day_text = value.partition("=")
        if not sep or not km_text.isdigit():
            raise ValueError(f"--date-for erwartet KM=JJJJ-MM-TT, nicht {value!r}")
        try:
            result[int(km_text)] = date.fromisoformat(day_text)
        except ValueError:
            raise ValueError(f"--date-for erwartet KM=JJJJ-MM-TT, nicht {value!r}")
    return result


def read(path):
    if path is None:
        return None
    with open(path, encoding="utf-8-sig") as f:
        return f.read()


def main(argv=None):
    parser = argparse.ArgumentParser(description="Altbestand fuer das Logbuch der OBD2-App in das Import-CSV umwandeln.")
    parser.add_argument("--fuel", help="Tankprotokoll (CSV mit Semikolon)")
    parser.add_argument("--fluids", help="Liste fuer Oel und Kuehlfluessigkeit (Text)")
    parser.add_argument("--out", required=True, help="Ausgabedatei (wird nur bei Erfolg geschrieben)")
    parser.add_argument("--date-for", action="append", default=[], metavar="KM=JJJJ-MM-TT", help="Datum fuer eine Listenzeile ohne Datum")
    args = parser.parse_args(argv)
    if not args.fuel and not args.fluids:
        parser.error("--fuel und/oder --fluids angeben")
    try:
        date_for = parse_date_for(args.date_for)
    except ValueError as e:
        parser.error(str(e))
    try:
        fuel_text, fluids_text = read(args.fuel), read(args.fluids)
    except (OSError, UnicodeDecodeError) as e:
        print(f"FEHLER: Eingabe nicht lesbar: {e}", file=sys.stderr)
        return 1
    result = convert(fuel_text, fluids_text, date_for)
    for line in result.report:
        print(line)
    if result.errors:
        for error in result.errors:
            print(f"FEHLER: {error}", file=sys.stderr)
        print("Nichts geschrieben.", file=sys.stderr)
        return 1
    with open(args.out, "w", encoding="utf-8", newline="") as f:
        f.write(result.csv)
    print(f"Geschrieben: {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
