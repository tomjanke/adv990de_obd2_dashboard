# Funktionsideen für Adventure/Enduro (Stand 2026-10-17)

**Das ist kein Backlog.** Die Liste sammelt mögliche neue Funktionen; **nichts davon ist beschlossen oder begonnen**, ein Auftrag kommt nur vom Nutzer. Sie wird gepflegt (neue Erkenntnisse, Antworten des Nutzers), der Backlog (offene Aufgaben) steht getrennt davon. Die Einschätzungen sind die von Claude, nicht aus Quellen belegt; was Daten des Nutzers belegen, steht in `docs/qa/2026-10-17-voltage-analysis.md`.

Anlass: Frage des Nutzers vom 2026-10-17, welche Funktionen für ein Adventure/Enduro-Motorrad denkbar, nützlich oder unerlässlich sind, wenn die App primär Fahrzeugdaten während der Fahrt anzeigt. Der Nutzer fand die erste Liste „schon sehr hilfreich“.

## Kontext des Nutzers (Angaben 2026-10-17)

- **Navigation läuft getrennt** (anderes Gerät) → ein Overlay oder Bild-in-Bild über einer Navigations-App ist nicht nötig; das Dashboard bleibt im Vordergrund.
- **Zusatzverbraucher:** Heizgriffe (**die Fahrten morgens laufen häufig mit Heizgriffen in mittlerer Stufe**) und ein zweiter Lüfter; die 88 °C im Stand sind laut Nutzer wahrscheinlich das Zuschalten des zweiten Lüfters, und die Spannung normalisiert sich beim Abschalten oder Fahren (Angaben 2026-10-17; die Daten bestätigen das überwiegend, s. Auswertung).
- **Kein Headset:** ein Warnton am Gerät ist zunächst ok. *(Einschätzung: bei Fahrtwind und Motorgeräusch ist ein Ton vom Lenker schwer zu hören; deshalb zuerst ein starkes visuelles Signal, der Ton als Zusatz; die Wirksamkeit am Motorrad prüft der Nutzer.)*
- Querformat ist die Hauptansicht, Bedienung mit Handschuhen (56 dp, keine Wischgesten an kritischen Stellen).
- **Was das Motorrad per OBD2 liefert (belegt):** Drehzahl, Kühlmittel, Spannung (`0142`); Geschwindigkeit nur per GPS; keine Kraftstoff-Werte. Alles andere ist unbekannt.

## Was die Auswertung der Fahrten ergab (Kurzfassung)

9 Fahrten, 141 min, kühle Jahreszeit; Details und Grenzen: `docs/qa/2026-10-17-voltage-analysis.md`.

- Bei laufendem Motor liegt die Spannung bei **13,6–13,7 V**. Im Stand **bricht sie ab etwa 88 °C Kühlmittel auf 12,1–12,8 V ein** (bei 90 °C in 100 % der Zyklen unter 13,0 V) — das Muster eines zuschaltenden Verbrauchers, vermutlich des Lüfters. Die Phasen dauern bis zu 47 s; tiefster Wert 11,6 V.
- **Fast alle Einbrüche stammen aus den drei Morgenfahrten** (17 von 19 Phasen unter 13,0 V ≥ 10 s; tiefster Wert dort 11,6 V, in den übrigen sechs Fahrten 12,1 V): im Lüfterband liegt die Spannung morgens 0,5–0,7 V tiefer. Das passt zu **Heizgriffen und Lüfter zusammen**, ist aber nicht bewiesen (Einteilung nach Tageszeit, kleine Stichprobe, kältere Luft morgens).
- Das Kühlmittel erreicht **bei laufendem Motor höchstens 90 °C**; der Lüfter hält es dort. Die heutigen Kachelschwellen (Spannung < 11,8 V, Kühlmittel > 110 °C) lösen in diesen Fahrten praktisch nie aus.
- Einzelwerte sind verrauscht (Sprünge ≥ 0,4 V in 5,8 % der Zyklen): ein Alarm braucht ein **Dauerkriterium**.
- **Nicht belegt:** eine Entladung über Minuten (Stau, langsames technisches Fahren mit Lüfter und Heizgriffen im Leerlauf — die längste Phase unter 13,0 V dauerte 47 s), Sommer- und Geländebetrieb, der Zustand der Batterie, welche Fahrt genau mit Heizgriffen lief.

## Ideen, nach Empfehlung geordnet

Aufwand grob: klein / mittel / groß (Schätzung, nicht gemessen).

### Aus Claudes Sicht fast unerlässlich (Sicherheit und Reichweite abseits der Straße)

