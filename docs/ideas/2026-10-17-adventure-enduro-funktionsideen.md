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

**F1 — Alarme für Spannung und Kühlmittel** (mittel). Sichtbares Signal (Rand oder Vollbild blinkt) plus Ton am Gerät; Schwellen einstellbar, **mit Dauerkriterium statt Einzelwert**. Der Lüfter im Stand (12,1–12,8 V bis 47 s) ist Normalverhalten und darf nicht alarmieren. Kandidaten aus den Daten (Tabelle im Auswertungs-Dokument; **mit Heizgriffen ist „unter 12,5 V für ≥ 10 s“ Alltag und zu empfindlich**): Spannung Hinweis unter 12,5 V ≥ 20 s (1 Auslösung in 9 Fahrten), Warnung unter 12,2 V ≥ 10 s (0), „Entladung über Minuten“ unter 13,0 V ≥ 3 min (0); Kühlmittel Hinweis ≥ 95 °C und Warnung ≥ 100 °C bei laufendem Motor (0; nur Abstand zum Regelpunkt 90 °C, ohne Sommerdaten nicht abgesichert). Voraussetzung: die Spannung wird heute nur abgefragt, wenn sie als Kachel belegt ist (in den ausgewerteten Fahrten war sie es); ein Alarm müsste sie immer abfragen (Zykluszeit, jeder PID etwa 0,15–0,28 s, Protokoll Punkt 25).

**F2 — Lüfter-Ausfall erkennen** (klein, Teil von F1). Das Kühlmittel steigt bei laufendem Motor über den Regelpunkt (hier 90 °C) und die Spannungs-Signatur des Lüfters fehlt: ein ausgefallener oder hängender Lüfter (Sicherung, Relais, Stecker) ist unterwegs sonst erst bei Überhitzung zu bemerken. Folgerung aus den Daten, am Fahrzeug nicht erprobt.

**F3 — Lade-/Entlade-Anzeige** (klein bis mittel). Geglättete Spannung (zum Beispiel über 10 s) mit Zustand „lädt“ (≥ 13,3 V), „lädt kaum“ und „entlädt“, damit man im Stand mit Lüfter und Heizgriffen sieht, wohin es geht. Optional eine **abgeleitete „Lüfter läuft“-Anzeige** aus Kühlmittel und Spannungseinbruch — das ist ein Schluss, kein gemessener Zustand, und müsste so gekennzeichnet sein.

**F4 — Fehler unterwegs erkennen** (klein bis mittel). Die App liest Fehlercodes nur beim Verbinden (Grenze von 1.33.0); ein Code, der mitten in der Fahrt auftritt, bleibt unbemerkt. Abseits der Zivilisation wäre eine Meldung wichtig, etwa durch Auslesen alle 60 s (Modus 03, nicht in jedem Zyklus, wegen der Zykluszeit) und eine Benachrichtigung bei neuem Code.

**F5 — Reichweite** (mittel). Heute zehn Segmente und ein Zurücksetzen auf „voll“. Sinnvoll: Restkilometer als Zahl, eine Reserve-Marke, Teilbetankung mit Litereingabe → Verbrauch (l/100 km) und Reichweite lernen. Ohne Kraftstoff-Werte vom Motorrad bleibt es ein Streckenzähler.

### Sehr nützlich, überschaubarer Aufwand

**F6 — Trip-Computer, Betriebsstunden und Wartungserinnerungen** (klein). Trip A/B, Durchschnitt, Standzeit; Betriebsstunden (Enduros werden oft nach Stunden gewartet: Luftfilter, Öl); Kettenpflege nach Kilometern. Distanzintegrator und Fahrtdauer gibt es schon.

**F7 — Höhe, Steigung und Peilung** (klein bis mittel). Aus Barometer und GPS des Pixel, offline.

**F8 — Strecke aufzeichnen (GPX) und Position zu Fahrten und Fehlern** (mittel). Heute speichert die App keine Positionen, nur die Geschwindigkeit. Positionen sind sensible Daten (lokal halten, nie ins Repository). Navigation selbst ist kein Ziel.

**F9 — Handy-Zustand** (klein). Akku, Laden oder Entladen, Temperatur (der Pixel drosselt in praller Sonne).

**F10 — Profile Straße und Gelände** (mittel). Andere Kachelbelegung und Schwellen per Knopf, ohne Wischgeste.

**F11 — Min/Max je Fahrt** (klein; **nur mit ausdrücklichem Ja**). Kühlmittel max und Spannung min im Detail-Dialog einer Fahrt: zwei Zahlen, kein Diagramm. Zeigt, ob der Lüfter reicht und die Heizgriffe die Spannung drücken; rechenbar aus vorhandenen Messwerten, auch für ältere Fahrten, die beides aufgezeichnet haben. Messwert-Diagramme je Fahrt hat der Nutzer abgelehnt (Protokoll Punkt 46).

**F12 — Batterie-Trend** (klein; neu aus der Auswertung). Spannung bei Zündung an und Einbruch beim Start je Fahrt festhalten, um eine alternde Batterie am Verlauf zu erkennen. Grenzen: Abstand der Zyklen 1,1–1,6 s (Anlasser-Spitzen nur zufällig getroffen; bessere Werte bräuchten kurzes schnelles Abfragen beim Start); die Steuergerätespannung ist nicht die Klemmenspannung; die ausgewerteten Fahrten erlauben **keine** Aussage zur Batteriegesundheit (Gegenprobe: Multimeter).

### Denkbar, aber aufwendig oder unsicher

**F13 — Neigungsanzeige** aus den Lagesensoren (Montage und Kalibrierung nötig). **F14 — Reifendruck** per Bluetooth-Sensor (Sensorprotokolle proprietär; die App kann kein BLE mehr, der Code wurde entfernt). **F15 — Sturzerkennung mit Alarm an einen Kontakt** (Fehlalarme im Gelände, heikle Berechtigungen, Haftung).

### Entfallen oder nicht empfohlen

- **Overlay über der Navigations-App:** entfällt (Navigation läuft getrennt).
- Nicht empfohlen: Schaltempfehlung (im Gelände unzuverlässig), Cloud-Funktionen.

## Offene Fragen an den Nutzer

1. Waren in den Fahrten **41, 48 und 51** (die drei Morgenfahrten) die **Heizgriffe** an, in den übrigen sechs nicht? Dann wäre der Zusammenhang belegt statt nur wahrscheinlich. *(Beantwortet am 2026-10-17: „die Fahrten morgens sind häufig mit Heizgriffen in mittlerer Stufe“ — „häufig“ ist nicht je Fahrt.)*
2. Gibt es **Sommer- oder Geländefahrten** mit Aufzeichnung? Damit ließen sich die Kühlmittel-Schwellen und die Entladung über Minuten absichern.
3. Soll mit **F1 (mit F2 und F3)** begonnen werden? Das ist die Empfehlung; ein Auftrag steht aus.
