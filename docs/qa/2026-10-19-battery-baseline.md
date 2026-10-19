# Batterie-Kennwerte je Fahrt — Basis vom 2026-10-19 (Idee F12)

Ausgangswerte für den **Batterie-Trend** (F12, `docs/ideas/2026-10-17-adventure-enduro-funktionsideen.md`). Eine Funktion in der App gibt es nicht
(Nutzer, 2026-10-19, Entscheidungsprotokoll Punkt 58): der Nutzer exportiert ab und zu die CSV aller Fahrten, Claude rechnet **dieselbe Auswertung**
auf den neuen Export und vergleicht sie mit dieser Basis.

- **Quelle:** Export „alle Fahrten“ der App vom 2026-10-19 (`session_all_….csv`): 13 Fahrten (Ids 45 bis 57, 14. bis 19.10.2026), 46 733 Datenzeilen.
  Die CSV liegt **nicht** im Repository (sie enthält Fahrtzeiten, persönliche Daten); der Bericht nennt je Fahrt nur das Datum (UTC).
- **Aufruf:** `python3 -I scripts/battery_baseline.py <csv>` (Definitionen im Kopf des Skripts, Tests: `scripts/test_battery_baseline.py`, 47 Fälle;
  jede Grenze ist durch einen Test festgehalten — wer eine Konstante ändert, rechnet diese Basis neu).
- **Die Fahrten 55 bis 57** sind Tests des Nutzers am Motorrad im Stand (Angabe, 2026-10-19), Fahrt 56 ohne Start. Alle 13 Fahrten hatten die
  Spannungs-Kachel (Spannung in jedem Zyklus).

## Ausgabe des Skripts

```
Fahrt  Datum    Dauer  Zykl.  Spannung  Kühl.  Zündung an (n)   Anlasser-Einbruch (zum Lauf)  laufend (n)    Abstellen: erste / nach 15 s  Standzeit
                  [s]            -werte  [°C]       [V]                 [V]                      [V]                    [V]                  davor
   45  14.10.     706    682       653     19   11.9 (  6)    10.3 (-2.3 s)              13.7 ( 497)    12.8 /  12.2
   46  14.10.     434    397       382     26   11.9 ( 15)   nicht erfasst               13.7 ( 330)    12.8 /     –           3.7 h
   47  14.10.     421    397       395     85   12.3 ( 22)     7.8 (-0.8 s)              13.7 ( 354)                           2 min
   48  15.10.    1605   1506      1478      9   12.0 (  6)     9.5 (-1.1 s)              13.5 (1408)    11.8 /  11.4           10.6 h
   49  15.10.      96     94        87     17   11.9 ( 34)     9.7 (-2.6 s)              13.7 (  30)                           7.6 h
   50  15.10.    1178   1095      1059     20   11.9 (  6)   nicht erfasst               13.7 ( 962)    12.7 /  12.3           3.4 h
   51  16.10.    1365   1278      1265      9   11.9 ( 32)   nicht erfasst               13.6 (1170)    11.7 /  11.8           12.4 h
   52  16.10.    1228   1022       969     19   11.9 (  5)   nicht erfasst               13.5 ( 951)                           7.5 h
   53  19.10.    1505   1340      1336     10   11.7 ( 11)   nicht erfasst               13.7 (1297)    12.1 /     –           62.6 h
   54  19.10.     103     99        99     19   12.1 ( 14)    11.4 (+0.0 s)              13.7 (  68)                           3.6 h
   55  19.10.      36     38        38     19   12.0 (  8)     6.8 (+0.0 s)              14.0 (  12)                           2.5 h
   56  19.10.      10     12        12     28   12.1 ( 12)   Motor nie gelaufen
   57  19.10.      47     42        42     31   11.9 (  5)     8.1 (-1.1 s)              13.5 (   7)    13.1 /  11.9           1 min

Fahrten: 13, davon mit aufgezeichnetem Start: 12
Zündung an: 11.7 bis 12.3 V
laufend: 13.5 bis 14.0 V
Einbruch erfasst: 7 von 12 Starts (6.8 bis 11.4 V)
```

## Lesehilfe

- **Zündung an:** Median der Spannung vor dem Start, ohne die letzten 3 s (Orgeln). Hängt von Lasten und Standzeit ab: 12,3 V zwei Minuten nach einer
  Fahrt (Fahrt 47), 11,9 V nach 3 bis 12 h, 11,7 V nach 62,6 h (Fahrt 53, ein Punkt).
- **Anlasser-Einbruch:** die tiefste **erfasste** Probe um den Start, nur wenn sie mehr als 0,5 V unter „Zündung an“ liegt. Bei einem Takt von 1,1 bis
  1,6 s wird das Orgeln zufällig getroffen (7 von 12 Starts, je eine Probe, 6,8 bis 11,4 V): **zwischen Fahrten nicht vergleichbar**, kein Maß für die Batterie.
- **Laufend:** Median ab 15 s nach dem Start, 13,5 bis 13,7 V bei allen langen Fahrten (14,0 V bei Fahrt 55 mit nur 12 Proben). Die stabilste Größe,
  misst aber die Ladeanlage, nicht die Batterie.
- **Abstellen:** die erste Probe nach dem Lauf und die bei +15 s (Fahrt 57: 13,1 V → 11,9 V). Hängt von Lüfter und Lasten ab, zu wenige Fälle für eine Aussage.

## Vergleich mit späteren Exporten

1. Neuen Export ziehen, `python3 -I scripts/battery_baseline.py <csv>` laufen lassen.
2. Vergleichen: **laufend** (Bereich und Median), **Zündung an** nur bei ähnlicher Standzeit und ähnlichen Lasten, **Einbruch** nur als „wie oft erfasst“.
3. Ein dauerhaftes Absinken von „laufend“ oder ein Abfall von „Zündung an“ bei gleicher Standzeit über Wochen wäre ein Anlass für eine Gegenprobe
   mit dem Multimeter an der Batterie — die Daten allein sagen nichts über die Gesundheit der Batterie (die Spannung ist die des Steuergeräts, nicht die der Klemme).

## Grenzen

- **Fünf Tage, ein Fahrzeug:** das ist eine Basis, kein Trend. Alterung zeigt sich über Monate; Jahreszeit und Temperatur verschieben die Werte ebenfalls
  (die Starttemperatur steht in der Spalte „Kühl.“).
- **Ohne Spannungs-Kachel** fragt die App die Spannung seit 1.36.0 nur in jedem 4. Zyklus ab (Alarm an): dann wird der Einbruch fast nie getroffen, und die
  Zahl der Proben je Fahrt sinkt auf ein Viertel. Für vergleichbare Exporte die Spannungs-Kachel belegen.
- **Standzeit** ist der Abstand zum Ende der Fahrt mit der nächstkleineren Id; wurde dazwischen eine Fahrt gelöscht, ist sie zu lang.
- Das Skript misst die **Aufzeichnung**, nicht die App; die Rechnung ist unabhängig vom Kotlin-Code (Zyklen werden aus den Zeitstempeln gruppiert, die Datei hat keine Zyklus-Kennung).