**F1 — Alarme für Spannung und Kühlmittel** (mittel). Sichtbares Signal (Rand oder Vollbild blinkt) plus Ton am Gerät; Schwellen einstellbar, **mit Dauerkriterium statt Einzelwert**. Der Lüfter im Stand (12,1–12,8 V bis 47 s) ist Normalverhalten und darf nicht alarmieren. Kandidaten aus den Daten (Tabelle im Auswertungs-Dokument; **mit Heizgriffen ist „unter 12,5 V für ≥ 10 s“ Alltag und zu empfindlich**): Spannung Hinweis unter 12,5 V ≥ 20 s (1 Auslösung in 9 Fahrten), Warnung unter 12,2 V ≥ 10 s (0), „Entladung über Minuten“ unter 13,0 V ≥ 3 min (0); Kühlmittel Hinweis ≥ 95 °C und Warnung ≥ 100 °C bei laufendem Motor (0; nur Abstand zum Regelpunkt 90 °C, ohne Sommerdaten nicht abgesichert). Voraussetzung: die Spannung wird heute nur abgefragt, wenn sie als Kachel belegt ist (in den ausgewerteten Fahrten war sie es); ein Alarm müsste sie immer abfragen (Zykluszeit, jeder PID etwa 0,15–0,28 s, Protokoll Punkt 25). **Status (2026-10-18): umgesetzt, released als 1.36.0** — auf Auftrag des Nutzers („Bitte F1 und F4 konzeptionieren, planen und umsetzen“, Protokoll Punkt 51): Alarmband plus Systembenachrichtigung statt Blinken und Ton am Gerät allein, Schwellen wählbar, Spannung alle 4 Zyklen abgefragt, standardmäßig an; die Schwellen sind die der Kandidaten oben. F2 und F3 bleiben Ideen. Am Fahrzeug nichts geprüft.

**F2 — Lüfter-Ausfall erkennen** — **gestrichen (Nutzer, 2026-10-19: „zu speziell“, Protokoll Punkt 59).** Ursprüngliche Beschreibung (klein, Teil von F1): Das Kühlmittel steigt bei laufendem Motor über den Regelpunkt (hier 90 °C) und die Spannungs-Signatur des Lüfters fehlt: ein ausgefallener oder hängender Lüfter (Sicherung, Relais, Stecker) ist unterwegs sonst erst bei Überhitzung zu bemerken. Folgerung aus den Daten, am Fahrzeug nicht erprobt.

**F3 — Lade-/Entlade-Anzeige** (klein bis mittel). Geglättete Spannung (zum Beispiel über 10 s) mit Zustand „lädt“ (≥ 13,3 V), „lädt kaum“ und „entlädt“, damit man im Stand mit Lüfter und Heizgriffen sieht, wohin es geht. Optional eine **abgeleitete „Lüfter läuft“-Anzeige** aus Kühlmittel und Spannungseinbruch — das ist ein Schluss, kein gemessener Zustand, und müsste so gekennzeichnet sein. **Status (2026-10-19): umgesetzt ohne die „Lüfter läuft“-Anzeige, released als 1.37.0** — auf Auftrag des Nutzers (Protokoll Punkt 53): kein Wort-Zustand, sondern ein senkrechter Balken links in der Spannungs-Kachel (Skala 11–15 V, Nulllinie 12,7 V, grün ab 13,3 V, gelb bis 12,7 V, rot darunter, 10 s geglättet, nur bei laufendem Motor); „Lüfter läuft“ nicht gebaut. Am Fahrzeug nichts geprüft.

**F4 — Fehler unterwegs erkennen** (klein bis mittel). Die App liest Fehlercodes nur beim Verbinden (Grenze von 1.33.0); ein Code, der mitten in der Fahrt auftritt, bleibt unbemerkt. Abseits der Zivilisation wäre eine Meldung wichtig, etwa durch Auslesen alle 60 s (Modus 03, nicht in jedem Zyklus, wegen der Zykluszeit) und eine Benachrichtigung bei neuem Code. **Status (2026-10-18): umgesetzt, released als 1.36.0** (zusammen mit F1, Protokoll Punkt 51): Auslesen alle 60 s (10 s nach einer Änderung), ein neuer Code zählt erst in zwei aufeinanderfolgenden Auslesungen, Alarm bis zum Quittieren; der ECU-Simulator meldet nie Fehlercodes, F4 ist nur gegen eingespielte Antworten getestet.

**F5 — Reichweite** (mittel). Heute zehn Segmente und ein Zurücksetzen auf „voll“. Sinnvoll: Restkilometer als Zahl, eine Reserve-Marke, Teilbetankung mit Litereingabe → Verbrauch (l/100 km) und Reichweite lernen. Ohne Kraftstoff-Werte vom Motorrad bleibt es ein Streckenzähler.

### Sehr nützlich, überschaubarer Aufwand

**F6 — Trip-Computer, Betriebsstunden und Wartungserinnerungen** (klein). Trip A/B, Durchschnitt, Standzeit; Betriebsstunden (Enduros werden oft nach Stunden gewartet: Luftfilter, Öl); Kettenpflege nach Kilometern. Distanzintegrator und Fahrtdauer gibt es schon. **In der Fassung des Nutzers beschlossen und Phase 1 umgesetzt (2026-10-20 bis 2026-10-25, Entscheidungsprotokoll Punkt 63):** ein **Logbuch** mit Datum und Kilometerstand (Basis Kilometer, nicht Betriebsstunden; Trip A/B und Betriebsstunden entfallen) für Tanken, Öl, Kühlflüssigkeit, Kettenpflege, Service, Reifen- und Kettenkit-Wechsel, CSV-Export und -Import; released als 1.42.0. **Noch offen:** die Auswertung mit Diagramm (Phase 2) und Erinnerungen (Phase 3), beides nicht bestellt.

**F7 — Höhe, Steigung und Peilung** (mittel). Aus Barometer und GPS des Pixel, offline. **Beschlossen und umgesetzt (2026-10-18, Entscheidungsprotokoll Punkt 49):** eine Kachel „Gelände“ — Höhe groß, Steigung als Graph der letzten 1000 m, Peilung als Windrose; released als 1.35.0, am Motorrad nicht geprüft. Entwurf und Plan: `docs/superpowers/archive/specs|plans/2026-10-18-terrain-tile*`.

**F8 — Strecke aufzeichnen (GPX) und Position zu Fahrten und Fehlern** (mittel). Heute speichert die App keine Positionen, nur die Geschwindigkeit. Positionen sind sensible Daten (lokal halten, nie ins Repository). Navigation selbst ist kein Ziel.

**F9 — Handy-Zustand** (klein). Akku, Laden oder Entladen, Temperatur (der Pixel drosselt in praller Sonne).

**F10 — Profile Straße und Gelände** (mittel). Andere Kachelbelegung und Schwellen per Knopf, ohne Wischgeste.

**F11 — Min/Max je Fahrt** (klein; war „nur mit ausdrücklichem Ja“). **Status (2026-10-19): umgesetzt, released als 1.38.0** — der Nutzer sagte Ja und wünschte Kühlmittel **und** Spannung jeweils Min **und** Max (Entscheidungsprotokoll Punkt 54); die Spannung zählt nur bei laufendem Motor (meine Auslegung), s. CLAUDE.md „Min/Max je Fahrt im Detail-Dialog“. Ursprüngliche Beschreibung: Kühlmittel max und Spannung min im Detail-Dialog einer Fahrt: zwei Zahlen, kein Diagramm. Zeigt, ob der Lüfter reicht und die Heizgriffe die Spannung drücken; rechenbar aus vorhandenen Messwerten, auch für ältere Fahrten, die beides aufgezeichnet haben. Messwert-Diagramme je Fahrt hat der Nutzer abgelehnt (Protokoll Punkt 46).

**F12 — Batterie-Trend** (klein; neu aus der Auswertung). Spannung bei Zündung an und Einbruch beim Start je Fahrt festhalten, um eine alternde Batterie am Verlauf zu erkennen. Grenzen: Abstand der Zyklen 1,1–1,6 s (Anlasser-Spitzen nur zufällig getroffen; bessere Werte bräuchten kurzes schnelles Abfragen beim Start); die Steuergerätespannung ist nicht die Klemmenspannung; die ausgewerteten Fahrten erlauben **keine** Aussage zur Batteriegesundheit (Gegenprobe: Multimeter). **Status (2026-10-19): Ausgangswerte aus der CSV aller Fahrten abgeleitet, noch keine Funktion** (auf Frage des Nutzers; 13 Fahrten vom 14. bis 19.10.2026, alle mit Spannungs-Kachel, Auswertung per Wegwerf-Skript, nicht aufbewahrt): **Zündung an** 11,7 bis 12,3 V (Median je Fahrt, 5 bis 34 Proben; 12,3 V zwei Minuten nach einer Fahrt, 11,9 V nach 3 bis 12 h Stand, 11,7 V nach 62,6 h — ein Punkt); **laufend** 13,5 bis 13,7 V (Median ab 15 s nach dem Start; 14,0 V bei nur 12 Proben); **Anlasser-Einbruch** in 7 von 12 Starts erfasst (6,8 bis 11,4 V, je **eine** Probe, kein erkennbarer Zusammenhang mit der Kühlmittel-Starttemperatur; bei den übrigen fiel keine Probe in die Orgelphase). **Kein Trend sichtbar** — bei fünf Tagen erwartbar, Alterung zeigt sich über Monate. Die Einbrüche sind mit 1,1 bis 1,6 s Zyklus nicht vergleichbar (zufällig getroffen); ohne Spannungs-Kachel fragt die Pipeline die Spannung seit 1.36.0 nur in jedem 4. Zyklus ab, dann würden sie fast nie getroffen. Die stabilste Größe ist „laufend“, sie misst aber die Ladeanlage, nicht die Batterie; „Zündung an“ hängt von Lasten und Standzeit ab. Nichts gebaut. **Vereinbart (Nutzer, 2026-10-19, Protokoll Punkt 58): F12 wird vorerst nicht als App-Funktion gebaut. Bis dahin reicht es, ab und zu die CSV zu exportieren; Claude wendet dieselbe Rechnung auf spätere Exporte an und vergleicht sie mit dieser Basis.** Die Rechnung steht als getestetes Skript im Repository: `scripts/battery_baseline.py` (Tests: `scripts/test_battery_baseline.py`), die Basis mit der Ausgabe in `docs/qa/2026-10-19-battery-baseline.md` (Auswertung statt Wegwerf-Skript: jede Grenze ist festgehalten, damit spätere Exporte mit denselben Definitionen gerechnet werden).

### Denkbar, aber aufwendig oder unsicher

**F13 — Neigungsanzeige** aus den Lagesensoren (Montage und Kalibrierung nötig). **F14 — Reifendruck** per Bluetooth-Sensor (Sensorprotokolle proprietär; die App kann kein BLE mehr, der Code wurde entfernt). **F15 — Sturzerkennung mit Alarm an einen Kontakt** (Fehlalarme im Gelände, heikle Berechtigungen, Haftung).

### Entfallen oder nicht empfohlen

- **Overlay über der Navigations-App:** entfällt (Navigation läuft getrennt).
- Nicht empfohlen: Schaltempfehlung (im Gelände unzuverlässig), Cloud-Funktionen.
- **F2 (Lüfter-Ausfall erkennen):** gestrichen am 2026-10-19 (Nutzer: zu speziell). Der Kühlmittel-Alarm aus F1 deckt die Folge eines ausgefallenen Lüfters ab (Überhitzung).

## Antworten des Nutzers auf die offenen Fragen (2026-10-17)

1. **Heizgriffe je Fahrt:** „kann ich nicht genau sagen“ — der Zusammenhang mit den Morgenfahrten bleibt wahrscheinlich, nicht belegt.
2. **Sommer- oder Geländefahrten mit Aufzeichnung:** keine. Die Kühlmittel-Schwellen und die Entladung über Minuten sind damit vorerst nicht absicherbar.
3. **F1 (Alarme) als Start:** **erstmal nein.** F1 bis F3 ruhen.
4. **Geändert am 2026-10-18:** der Nutzer beauftragte F1 und F4 („Bitte F1 und F4 konzeptionieren, planen und umsetzen“); sie sind released (1.36.0, s. Status bei F1 und F4). F2 und F3 ruhen weiter. (Später: F3 am 2026-10-19 umgesetzt, F2 am 2026-10-19 gestrichen.)

## Vorrang des Nutzers: Export der Fahrten einfacher machen — umgesetzt

Der Nutzer nannte es **wichtiger** als diese Ideen, die Fahrtaufzeichnungen einfacher exportieren zu können, zum Beispiel **alle Fahrten in einer CSV**. Das war kein Eintrag dieser Liste, sondern ein Auftrag; umgesetzt in **1.34.0**: Export-Symbol in der Kopfleiste des Bereichs „Fahrten“, alle abgeschlossenen Fahrten in einer Datei mit der Spalte `session`, **Teilen oder auf dem Gerät speichern** (Entscheidungsprotokoll Punkt 48, `README.md` Abschnitt CSV-Export, `CLAUDE.md` „CSV-Export“). Als Auswertungs-Hilfe: mit „Speichern“ liegt die Datei lokal auf dem Gerät, ohne Cloud-Umweg.
