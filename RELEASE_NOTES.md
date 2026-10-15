# Release Notes — OBD2 Monitor

## Version 1.32.0 (versionCode 110)

Neu: eine virtuelle Tankanzeige — ein Balken, der die seit dem Tanken gefahrene GPS-Strecke gegen die Reichweite bei vollem Tank zählt.

### Anzeige

- **Neu: Tankanzeige über dem Hauptmenü bzw. der Statuszeile**, in voller Breite und auf jedem Screen, links ein Tankstellen-Symbol. Der Balken füllt sich von links mit dem Anteil der Restreichweite: **ab 40 % grün, ab 20 % gelb, ab 5 % rot, darunter rot blinkend** (500 ms an, 500 ms aus). Die Grenzen sind Untergrenzen („39 %“ heißt unter 40). Die Zeile ist 56 dp hoch (Trefferfläche für Handschuhe); die Kacheln behalten ihre Höhe (170 dp Hochformat, 200 dp Querformat).
- **Zurücksetzen nach dem Tanken:** ein langer Druck auf Symbol oder Balken (der Standard-Long-Press des Systems, ca. 0,4 s) öffnet eine Rückfrage; bestätigt, springt die Anzeige auf die volle Reichweite. Ein einfacher Tipp tut nichts. Für TalkBack beschreibt die Zeile den Stand („Tankanzeige: noch etwa 220 km (69 %)“) und nennt den langen Druck „Tankanzeige zurücksetzen“.
- Die Farben sind die Ampel (`TrafficLightGreen`, `Warning`, `Critical`) mit der üblichen Bedeutung (voll = grün) — eine weitere dokumentierte Ausnahme neben Bluetooth-Punkt, Ölbalken und „N“.

### Einstellung

- **Neu: „Reichweite bei vollem Tank“** in der Gruppe „Fahrzeug“ — ganze Kilometer von **50 bis 1000**; ein leeres Feld löscht den Wert. **Ohne Reichweite gibt es die Tankanzeige nicht**, bestehende Installationen sehen keine Änderung. Eingabe und Anzeige in km, auch bei imperialen Einheiten.

### So wird gezählt

- Die Strecke ist das **Integral der GPS-Geschwindigkeit** über die Zeit (`DistanceIntegrator`). Gezählt wird nur, was belegt ist: eine Lücke zwischen zwei Messungen von mehr als 10 s, eine unbekannte oder unplausible Geschwindigkeit (null, NaN, negativ, über 250 km/h) zählt nichts; unter 2 km/h gilt als Stillstand (GPS-Rauschen). Ein Verbindungsverlust beendet das Intervall.
- Der Zähler gehört der ganzen App (`FuelRepository`, DataStore-Schlüssel `fuel_km_since_fill` und `fuel_range_full_km`, die Namen sind persistierter Zustand). Er wird gedrosselt gespeichert (ab 0,2 km Zuwachs, beim Zurücksetzen sofort, am Sitzungsende); ein Prozess-Kill verliert höchstens 0,2 km. Ein noch nicht gelesener Zähler wird nie überschrieben.

### Grenzen

- **Es ist ein Streckenzähler, keine Kraftstoffmessung.** Die App erkennt das Tanken nicht (Sie setzen per Zurücksetzen auf „voll“, Teilbefüllungen gibt es nicht) und kennt den Verbrauch nicht — Autobahn oder Stadt verschiebt die echte Restreichweite gegen die angezeigte.
- **Gezählt wird nur bei verbundener Sitzung und eingeschalteter „Geschwindigkeit per GPS“** (dieselbe Quelle wie die Ganganzeige). Ohne OBD-Verbindung, bei beendeter App oder abgeschalteter GPS-Einstellung steht der Zähler still.
- Die Genauigkeit der GPS-Strecke ist **nicht gemessen** (am besten bei der ersten Fahrt gegen den Tageskilometerzähler vergleichen). Der Platz im Querformat ist nur gerechnet (≈ 390 von ≈ 510 dp auf dem Pixel 8 Pro), nicht am Gerät gemessen; auf kleineren Telefonen schrumpft die Kachelreihe im Querformat wie bisher auf die verfügbare Höhe.
- Nur auf dem Pixel 8 Pro im Hochformat am Gerät gesehen (Zeile, Tipp ohne Wirkung, langer Druck öffnet die Rückfrage), nicht während einer Fahrt.

### Tests

- 1613 JVM-Tests (+78), 373 Instrumentation-Tests (+35: Tankzeile mit Farben aus dem gerenderten Bild, Blinken bei angehaltener Uhr, Long-Press und Trefferfläche, Zeile in der Navigation samt Rückfrage, Höhe von Hauptmenü und Statuszeile auch mit sichtbarer Tankzeile, Eingabedialog der Reichweite). 17 gezielte Mutationen (Stufengrenzen, Streckenrechnung, Speicherung, Dashboard-Anbindung, Eingabeprüfung) wurden von den Tests erkannt. Lint: 0 Fehler, 3 Warnungen.
- Auf dem Pixel 8 Pro (Android 17) geprüft, nicht auf älteren Android-Versionen und nicht am Fahrzeug.

## Version 1.31.0 (versionCode 109)

Der laufende Timer des Ölbalkens steht jetzt in der Statuszeile während der Fahrt.

### Anzeige

- **Neu: „Öl: 6:12“ in der Mitte der Statuszeile** (englisch „Oil: 6:12“) — die Restzeit, bis der Ölbalken grün wird. Die Zeile erscheint wie bisher nur, wenn „Navigationsleiste beim Fahren ausblenden“ an ist und das Fahrzeug fährt.
- **Wann der Timer läuft:** Er beginnt beim ersten Kühlmittelwert **über 80 °C** (nicht bei 75 oder 76 °C; dort wird der Balken nur gelb) und zählt 10 Minuten herunter. Ein kurzer Einbruch hält ihn nicht an; er wird erst zurückgesetzt, wenn die Temperatur auf höchstens 75 °C fällt, ein Einbruch auf höchstens 78 °C 120 s dauert oder mehr als 60 s kein Messwert kam. „0:00“ steht genau dann da, wenn der Balken grün wird.
- **Solange der Timer nicht läuft, steht die Startzeit da:** „Öl: 10:00“ — vor dem ersten Wert über 80 °C und nach jedem Zurücksetzen.
- **Im Hochformat zeigt die Zeile nur die Uhrzeit statt des vollen Datums.** Datum, Timer und Temperatur passen in dieser Schriftgröße nicht nebeneinander (das volle Datum braucht rund 265–280 dp, ein Pixel 8 Pro hat im Hochformat etwa 380 dp). Der Timer steht exakt in der Mitte. Im Querformat bleibt das volle Datum.
- Der Wert kommt vom Dashboard über einen kleinen gemeinsamen Zustand (`OilWarmupState`) zur Navigation und läuft auch zwischen zwei Messwerten weiter. Die Aufzeichnung ist unverändert (die Balkenfarbe steht seit 1.30.0 als `OIL_BAR` im CSV).

### Grenzen

- Nur mit Tests und Vorschau-Bildern auf dem Pixel 8 Pro geprüft, nicht während einer Fahrt und nicht im Querformat am Gerät (das volle Datum dort ist per Test mit einer breiten Zeile belegt).
- Die Restzeit überlebt keinen Neustart der App bis zum ersten Messzyklus (der Timer selbst schon).
- Die Anzeige liest die Kühlmitteltemperatur, nicht eine gemessene Öltemperatur; das Öl ist wie beim Balken simuliert.

### Tests

- 1535 JVM-Tests (+31), 337 Instrumentation-Tests (+10: Statuszeile in festen Breiten, Timer in der Navigation). Neun gezielte Mutationen (z. B. Restzeit ohne Lückenlimit, aufwärts statt abwärts zählen, nie veröffentlicht, abgerundet statt aufgerundet, falscher Vergleich bei der Breite) werden von den neuen Tests erkannt. Lint: 0 Fehler, 3 Warnungen (neuere Gradle-Version 9.8.1, bewusst nicht genommen; `paging` 3.5.3 ist neu erschienen).
- Auf dem Pixel 8 Pro (Android 17) geprüft, nicht auf älteren Android-Versionen und nicht am Fahrzeug.

## Version 1.30.0 (versionCode 108)

Die Farbe des Ölbalkens steht jetzt auch in der Fahrtaufzeichnung und im CSV-Export — neben dem Gang.

### Aufzeichnung

- **Neu: die angezeigte Farbe des Ölbalkens je Messzyklus.** Eine Zeile mit der PID `OIL_BAR` (Name `Oil bar`, Herkunft `DERIVED`): `0` = rot, `1` = gelb, `2` = grün, `-1` = grau (noch kein Kühlmittelwert eingetroffen). Grau steht bewusst als eigene Zeile, damit „Balken grau“ von „kein Zyklus“ zu unterscheiden ist.
- **Es ist die Anzeige, keine zweite Berechnung:** Grün-Hysterese, Entprellen von 120 s und das Halten der Farbe bei einem fehlenden Kühlmittelwert gelten wie am Balken. So bleibt eine Fahrt auswertbar, auch wenn sich die Schwellen der App später ändern, und auch nach einem Schnell-Reconnect, bei dem der Timer der vorigen Fahrt weiterlief.
- **CSV:** Die Spalten bleiben `timestamp,pid,value,name,source`. Auswertungen, die nach `010C`/`010D`/`0105` filtern, laufen unverändert weiter; die Gang-Zeilen `GEAR` bleiben wie in 1.29.0.
- **Keine Datenbank-Migration** (PID und Herkunft sind Text). `OIL_BAR` steht nicht in der PID-Liste der App: Es wird nie abgefragt und nie als Kachel angeboten.
- Intern: Gang- und Balkenzeile eines Zyklus gehen in einem Aufruf an die Aufzeichnung (ein Schreibvorgang statt zwei).

### Grenzen

- Frühere Fahrten haben keine Farbe. Der Timerstand (wie lange das Kühlmittel schon über 80 °C ist) wird nicht mitgeschrieben; er folgt aus der Kühlmittelreihe.
- Die Zeilen eines Zyklus stehen in der Datei nicht zwingend direkt hinter ihm — nach Zeitstempel sortieren. Ein Wert, der erst nach dem Ende der Sitzung ankommt, wird verworfen. Pro Zyklus kommt eine Zeile hinzu.
- Am Fahrzeug nicht erprobt: nur per Tests und Fakes geprüft. (Dass der Balken in der Praxis grün wird, auch bei kurzzeitig fallender Temperatur, hat der Nutzer nach zwei Testfahrten bestätigt — ohne Aufzeichnung.)

### Tests

- 1504 JVM-Tests (+15), 327 Instrumentation-Tests (+1: die Farbe im CSV-Export gegen eine echte Datenbank). Neun gezielte Mutationen (z. B. Balken immer „grau“, Aufzeichnung bei jedem Ereignis, Rot als Gelb, falsche Herkunft, nur der erste Wert geschrieben) werden von den neuen Tests erkannt. Lint: 0 Fehler, 3 Warnungen (neuere Gradle-Version 9.8.1, bewusst nicht genommen; `paging` 3.5.3 ist neu erschienen).
- Auf dem Pixel 8 Pro (Android 17) geprüft, nicht auf älteren Android-Versionen und nicht am Fahrzeug.

## Version 1.29.0 (versionCode 107)

Der Gang, den die Gang-Box zeigt, steht jetzt auch in der Fahrtaufzeichnung und im CSV-Export.

### Aufzeichnung

- **Neu: der angezeigte Gang je Messzyklus.** Eine Zeile mit der PID `GEAR` (Name `Gear`, Herkunft `DERIVED` = von der App berechnet): `1`–`6` ist der Gang, `0` ist „N“, `-1` heißt „nicht bestimmbar“ („–“ in der Gang-Box). „–“ steht bewusst als eigene Zeile, damit „Gang unbekannt“ von „kein Zyklus“ zu unterscheiden ist.
- **Es ist die Anzeige, keine zweite Berechnung:** Hysterese, Halten des Gangs, „N“ und die GPS-Gegenprobe gelten wie in der Gang-Box. Aufgezeichnet wird je OBD-Zyklus, nicht bei jedem GPS-Update dazwischen; der Zeitstempel ist der des Zyklus.
- **CSV:** Die Spalten bleiben `timestamp,pid,value,name,source`. Neu in `source`: `DERIVED`. Ihre Auswertungsskripte, die nach `010C`/`010D`/`0105` filtern, laufen unverändert weiter.
- **Keine Datenbank-Migration** (PID und Herkunft sind Text). `GEAR` steht nicht in der PID-Liste der App: Es wird nie abgefragt und nie als Kachel angeboten.

### Grenzen

- Frühere Fahrten haben keinen Gang. Radumfang und Übersetzungen der Fahrtzeit werden nicht mitgespeichert; der Gang gilt für die Einstellungen von damals.
- Die Gang-Zeile eines Zyklus steht in der Datei nicht zwingend direkt hinter ihm (das Dashboard schreibt sie aus einer eigenen Coroutine) — nach Zeitstempel sortieren. Ein Gang, der erst nach dem Ende der Sitzung ankommt, wird verworfen.
- „N“ heißt „steht, Motor läuft“, auch bei gezogener Kupplung. Pro Zyklus kommt eine Zeile hinzu.
- Am Fahrzeug nicht erprobt: nur per Tests und Fakes geprüft.

### Tests

- 1489 JVM-Tests (+25), 326 Instrumentation-Tests (+1: der Gang im CSV-Export gegen eine echte Datenbank). Neun gezielte Mutationen (z. B. Aufzeichnung bei jedem Ereignis, „-1“ als „0“, falscher Zeitstempel) werden von den neuen Tests erkannt. Lint: 0 Fehler, 3 Warnungen (neuere Gradle-Version 9.8.1, bewusst nicht genommen; `paging` 3.5.3 ist neu erschienen und kommt mit der nächsten Bibliotheksrunde).
- Auf dem Pixel 8 Pro (Android 17) geprüft, nicht auf älteren Android-Versionen und nicht am Fahrzeug.

## Version 1.28.0 (versionCode 106)

Die Aufzeichnung ist einfacher: Gespeichert wird, was die App abfragt, und die Geschwindigkeit ist immer dabei. Zwei Einstellungen, die verwirrten und bei Fahrzeugen ohne Geschwindigkeit sogar schadeten, sind entfernt.

### Aufzeichnung

- **Gespeichert wird alles, was die App abfragt:** die Werte der Kacheln, immer Drehzahl und Kühlmittel und bei eingeschalteter dynamischer Drehzahl-Schwelle die Öltemperatur. Es gibt keinen weiteren Filter. Mehr Werte in einer Fahrt bekommt man, indem man sie als Kachel belegt.
- **Die Geschwindigkeit ist immer in der Fahrt.** Meldet das Steuergerät die Geschwindigkeit, wird sie jetzt auch ohne Kachel abgefragt. Meldet es sie nicht (bei Motorrädern oft: sie liegt im ABS-Steuergerät, nicht auf dem OBD2-Bus), kommt die GPS-Geschwindigkeit, solange „Geschwindigkeit per GPS“ an ist. Ohne Liste vom Steuergerät wird sie nicht von sich aus abgefragt, dann gilt wie bisher nur die Kachel.
- **Entfernt: „Nur vom Fahrzeug gemeldete Werte speichern“.** Der Schalter verwarf bei Fahrzeugen, die keine Geschwindigkeit melden, die GPS-Geschwindigkeit aus der Fahrt und war sonst fast wirkungslos, weil nicht gemeldete Werte gar nicht erst abgefragt werden.
- **Entfernt: die Liste „Aufzeichnung filtern“ (vorher „Aufgezeichnete PIDs“) samt Bildschirm.** Ein Haken bei einem Wert ohne Kachel bewirkte nie etwas, und alle 19 Zeilen standen angehakt da. Wer früher Werte abgewählt hatte, sieht sie jetzt wieder aufgezeichnet, sofern sie abgefragt werden. Die alten Einstellungswerte bleiben ungelesen im Gerät liegen.
- **Unverändert:** „Nur Fahrten mit DTCs speichern“ und die Auswahl der Kacheln. Sie bot schon nur Werte, die das Steuergerät als unterstützt meldet (die Geschwindigkeit bleibt bei eingeschaltetem GPS wählbar; ohne Verbindung oder ohne Liste stehen alle zur Wahl).

### Tests

- 1464 JVM-Tests (−26: die Tests der entfernten Einstellungen und des Bildschirms fielen weg; neu sind 5, darunter zwei, die ohne die Geschwindigkeits-Regel rot werden), 325 Instrumentation-Tests (−7 mit dem Bildschirm). Lint: 0 Fehler, 1 Warnung (neuere Gradle-Version 9.8.1, bewusst nicht genommen).
- Auf dem Pixel 8 Pro (Android 17) geprüft. **Grenzen:** Ein Geräte-Gesamtlauf hatte einen Fehlschlag (`PreviewsRenderDeviceTest`, „No compose hierarchies found“, ungewöhnlich langer Lauf); einzeln und in einem zweiten Gesamtlauf war er grün, die Ursache ist nicht geklärt. Die Einstellungsseite wurde nicht per Bildschirmfoto angesehen, und das Verhalten an einem echten Fahrzeug ist nicht getestet, nur per Fakes.

## Version 1.27.3 (versionCode 105)

Die Texte der Aufzeichnungs-Einstellungen sagen jetzt, was die Aufzeichnung tatsächlich tut. Am Verhalten ändert sich nichts.

### Einstellungen

- **„Aufgezeichnete PIDs“ heißt jetzt „Aufzeichnung filtern“.** Die Auswahl ist nur ein Filter über das, was die App ohnehin misst: die Werte der Kacheln, immer Drehzahl und Kühlmittel, bei eingeschalteter dynamischer Drehzahl-Schwelle auch die Öltemperatur. Ein Haken bei einem Wert ohne Kachel bewirkte nie etwas, der alte Name und die alte Unterzeile („Welche Messwerte in der Fahrt-Historie gespeichert werden“) ließen das aber so aussehen. Oben im Bildschirm steht jetzt ein Hinweis; wer mehr Werte in einer Fahrt braucht, belegt sie als Kachel.
- **„Nur ECU-bestätigte PIDs aufzeichnen“ heißt jetzt „Nur vom Fahrzeug gemeldete Werte speichern“.** Die Unterzeile nennt, was der Schalter tut: Werte, die das Steuergerät beim Verbinden nicht als unterstützt gemeldet hat, werden verworfen. Der Schalter wirkt nur, wenn diese Abfrage ein Ergebnis hatte.
- **Gut zu wissen:** Der Schalter verwirft auch die GPS-Geschwindigkeit, wenn das Steuergerät die Geschwindigkeit nicht meldet (sie trägt dieselbe PID-Kennung `010D`). Das war schon vorher so und ist nicht geändert worden; die Unterzeile sagt es jetzt. Bei einem Fahrzeug, dessen Geschwindigkeit nur per GPS kommt, sollte der Schalter aus bleiben.

### Tests

- 1490 JVM-Tests, 332 Instrumentation-Tests (+1: der Hinweis oben im Bildschirm). Lint: 0 Fehler, 1 Warnung (neuere Gradle-Version 9.8.1, bewusst nicht genommen).
- Auf dem Pixel 8 Pro (Android 17) geprüft. **Grenzen:** Der neue Hinweis ist per Test belegt (er wird angezeigt), das Aussehen nicht per Bildschirmfoto; ein Test für das Verwerfen der GPS-Geschwindigkeit durch den Schalter fehlt weiterhin.

## Version 1.27.2 (versionCode 104)

Die Öltemperatur-Leiste wird in der Aufwärmphase auch dann grün, wenn die Kühlmitteltemperatur zwischendurch kurz zurückfällt.

### Anzeige

- Vorher setzte jeder Rückfall auf 78 °C oder tiefer den Grün-Timer sofort zurück. In der Aufwärmphase hängt das Kühlmittel aber bei 78–80 °C (Thermostat) und bricht bei Last immer wieder auf 76–78 °C ein; in zwei aufgezeichneten Fahrten (Start bei 6 und 16 °C) erschien deshalb nie Grün.
- Jetzt setzt ein Rückfall den Timer erst zurück, wenn er **120 Sekunden ununterbrochen** anhält. Ein Wert bei 79–80 °C oder darüber beendet den Rückfall, die Zählung beginnt danach neu. Bei 75 °C oder tiefer (Rot) wird weiterhin sofort zurückgesetzt. Ein bereits grüner Balken bleibt bei kurzen Rückfällen grün und fällt erst nach 120 Sekunden unter 78 °C auf Gelb zurück.
- Unverändert: Grün gilt weiter erst nach 10 Minuten über 80 °C; Farben, Schwellen 75 und 80 °C und das Lücken-Limit von 60 Sekunden sind gleich geblieben.
- Die 120 Sekunden stammen aus den zwei Fahrten: die längsten Rückfälle unter 78 °C dauerten 74 und 84 Sekunden. Mit der echten Logik nachgespielt, erscheint Grün damit genau 10 Minuten nach dem ersten Wert über 80 °C; ein sofortiger Reset (auch bei 77 °C) und ein Entprellen von 60 Sekunden hätten in beiden Fahrten nie Grün gezeigt.
- Neuer gespeicherter Zustand `oilBarBelowResetSinceMillis` (Beginn des Rückfalls); ein Zustand aus einer älteren Version ohne ihn wird wie „kein Rückfall“ gelesen.

### Tests

- 1490 JVM-Tests (+12, darunter ein Regressionstest, der beide Fahrten durchspielt), 331 Instrumentation-Tests. Lint: 0 Fehler, 1 Warnung (neuere Gradle-Version 9.8.1, bewusst nicht genommen).
- Auf dem Pixel 8 Pro (Android 17) geprüft, Smoke-Test mit dem ECU-Simulator bestanden. **Grenzen:** nur zwei Fahrten bei kühler Witterung als Grundlage; Rückfälle nach dem Grünwerden sind in den Daten nicht enthalten; eine eigene Aufwärmphase am Motorrad ist nicht geprüft.

## Version 1.27.1 (versionCode 103)

Reine Aufräumarbeit im Build; an der App ändert sich nichts.

### Build

- Die Lizenztexte (`docs/licenses`) werden jetzt mit `assets.directories.add(…)` statt mit dem veralteten `assets.srcDir(…)` ins APK gepackt. Der Build meldet die Veraltet-Warnung nicht mehr. Die vier Lizenztexte stehen mit denselben Größen im APK wie vorher (aus dem APK-Inhalt verglichen); der Geräte-Test der Lizenzseite liest sie aus dem echten APK.
- Gradle 9.7/9.8 und AGP 9.5 bleiben zurückgestellt: Das jüngste stabile Kotlin ist weiter 2.4.21 (2.5.0 gibt es nur als Beta), dessen Tabelle Gradle bis 9.7.0 und AGP bis 9.3.1 nennt; AGP 9.5 gibt es nur als Alpha (`9.5.0-alpha09`). Abfrage am 2026-10-12.

### Tests

- 1475 JVM-Tests, 332 Instrumentation-Tests (1 Annahme-Test läuft nur vor Android 15). Lint: 0 Fehler, 1 Warnung (neuere Gradle-Version 9.8.1, bewusst nicht genommen).
- Auf dem Pixel 8 Pro (Android 17) geprüft. Weiter ungeprüft: Außentemperatur mit Ziel 37 auf einem Gerät mit Internet, der Verbindungsabriss am echten Adapter, Android ≤ 14.

## Version 1.27.0 (versionCode 102)

Die App zielt jetzt auf Android 17 (targetSdk 37). Dabei ist eine Lücke bei der Erkennung eines abgerissenen Bluetooth-Adapters geschlossen worden.

### Verbindung

- Ab Android 17 und targetSdk 37 meldet ein abgerissener Bluetooth-Socket das Ende der Verbindung mit „-1“ statt mit einer Fehlermeldung. Kam davor schon ein Teil der Antwort vom Adapter an, wurde diese halbe Antwort bisher als Ergebnis behandelt, und der Abriss fiel erst beim nächsten Befehl auf. Jetzt gilt: Endet der Datenstrom, bevor die Antwort mit dem Prompt `>` abgeschlossen ist, ist die Verbindung verloren — die Wiederverbindung greift sofort. Zwei neue Tests halten das fest.
- Am echten Adapter ist das nicht geprüft; der Test bildet das „-1“ nach.

### Zielplattform

- targetSdk 36 → **37**. Die Änderungen von Android 17 für Apps mit Ziel 37 sind gegen den Quelltext geprüft: außer dem Bluetooth-Verhalten betrifft keine die App (keine Hintergrund-Activity-Starts, kein LAN-Verkehr, kein Hintergrund-Audio, keine Widgets, kein Klartext-Verkehr, keine feste Bildschirmausrichtung im Manifest). Ein Test hält jetzt README und CLAUDE.md gleich der Einstellung im Build.
- Certificate Transparency ist ab Ziel 37 standardmäßig aktiv. Das Zertifikat des Wetterdienstes trägt eingebettete Nachweise mehrerer Logs. **Ein Abruf der Außentemperatur auf dem Gerät war nicht möglich** (das WLAN des Testgeräts hat keinen Internetzugang) und ist ungeprüft.

### Tests

- 1475 JVM-Tests (+3), 332 Instrumentation-Tests (1 Annahme-Test läuft nur vor Android 15). Lint: 0 Fehler, 1 Warnung (neuere Gradle-Version 9.8.1, bewusst nicht genommen); die Warnung zur Ziel-API ist weg.
- Auf dem Pixel 8 Pro (Android 17) geprüft; ältere Android-Versionen (≤ 14) und das Verhalten am Fahrzeug bleiben ungeprüft wie bisher.

## Version 1.26.0 (versionCode 101)

Das Toolchain-Release: neuer Build-Unterbau und der aktuelle Compose-Stand. An dem, was du in der App bedienst, ändert sich nichts.

### Toolchain

- Gradle 9.5.0 → **9.6.1**, Android Gradle Plugin 9.3.0 → **9.4.1**, compileSdk 36 → **37** (targetSdk bleibt 36: der Wechsel bringt Verhaltensänderungen und ist eine eigene Entscheidung).
- Compose-BOM 2026.05.00 → **2026.09.00** (Compose 1.12.1; Material3 bleibt bei 1.4.0), navigation-compose 2.9.8 → 2.10.2, lifecycle 2.10.0 → 2.11.0, core-ktx 1.18.0 → 1.19.1, hilt-navigation-compose 1.3.0 → 1.4.0.
- Gradle 9.6.1 und nicht neuer: AGP 9.4 verlangt mindestens 9.6.0, Kotlin 2.4.21 ist offiziell nur bis Gradle 9.7.0 freigegeben. AGP 9.4 liegt über der Liste von Kotlin 2.4.21 (bis AGP 9.3.1); der Build läuft ohne Warnung und alle Tests sind grün.
- Die Prüfsumme der Gradle-Distribution stammt aus zwei offiziellen Quellen, die übereinstimmen; die Prüfsummen der Abhängigkeiten (`gradle/verification-metadata.xml`, 998 Komponenten) sind mit `--refresh-dependencies` neu geschrieben.

### Tests

- 1472 JVM-Tests, 332 Instrumentation-Tests (1 Annahme-Test läuft nur vor Android 15 und wird auf dem Pixel nicht ausgeführt). Lint: 0 Fehler, 2 Warnungen (Ziel-API 36 statt 37, neuere Gradle-Version 9.8.1 bewusst nicht genommen).
- Alle 63 Vorschauen rendern auf dem Gerät; die geprüften Bilder (Kacheln, Drehzahlbalken, Einstellungszeilen, Auswahlsegmente) sehen unverändert aus, eines ist Byte für Byte gleich dem des Vorgängers.
- Neu mit AGP 9.4: das Testprotokoll führt den Annahme-Test als „failure“ statt „skipped“ (Gradle wertet ihn weiter als bestanden).
- Auf dem Pixel 8 Pro (Android 17) geprüft; ältere Android-Versionen (≤ 14) und das Verhalten am Fahrzeug bleiben ungeprüft wie bisher.

### Bewusst nicht Teil dieses Releases

- targetSdk 37, Gradle 9.7/9.8 und AGP 9.5: warten auf eine Kotlin-Version, die sie freigibt bzw. auf eine eigene Entscheidung.

## Version 1.25.3 (versionCode 100)

Sieben weitere Bibliotheken und Kotlin sind aktualisiert; an der App selbst ändert sich nichts, was du bedienst.

### Bibliotheken

- Aktualisiert: Kotlin 2.4.10 → 2.4.21, kotlinx-coroutines 1.9.0 → 1.11.0, kotlinx-serialization 1.8.1 → 1.11.0, Sentry 8.29.0 → 8.60.0 (ohne eingebauten DSN weiter nicht aktiv), Testbibliotheken MockK 1.13.12 → 1.14.11 und androidx.test runner 1.7.0 / ext-junit 1.3.0 / rules 1.7.0. Die Prüfsummen (`gradle/verification-metadata.xml`, 855 Komponenten) sind mit `--refresh-dependencies` neu geschrieben.
- Der Kotlin-Pin im Root-Build stand zweimal (Version-Catalog und `buildscript`-Block); ein neuer Test hält beide gleich. Beim Anheben fiel auf, dass der Pin nachhinkte, weil Gradle still die höhere Version nimmt.
- Eine Aussage der Backlog-Liste war falsch: Kotlin ließ sich sehr wohl losgelöst vom Toolchain-Release anheben (AGP 9.3, KSP 2.3.11 und Hilt 2.60.1 verlangten nichts weiter).
- Weiter zurückgestellt, weil sie compileSdk 37 brauchen: Compose-BOM 2026.09, navigation-compose 2.10.x, lifecycle 2.11.0, core-ktx 1.19.x, hilt-navigation-compose 1.4.0, dazu AGP 9.4 und Gradle 9.6 (Toolchain-Release nach der Testfahrt).

### Tests

- 1472 JVM-Tests (+1: Kotlin-Pin), 332 Instrumentation-Tests (1 übersprungen wie bisher), Lint 0 Fehler / 10 Warnungen (nur die Toolchain-Punkte).
- Auf dem Pixel 8 Pro (Android 17) geprüft; die älteren Android-Versionen (≤ 14) bleiben ungeprüft wie bisher, ebenso das Verhalten am Fahrzeug.

## Version 1.25.2 (versionCode 99)

Die Aufzeichnungsauswahl vergisst keinen später ergänzten PID mehr, jede Vorschau wird auf dem Gerät gerendert, die Testhilfen stehen nur noch einmal da, und acht Bibliotheken sind aktualisiert.

### Aufzeichnung

- Die Auswahl der aufgezeichneten PIDs speichert jetzt, was du **abgewählt** hast, nicht, was du behalten willst. Eine gespeicherte Positivliste ließ jeden PID aus, den es beim Speichern noch nicht gab — wer einmal einen Haken entfernt hatte, zeichnete später ergänzte PIDs nie auf, ohne es zu merken.
- Deine bisherige Auswahl bleibt unverändert: der alte Eintrag wird weiter gelesen (was darin fehlt, gilt als abgewählt) und beim ersten Ändern abgelöst. Es gibt heute keinen neuen PID; die Änderung wirkt erst, wenn einer dazukommt.

### Vorschauen

- Jede der 63 `@Preview`-Funktionen wird auf dem Gerät komponiert und muss etwas zeichnen (`PreviewsRenderDeviceTest`); eine Prüfung im JVM-Test hält fest, dass jede Vorschau die gefundene Form hat und die Zahl stimmt. Beide Prüfungen sind per Mutation geprüft (eine werfende und eine leere Vorschau werden erkannt).
- Das Rendern zeigte zwei falsche Vorschauen: die Kachel füllte das ganze Fenster und verdrängte die zweite Kachel und das Vorschau-Band, und die Einstellungszeilen hatten keinen Seitenrand. Beide sind behoben. Sieben Hilfs-Composables, die nur so hießen wie Vorschauen, heißen jetzt `…PreviewContent`.

### Tests

- TST-17: `fakeAppPreferences` ersetzt sieben `makePrefs` und vier Inline-Mocks (ein Test vergleicht jeden Flow mit der echten Klasse), `fakeObdRepository` ersetzt 60 Stub-Blöcke (`DashboardViewModelTest` 3078 → 2666 Zeilen), `idleObdRepository` ersetzt den gleichen Inhalt in 35 Instrumentation-Klassen (`@BindValue` bleibt je Klasse, Hilt lässt kein Überschreiben zu).
- 1471 JVM-Tests (+15), 332 Instrumentation-Tests (+1, 1 übersprungen wie bisher).

### Bibliotheken

- Aktualisiert: navigation-compose 2.8.5 → 2.9.8, lifecycle 2.8.7 → 2.10.0, datastore 1.1.1 → 1.2.1, appcompat 1.7.0 → 1.8.0, paging 3.3.4 → 3.5.2, core-ktx 1.15.0 → 1.18.0, activity-compose 1.9.3 → 1.13.0, hilt-navigation-compose 1.2.0 → 1.3.0 (`hiltViewModel` wechselte das Paket). Die Prüfsummen (`gradle/verification-metadata.xml`, 789 Komponenten) sind mit `--refresh-dependencies` neu geschrieben.
- **Nicht möglich unter compileSdk 36:** navigation-compose 2.10.x, lifecycle 2.11.0, core-ktx 1.19.x, hilt-navigation-compose 1.4.0 — sie warten mit dem Compose-BOM auf das Toolchain-Release (compileSdk 37, AGP 9.4, Gradle 9.6). Nicht Teil der Freigabe: androidx.test, Sentry, coroutines, mockk, kotlinx-serialization, Kotlin.
- Auf dem Pixel 8 Pro (Android 17) geprüft; die älteren Android-Versionen (≤ 14) bleiben ungeprüft wie bisher.

### Nicht Teil dieses Releases

- Am Fahrzeug: A8, A9 Teil 2, Lese-Hänger auf Classic, Dienst-Typ im Betrieb, Verwerfen von Altbytes, PRO-11 im echten Wechsel zwischen zwei Adaptern.
- Compose-BOM 2026.09 und die vier oben genannten Bibliotheken (Toolchain-Release nach der Testfahrt).

## Version 1.25.1 (versionCode 98)

Fehlercode-Texte in beiden Sprachen, eine Verbindung, die ein alter Leser nicht mehr einreißt, und die letzten großen Dateien aufgeteilt.

### Fehlercode-Texte in deiner Sprache

- Die Beschreibung der 37 bekannten Fehlercodes und der Platzhalter „Unbekannter Fehlercode“ kommen jetzt aus den String-Ressourcen: **deutsch** in `values-de/`, englisch in `values/`. Vorher standen sie fest englisch im Code.
- Die deutschen Texte sind eine **eigene Übersetzung** der SAE-J2012-Namen, kein amtlicher Wortlaut; der Code steht immer daneben.
- `DtcEntry` und `DtcHistoryEntry` tragen nur noch den Code, die Beschreibung folgt daraus (`dtcDescriptionRes`). Das App-Log nennt die Codes (ausstehende markiert), nicht mehr den Text.

### Ein alter Leser reißt keine neue Verbindung mehr ein (PRO-11)

- „Verbinden“ zu einem anderen Adapter schließt die alte Verbindung. Das blockierte Lesen auf ihr scheitert, will trennen und wartet hinter dem Lock; bis es ihn bekam, stand die neue Verbindung — und das bedingungslose `disconnect()` riss sie wieder ein (Verlust-Meldung, Wiederverbindung).
- Die Lesepfade (Live-Abfrage bei Link-Verlust, fünfmal BusInit und Watchdog; Fehlercodes und Freeze-Frame bei Zeitlimit und Link-Verlust) trennen jetzt nur noch, wenn sie noch auf der **aktuellen** Verbindung lasen (`disconnectIfCurrent`). Das Trennen auf Wunsch des Nutzers bleibt bedingungslos, ebenso „ein Zeitlimit bei Fehlercodes/Freeze-Frame trennt“.
- Beim Ersetzen einer Verbindung wird deren ganzer Zustand zurückgesetzt (Verbindungsstatus, Fahrterkennung, unterstützte PIDs, letzte Werte) — der alte Leser räumt nicht mehr hinterher auf.
- **Am Fahrzeug nicht geprüft**; die Lage ist mit Test-Verbindungen nachgestellt (drei Tests rot vor dem Fix, beide Schutzmechanismen per Mutation geprüft).

### Aufräumen

- **Keine Datei mehr über 400 Zeilen:** `ObdRepository` (450 → 391, die einmaligen Lesepfade in `DiagnosticReads`), `ReadingsPipeline` (445 → 378, `SessionRecorder` und die reine Funktion `selectPids`), `DashboardViewModel` (483 → 367, `SparklineBuffer`, `OilBarTracker`, `GearTracker`). Die Begründungen wanderten als KDoc mit; die Schlüssel des gespeicherten Öl-Balken-Zustands sind unverändert.
- In `selectPids` konnte der Rückfall auf die Standard-PIDs seit der Pflicht-Abfrage von Drehzahl und Kühlmittel nie greifen; er ist entfernt.

### Tests

- Der ECU-Simulator bekommt eine injizierbare Uhr: Die Kurven (Drehzahlwelle, Kühlmittelrampe, Last/Drossel/Ansaugdruck/Luftmasse an den Rändern) sind an festen Zeitpunkten geprüft statt per `Thread.sleep`. Der Geräte-Test der Geschwindigkeits-Kachel verlangt eine Zahl aus 0–120 km/h, die sich bewegt (vorher nur das Label).
- Überlebende Mutanten festgenagelt: BusInit-Schwelle genau 5, Init-Reihenfolge `ATZ … ATSP0` als ganzer String, SPP-UUID, Kanten der Statusschwellen und des Öl-Balkens (15 Mutationen, alle erkannt). Ein Geräte-Test ohne Prüfung prüft jetzt das Warte-Band; vier veraltete Testkommentare sind korrigiert.
- 1456 JVM-Tests (+69), 331 Instrumentation-Tests (±0; ein Geräte-Test ersetzt, einer neu gefasst; 1 übersprungen wie bisher). Neu: `DiagnosticReads` (über `ObdRepositoryTest`), `SessionRecorderTest`, `PidSelectionTest`, `SparklineBufferTest`, `OilBarTrackerTest`, `GearTrackerTest`; `DtcDescriptionTest` prüft Liste, Ressourcen und beide Sprachen. Der Kompilierlauf der Tests bleibt ohne Warnung.

### Entschieden, nicht umgesetzt (2026-10-12)

- „Imperial“ bleibt bei km/h → mph und °C → °F; die Ganganzeige bleibt bei einem Satz Getriebedaten; die Toleranzen 4 % / 6 % und die Eingabeart der Übersetzungen bleiben unverändert (Toleranzen: Auswertung der Testfahrt abwarten).

### Nicht Teil dieses Releases

- Am Fahrzeug: A8, A9 Teil 2, Lese-Hänger auf Classic, Dienst-Typ im Betrieb, Verwerfen von Altbytes, PRO-11 im echten Wechsel zwischen zwei Adaptern.
- Duplikate in Testhilfen (TST-17).

## Version 1.25.0 (versionCode 97)

Der Rest des QS-Backlogs, der kein Fahrzeug braucht: zwei Funktionen (Herkunft der Geschwindigkeit in der Aufzeichnung, Dienst-Typ bei laufender Verbindung), verspätete Adapterantworten, Drehung und Lebenszyklus der Oberfläche sowie Pflege (Vorschauen, große Dateien, Testklassen, JDK).

### Herkunft der Geschwindigkeit in Aufzeichnung und CSV

- Die GPS-Geschwindigkeit trägt dieselbe PID-ID wie die des Fahrzeugs (`010D`), damit sie in den Geschwindigkeits-Slot fällt. In einer Aufzeichnung war deshalb nicht mehr zu erkennen, welche Quelle ein Wert hatte — für die Auswertung der Testfahrt (Gang-Toleranzen, ECU gegen GPS) entscheidend.
- Neu: `SensorValue.source` (`ECU` oder `GPS`), gespeichert in `sensor_readings.source` (**Datenbank v4**, `MIGRATION_3_4`, nullable und ohne Vorgabewert). **Bereits aufgezeichnete Fahrten bleiben „unbekannt“** (leeres Feld) — ihre Herkunft kennt niemand, `ECU` zu raten wäre eine Behauptung.
- Der CSV-Export hat eine **fünfte Spalte `source`**; die ersten vier Spalten sind unverändert.
- **Rückweg:** eine Datenbank v4 kann ein APK ≤ 1.24.x nicht öffnen (kein Downgrade-Pfad, wie schon seit v1.23.0) — nur mit gelöschten App-Daten.

### Dienst-Typ bei laufender Verbindung

- Der Typ des Vordergrunddienstes (`connectedDevice`, mit GPS-Geschwindigkeit und „Immer“ zusätzlich `location`) wurde nur beim Start gewählt. Schaltete man GPS-Geschwindigkeit ein oder erteilte „Immer“, während die Verbindung stand, behielt der Dienst den alten Typ bis zur nächsten Verbindung.
- Jetzt gleicht `refreshServiceType()` den Typ eines **laufenden** Dienstes ab — bei einer Änderung der Einstellung und beim Zurückkehren in die App. Nur ab Android 14 (davor gilt der Typ aus dem Manifest), nie für einen nicht laufenden Dienst, und eine abgelehnte Aufwertung beendet den laufenden Dienst nicht.
- **Am Fahrzeug nicht geprüft.** Abgesichert sind die Entscheidung (JVM) und das Merken des Typs im Dienst (Gerät).

### Verspätete Adapterantworten und Zeitlimits (PRO-13)

- Der ELM327 kennt keine Antwort-Kennung: Die verspätete Antwort eines Befehls, der ins Zeitlimit lief, wurde für die Antwort auf den **nächsten** gehalten, und jede weitere war um eins verschoben. `ElmController` verwirft jetzt vor jedem Befehl, was ungelesen im Puffer und im Eingang liegt. Bytes, die erst nach dem Absenden eintreffen, erwischt das nicht (Grenze des Protokolls).
- **Freeze-Frame** bricht beim ersten Zeitlimit ab, trennt und meldet den Fehler (wie die Fehlercodes). Vorher lief die Schleife mit allen rund 40 PIDs weiter — bei einem stillen Adapter 40 × 5 s — und meldete am Ende „keine Daten“, obwohl der Adapter nie geantwortet hatte.
- Die **FIN trennt bewusst nicht**: Die Sitzung fragt sie bei jedem Verbinden, und ein Motorrad, das auf Modus 09 schweigt, geriete sonst in eine Schleife aus Verbinden, Zeitlimit und Trennen.
- Die Richtlinie je Lesepfad steht einmal als KDoc an `ObdRepository`. **Am Fahrzeug nicht geprüft**; ein absichtlich eingebauter Fehler (ohne das Verwerfen) lässt zwei Tests scheitern.

### Drehung und Lebenszyklus

- **Eingaben und Dialoge überleben eine Drehung:** die Slot-Auswahl, der Beenden-Dialog und die Getriebe-Eingabefelder (auch halb getippte oder ungültige Eingaben) waren `remember` und verschwanden mit der Activity. Jetzt `rememberSaveable`. Die Berechtigungsprüfung bleibt bewusst `remember`: sie ist eine Momentaufnahme des Systems und wird bei jedem Zurückkehren neu gelesen.
- **Alle 62 `collectAsState` sind `collectAsStateWithLifecycle`:** die Oberfläche sammelt nur noch zwischen Start und Stopp der Activity. Das ist zulässig, weil kein ViewModel für seine Arbeit auf einen Sammler der Oberfläche angewiesen ist — Polling und Aufzeichnung laufen im `viewModelScope` bzw. im Anwendungs-Scope. Ein Test hält die Regel fest.
- Die **Drehung selbst** wurde nicht am Gerät ausgeführt (der Nutzer dreht physisch); nachgestellt ist die Wiederherstellung des Zustands.

### Kleinere Korrekturen

- **Drehzahl-Schwelle nach Motortemperatur (UID-22):** die Bänder hatten Lücken (49,95 / 69,95 / 84,95 °C fielen auf den vollen Faktor 1,00). Jetzt lückenlos; NaN gilt als „kein Wert“ (0,50). Die Temperaturen kommen als ganze Grad vom Steuergerät, im Betrieb war das nicht erreichbar.
- **`RpmBar`:** die Blink-Schleife läuft nur noch, solange geblinkt wird (vorher weckte ein `delay(50)` die Coroutine dauerhaft mit 20 Hz).
- **Datum und Uhrzeit** in Verlauf, Fehlerhistorie und App-Logs folgen der Sprache des Geräts (`ui/DateTimeFormats.kt`); vorher drei feste Muster `dd.MM.yyyy …`, eines beim Laden der Klasse gebaut (eine Sprachänderung erreichte es nie). Die Verlaufszeile zeigt jetzt „12.10.2026, 14:30“ (mit Komma).
- Ein Bluetooth-Gerät ohne Namen heißt in der Liste „Unbenanntes Gerät“ bzw. „Unnamed device“ statt fest „Unknown“.
- Der reguläre Ausdruck der Parser wird einmal gebaut statt bei jeder Antwort; die KDoc von `DtcDescriptions` nennt keine driftende Zahl mehr; `ObdResult.kt` (toter Code) ist entfernt.

### Pflege

- **`@Preview`** für die zustandslosen Bausteine (Sparkline, Fehlercode-Zeilen, Fahrtzeile, Log-Zeile, Geräte-Zeile, Berechtigungsseite, Kachel/Band, Farbschema-Auswahl). Ein Test verlangt für jede Datei mit Composables eine Vorschau oder eine Ausnahme mit Begründung (ViewModel-gebundene Screens, reine Effekte).
- **Große Dateien:** `DashboardComponents` (692 Zeilen), `SettingsScreen` (558), `SettingsComponents` (423) sind aufgeteilt (reine Verschiebungen im selben Paket, Zeile für Zeile geprüft); `SessionEvent`, `DashboardUiState` und die PID-Erkennung haben eigene Dateien. **Drei Dateien bleiben über 400 Zeilen** (`DashboardViewModel` 483, `ObdRepository` 450, `ReadingsPipeline` 445) — je eine Klasse; weiteres Teilen hieße Logik umbauen.
- **Testklassen:** sieben Klassen hießen auf dem JVM und auf dem Gerät gleich; die Geräte-Klassen enden jetzt auf `DeviceTest`, ein Test hält die Namen auseinander.
- **Pläne und Spezifikationen** umgesetzter Funktionen (11 + 3) liegen in `docs/superpowers/archive/` (nichts gelöscht).
- **JDK:** Gradle lädt kein JDK mehr von selbst (`org.gradle.java.installations.auto-download=false`) — für Toolchain-Downloads gibt es keine Prüfsumme. Fehlt das JDK 21 (JetBrains), bricht der Build mit Gradles Meldung ab; das vorhandene unter `~/.gradle/jdks` wird gefunden. Dass ein fehlendes JDK wirklich abbricht, wurde nicht nachgestellt (der Gegenversuch hätte einen Download ausgelöst).

### Tests

- 1387 JVM-Tests (+38), 331 Instrumentation-Tests (+10, 1 übersprungen wie bisher).
- Neu u. a.: `DateTimeFormatsTest`, `CodeHygieneSourceTest`; erweitert `ObdServiceControllerTest`, `ElmControllerTest` (Adapter-Fake, der erst auf einen Befehl antwortet), `ObdRepositoryTest`, `SessionRepositoryTest`, `SpeedSourceTest`, `ReadingsPipelineTest`, `DashboardComponentsTest`; Gerät: Migration 3→4, Dienst-Typ, Wiederherstellung nach Drehung.
- Der Kompilierlauf der Tests bleibt ohne Warnung (eine neue, ein Testname mit Anführungszeichen, wurde behoben); Lint (Debug, offline) 0 Fehler / 9 Warnungen, unverändert. Fünf absichtlich eingebaute Fehler (Temperaturband, Herkunft GPS, Schreiben der Herkunft, Dienst-Typ-Entscheidung, Datumsformat) wurden von jeweils mindestens einem Test erkannt.

### Nicht Teil dieses Releases

- Entscheidungen des Nutzers stehen aus: englische DTC-Beschreibungen (Übersetzung), weitere Einheiten bei „imperial“, Ganganzeige (Profile, Kalibrierung, Zähnezahl).
- Am Fahrzeug: A8, A9 Teil 2, Lese-Hänger auf Classic, der Dienst-Typ im laufenden Betrieb, das Verwerfen von Altbytes.

## Version 1.24.14 (versionCode 96)

Zwei kleine Fehler aus dem QS-Backlog (Reiter-Tipp auf einer Unterseite, veralteter Fehlertext in den Einstellungen) und Aufräumen der Tests.

### Erneuter Tipp auf den hervorgehobenen Reiter führt zur Startseite

- Auf einer Unterseite (App-Info, Getriebe, App-Logs, Freeze-Frame …) ist ihr Reiter hervorgehoben — ein erneuter Tipp darauf **tat nichts**: `navigate(…, restoreState = true)` stellte genau den Stapel wieder her, den der Tipp gerade gesichert hatte. Am Pixel mit der App-Info nachgestellt.
- Jetzt entscheidet die reine Funktion `bottomNavAction(currentRoute, tappedTab)`: auf der Startseite des Reiters passiert nichts, auf einer seiner Unterseiten geht es zur Startseite des Reiters, bei einem anderen Reiter wird wie bisher gewechselt (Zustand sichern/wiederherstellen). Liegt die Startseite nicht auf dem Stapel, wird wie ein Wechsel navigiert.
- Abgesichert durch 5 JVM-Tests der Entscheidung und 3 Geräte-Tests in `BottomNavTest`; auf dem Gerät mit dem alten Verhalten nachgewiesen (beide Re-Tap-Tests schlugen fehl).

### Fehlertext in den Einstellungen bleibt nicht mehr stehen

- `SettingsViewModel.error` hat zwei Ursachen: ein Fehler beim Laden der Geräteliste (fehlende Bluetooth-Berechtigung) und ein fehlgeschlagener Verbindungsversuch. Beide blieben stehen, auch wenn die Ursache längst weg war.
- Der Listenfehler endet mit dem nächsten **erfolgreichen** Laden der Liste. Der Verbindungsfehler endet, sobald eine Verbindung steht — **auf jedem Weg**, auch bei der Wiederverbindung des Dashboards, die `connect()` dieser Seite gar nicht durchläuft. Keiner räumt den anderen ab; ein neuer Verbindungsversuch ersetzt beides.
- 4 neue JVM-Tests (alle Mutationen der Fehlertext-Logik erkannt).

### Tests

- Die Sleep-sichere Uhr der Außentemperatur-Haltefrist (`SystemClock.elapsedRealtimeNanos()`) war ungetestet. Neu: `timeSourceFrom(readNanos)` (4 JVM-Tests: nur die gelieferte Uhr wird gelesen, ein Sprung von Stunden kommt an) und `ElapsedRealtimeSourceDeviceTest` (2 Geräte-Tests gegen die echte Uhr). Ein echter Tiefschlaf lässt sich im Test nicht herbeiführen.
- **Test-Compilerwarnungen 10 → 0:** zehn Kopien der `projectRoot`-Suche wurden durch eine gemeinsame, getestete `projectRoot` ersetzt (neun davon trugen eine Warnung, weil `System.getProperty` nullable ist), dazu ein unbenutzter Ausdruck in `CrashReportingSettingsViewModelTest`.
- 1349 JVM-Tests (+15), 321 Instrumentation-Tests (+5, 1 übersprungen wie bisher).

### Nicht Teil dieses Releases

Aus der Fehlerliste bewusst nicht angefasst: die 62 `collectAsState`-Aufrufe, fehlende `@Preview`s, `ObdResult.kt` (toter Code), englische DTC-Texte — Pflege bzw. Produktentscheidungen, keine Fehler.

## Version 1.24.13 (versionCode 95)

Der Tacho-Sweep läuft nur noch einmal je App-Start. Dazu die Entscheidungen vom 2026-10-12 (Doku) und eine verschärfte Freigabeliste der Entwicklungssitzung (nicht Teil der App).

### Tacho-Sweep: einmal je App-Start

- Die Demo-Animation des Drehzahlbalkens (0 → 10 000 → 0 rpm, rund 6,7 s) lief bei **jedem** Betreten des Dashboards ohne Drehzahl — nach jedem Tabwechsel, bei jeder Rückkehr aus den Einstellungen und bei jeder Drehung des Geräts. Jetzt läuft sie **einmal je App-Start**.
- `DashboardViewModel.consumeDemoSweepOffer()` liefert `true` nur beim ersten Aufruf. `DashboardScreen` verknüpft es mit `shouldStartRpmDemoSweep` (Drehzahl nach 400 ms noch 0?). Beide Fragen sind getrennt und einzeln getestet.
- „App-Start“ ist die Lebensdauer des `DashboardViewModel`: Tabwechsel und Drehung spielen nichts neu ab; Wegwischen und Neustart spielen den Sweep ab. Ein Systemkill mit Wiederherstellung zählt als neuer Start (das Feld liegt bewusst nicht im `SavedStateHandle`).
- Das Angebot ist mit der **ersten Prüfung** verbraucht, nicht erst mit dem Abspielen: lag beim Start schon eine echte Drehzahl an, wird der Sweep nicht später mitten in der Sitzung nachgeholt.
- **Folge:** Ohne gesetzte Blinkgrenze zeigt der Sweep die Schaltblitz-Vorschau über 7 000 rpm — sie erscheint nur noch beim Start.
- **Nicht am Gerät geprüft:** die Drehung des Geräts (wird vom Nutzer physisch gedreht).

### Entscheidungen und Doku

- Neues Entscheidungsprotokoll `docs/qa/2026-10-12-decisions.md`: Navigationsübergänge bleiben beim Standard von `NavHost` (CLAUDE.md nennt ihn jetzt als Vorgabe, die 220-ms-Forderung entfällt, UID-10), die Drehzahlskala bleibt bei 10 000 (UID-21), Polling und Aufzeichnung bleiben im ViewModel, Compose-BOM 2026.09 erst nach der Testfahrt, die Seriennummer des Testgeräts wird erst vor einer Veröffentlichung des Repositorys aus der Historie getilgt, die Keystore-Zweitkopie legt der Nutzer selbst an.
- Die Freigabeliste der Claude-Sitzung (`.claude/settings.local.json`, git-ignoriert, nicht Teil der App) wurde verschärft: 116 → 106 Einträge, `git push` nur noch auf `origin main` und Tags, Deny-Regeln für den Keystore-Ordner und `gradle.properties`. Beschreibung und Grenzen in der CLAUDE.md.

### Tests

1334 JVM-Unit-Tests (+3: einmaliges Angebot, neues ViewModel bekommt es wieder, Verbindungswechsel setzen es nicht zurück; zwei Mutationen erkannt).

## Version 1.24.12 (versionCode 94)

Kleine Anzeige-Fixes aus dem QS-Bericht (UID-15, UID-16, UID-19, UID-20, DAT-13) und das Verbindungsversuch-Band aus dem Backlog; dazu zwei Kleinigkeiten (Standort-Hardware optional, Smoke-Test im Repo).

### Vorschau-Band nennt den Verbindungsversuch

- Während einer Wiederverbindung steht im Band jetzt **„Vorschau · Verbindungsversuch 2/5“** statt nur „Vorschau · nicht verbunden“ — bis zu 93 s lang (3 · 6 · 12 · 24 · 48 s Wartezeit) war nicht zu sehen, dass die App noch versucht. Beim Auto-Connect beim Start (und im kurzen Moment zwischen Verlust und erstem Versuch) steht **„Vorschau · verbinde …“**. Die Entscheidung trifft die reine Funktion `previewBannerLabel`; `DashboardUiState.reconnectAttempt` wurde schon vorher gesetzt, aber von keiner Oberfläche gelesen.
- Der Koordinator meldet einen Versuch, bevor seine Wartezeit beginnt: „1/5“ steht also auch während der ersten Wartezeit (wie die Zeile „Reconnect attempt 1/5“ im App-Log).
- **Nicht gebaut:** „nächster Versuch in X s“ — bräuchte ein neues Ereignis mit der Wartezeit.

### Slot-Picker nach dem angezeigten Namen sortiert (UID-15)

Die Liste war nach dem **englischen** PID-Namen sortiert, angezeigt wird der übersetzte — auf einem deutschen Gerät wirkte sie ungeordnet. Jetzt sortiert `sortByDisplayLabel` nach dem Namen, den man liest (`Collator` der Gerätesprache: „Ö“ bei „O“, Groß-/Kleinschreibung egal, stabil).

### Sparkline gehört zu einer Verbindung (UID-16)

Nach einem Verbindungsverlust hängte der erste neue Punkt an die Kurve von vorher, als gäbe es keine Lücke. Puffer **und** Zeitmarken werden jetzt bei Trennung, Verlust und Sitzungsfehler geleert (die Zeitmarke, damit der 800-ms-Mindestabstand den ersten Punkt der neuen Verbindung nicht verschluckt).

### „N“ mit GPS-Gegenprobe (UID-19)

Meldet die ECU konstant 0 km/h (ein hängendes Geschwindigkeits-PID), stand „N“ die ganze Fahrt. Jetzt hebt das GPS „N“ auf, wenn der Widerspruch **ECU steht (< 4 km/h) / GPS fährt (≥ 8 km/h)** **5 Sekunden ununterbrochen** besteht (`standstillConflictSince`, `gpsVetoesNeutral`); der Gang kommt dann wieder aus dem GPS. **Bewusst nicht sofort:** Beim Anhalten meldet die ECU sofort 0, das GPS hinkt ein bis zwei Sekunden nach — das ist der Normalfall und darf „N“ nicht verzögern (ein erster Entwurf mit sofortigem Veto brach den Test `neutral wins over a gear that GPS still reports` und wurde verworfen). Die Frist läuft auf der monotonen Uhr des ViewModels und beginnt mit jeder Verbindung neu.

### „Beenden“ läuft immer zu Ende (UID-20)

`ObdServiceController.endSessionSafely()` fängt jeden Fehler beim Trennen (App-Log WARN), reicht nur die Abbruch-Ausnahme weiter und stoppt den Dienst auch dann. Vorher beendete eine Ausnahme aus dem Trennen die Coroutine in der Komposition (Prozess-Absturz), und `finishAffinity()` lief nie.

### Weitere

- **`useGpsSpeed` mit `distinctUntilChanged` (DAT-13):** Jeder DataStore-Schreibvorgang (u. a. `persistDevice` bei jedem manuellen Verbinden) startete den Standort-Listener neu, obwohl sich der Schalter nicht geändert hatte.
- **Standort-Hardware optional:** `uses-feature android.hardware.location` und `….gps` mit `required="false"` — die Standort-Berechtigungen deklarierten sie sonst implizit als Pflicht (aufgefallen beim `aapt2`-Dump von 1.24.11).
- **Smoke-Test im Repo:** `scripts/smoke.py <versionName> <versionCode>` (uiautomator, Gerät entsperrt: Start, Reiter, Beenden-Dialog, App-Info mit Version und Build, Logcat). Er lag bis 1.24.11 nur im Scratchpad einer Sitzung und ging verloren; `SmokeScriptTest` hält ihn vorhanden, übersetzbar und im Release-Ablauf genannt. Er prüft weder Messwerte noch die Fahrzeugverbindung.

### Bewusst nicht umgesetzt

- **UID-21 (RPM-Skala fest 10 000):** Die Blinkgrenze lässt sich nur bis 10 000 einstellen; eine größere Skala wäre eine neue Einstellung (Produktentscheidung).
- **Tacho-Sweep nur einmal je Start:** Produktentscheidung.
- **PRO-11 (`disconnect()` unbedingt):** Der Einzelbefund ist im Bericht nur ein Stichwort, es ist die Protokollschicht — ohne Adapter nicht prüfbar.

### Tests

- **1331 JVM-Unit-Tests** (vorher 1297, +34) und **316 Instrumentation-Tests auf dem Pixel 8 Pro** (vorher 312, +4; einer auf diesem Gerät übersprungen), 0 Fehler; Lint 0 Fehler / 26 Warnungen (unverändert)
- **Neue Tests:** `PreviewBannerLabelTest`, `SlotPickerOrderTest`, `SmokeScriptTest` (JVM); je Punkt Tests in `GearIndicatorTest`, `DashboardViewModelTest`, `ObdServiceControllerTest`, `ExitAppViewModelTest`, `AppPreferencesTest`, `BluetoothScopeSourceTest`; Gerät: `PreviewBannerTest` (+2), `SlotPickerSheetTest` (+1), `DashboardScreenTest` (+1).
- **Mutationsprüfung:** neun gezielte Eingriffe (Versuchsnummer ab 2 statt 1, Picker unsortiert, Puffer nicht geleert, Zeitmarke nicht geleert, Grenze 5 s auf `>`, Widerspruchsbeginn nicht gehalten, `endSessionSafely` ohne Fangnetz, `distinctUntilChanged` entfernt, Standort `required="true"`) — **alle erkannt**.
- **Am Gerät nicht prüfbar:** das Band mit echten Versuchsnummern (in der Testanordnung bleibt `isConnectedFlow` absichtlich wahr, die Schleife meldet keinen Versuch — dort ist „verbinde …“ geprüft, die Nummer über Compose- und JVM-Tests), „N“ mit konstant 0 km/h (braucht ein Fahrzeug).

## Version 1.24.11 (versionCode 93)

Drei kleine Härtungen aus dem QS-Bericht: Wetter-Client (SEC-9), `.idea` im Git (SEC-11) und `uses-feature` für Bluetooth (DAT-17). Keine sichtbare Änderung in der App.

### Wetter-Client gegenüber dem Dritten begrenzt (SEC-9)

- **`User-Agent`:** Die Anfrage an Open-Meteo nennt jetzt die App (`OBD2Monitor/<Version>`, sonst nichts — keine Geräte- oder Nutzerkennung, kein Standort) und `Accept: application/json`. Vorher stand dort der JVM-Standard „Dalvik/…“, der bei Auffälligkeiten keinen Ansprechpartner nennt.
- **Antwort begrenzt:** Gelesen werden höchstens 16 KiB (Bytes nach dem Entpacken; die echte Antwort hat rund 400 Byte). Ein größeres `Content-Length` wird gar nicht erst geöffnet, eine größere Antwort ergibt `null` wie jeder andere Fehler (dann gilt das Halten des letzten Werts für 30 Minuten). Ein fehlerhafter oder bösartiger Server kostet die App damit weder Speicher noch Daten. `readBounded` liest nie mehr als Grenze + 1 Byte.

### Nichts Persönliches im Git (SEC-11)

- 13 `.idea`-Dateien waren getrackt, darunter `deploymentTargetSelector.xml` mit der Seriennummer eines Testgeräts. Sie sind aus dem Index genommen (auf der Platte bleiben sie, Android Studio läuft unverändert weiter), die `.gitignore` ignoriert `.idea/` jetzt ganz — die alten Einzel-Einträge wirkten nicht, weil Ignorieren nur Dateien betrifft, die nicht im Index stehen.
- **`RepositoryHygieneTest`** hält das fest: Eintrag in der `.gitignore`, keine getrackte IDE-Datei, keine `serial=…` in getrackten Dateien.
- **Grenze:** Die Seriennummer steht weiter in der Git-Historie (Commits ab Februar 2026). Sie zu entfernen hieße `git filter-repo` und alle SHAs und Tags neu zu schreiben — nicht getan; das Repository ist privat. Die Codestil-Einstellungen des Projekts (`.idea/codeStyles`) kommen damit nicht mehr aus dem Repository, ein frisches Klonen fängt mit den Studio-Standards an.

### `uses-feature` Bluetooth (DAT-17)

- Das Manifest deklariert `android.hardware.bluetooth` als `required="true"`. Ohne Bluetooth liefert `getSystemService(BLUETOOTH_SERVICE)` null, und `AppModule.provideBluetoothAdapter` wirft beim Aufbau des Hilt-Graphen — die App ist dort nutzlos. Geräte ohne Bluetooth kommen damit nicht mehr über Stores und Installer.
- **Grenze:** Ein von Hand (adb) installiertes Exemplar auf einem Gerät ohne Bluetooth stürzt weiter beim Start ab; ein nullbarer Adapter durch drei Verbraucher wäre mehr Code als Nutzen.

### Tests

- **1297 JVM-Unit-Tests** (vorher 1284, +13) und **312 Instrumentation-Tests auf dem Pixel 8 Pro** (unverändert, einer auf diesem Gerät übersprungen), 0 Fehler; Lint 0 Fehler / 26 Warnungen (unverändert).
- **Neue Tests:** neun in `OpenMeteoApiTest` (User-Agent und Accept, Standard-Agent, `weatherUserAgent`, Grenze genau/ein Byte darüber, endlose Antwort, `Content-Length`, Mehrbyte-Zeichen, `readBounded`), eine in `BluetoothScopeSourceTest` (Feature `required="true"`), drei in `RepositoryHygieneTest`.
- **Mutationsprüfung:** fünf gezielte Eingriffe (User-Agent entfernt, `Content-Length`-Prüfung entfernt, Grenze um eins verschoben, `required="false"`, `.idea/` aus der `.gitignore` bzw. eine IDE-Datei wieder getrackt) — **alle erkannt**.

## Version 1.24.10 (versionCode 92)

Statusleisten-Symbole und System-Splash passen zum Farbschema der App (zwei Punkte aus dem Backlog: „Statusleisten-Symbole folgen dem Systemthema“ und „Hintergrundfarbe des System-Splash“).

### Statusleiste: Symbole passend zum App-Hintergrund

Ab Android 15 zeichnet die App hinter die durchsichtige Statusleiste, die Farbe der Symbole (Uhr, Akku, Benachrichtigungen) kam aber vom `DayNight`-Theme, also aus dem **Systemmodus**: helles System + Cockpit-Schema (fast schwarz) ergab dunkle Symbole auf Schwarz, dunkles System + helles Standard-Schema weiße auf Hell.
- **`AppTheme`** setzt die Symbolfarbe jetzt aus dem Hintergrund des Farbschemas (`useDarkStatusBarIcons`: Leuchtdichte > 0,5 → dunkle Symbole), über `isAppearanceLightStatusBars`. Die drei Cockpit-Schemata haben damit in jedem Systemmodus helle Symbole.
- **Am Pixel nachgestellt und behoben:** „Standard + Hell“ bei dunklem System zeigt jetzt dunkle Symbole auf hellem Grund (Screenshot geprüft).
- **Nur ab Android 15** (`controlsStatusBarIcons`, API 35): davor hat die Statusleiste eine eigene undurchsichtige Farbe aus dem Theme, und erzwungene Symbole könnten dort dunkel auf Schwarz werden. Die Navigationsleiste ist unberührt.

### System-Splash in der Farbe der App

Der Splash von Android 12+ (App-Icon auf einer Fläche) entsteht vor jedem App-Code und liest nur Ressourcen; seine Fläche kam aus dem `DayNight`-Theme — im dunklen System das neutrale Grau `#303030` (gemessen), danach der Wechsel auf das Cockpit-Schwarz.
- **Statisch:** `Theme.OBD2Monitor` malt sein Fenster in `splash_background_system` — hell `#FEF7FF`, mit `-night` dunkel `#141218`, exakt die Hintergrundfarben des Standard-Schemas. Das gilt auch für den ersten Start und als das, was Android < 12 vor dem ersten Frame zeigt.
- **Je Farbschema:** fünf Splash-Themes (`Light`, `Dark`, `Blue`, `Amber`, `Green`); `MainActivity` setzt das passende per `SplashScreen.setSplashScreenTheme` bei jedem Start und jeder Änderung — **wirksam ab dem nächsten Start** (das System persistiert es). „Wie das System“ setzt es zurück.
- **Am Pixel gemessen** (Start mit `am start -D` angehalten, damit der Splash stehen bleibt; abgedunkelt vom Systemdialog, daher Verhältnisse): Standard dunkel → die Splash-Fläche trägt den Ton von `#141218` (vorher neutral grau), Orange → `#060401` (≙ `#110A02`), „Hell“ → hell. Gemeinsam mit dem App-Hintergrund ergibt sich beim Start kein Farbsprung mehr.
- **`SplashThemeTest`** hält die Splash-Farben und die Hintergrundfarben der Kotlin-Schemata zusammen (XML und Schema können nicht auseinanderlaufen), die Zuordnung Schema → Theme und die Theme-Namen. **Die Namen persistiert das System — nie umbenennen.**

### Tests

- **1284 JVM-Unit-Tests** (vorher 1269, +15) und **312 Instrumentation-Tests auf dem Pixel 8 Pro** (vorher 307, +5, davon einer auf diesem Gerät übersprungen, s. Grenzen), 0 Fehler, 1 übersprungen; Lint 0 Fehler / 26 Warnungen (unverändert).
- **Mutationsprüfung:** 11 gezielte Eingriffe (9 mit JVM-Tests, 2 mit Gerätetests: Statusleisten-Effekt entfernt, Symbolfarbe invertiert; JVM u. a. Schwelle, Hintergrund statt Primärfarbe, API-Grenzen, Zuordnung, Resource-Farben, `-night`-Override, `windowBackground`) — **alle erkannt**.
- **Neue Tests:** `SystemBarsTest`, `SplashThemeTest` (JVM); `StatusBarIconsTest` (Gerät: helles Schema → dunkle Symbole und umgekehrt, Laufzeitwechsel, Cockpit-Schema in jedem Systemmodus).

### Bekannte Grenzen

- **Android ≤ 14 nicht am Gerät geprüft** (das Testgerät läuft auf API 37, kein Emulator installiert): die Statusleisten-Logik ist dort bewusst abgeschaltet; der statische Splash-/Fensterhintergrund und das Theme gelten dort ohne Prüfung am Gerät.
- **Ein Gerätetest ist übersprungen** (`beforeAndroid15TheAppLeavesTheIconsToTheSystem`): er läuft nur unter API < 35.
- **Wirksam ab dem nächsten Start:** Nach dem Wechsel des Farbschemas zeigt der nächste Start den passenden Splash; der allererste Start nach der Installation zeigt den Hell/Dunkel-Standard des Systems.
- **Liegt die App noch in der Übersicht der zuletzt benutzten Apps,** zeigt Android beim Start deren letztes Bild statt des Splash — dann spielt die Splash-Farbe keine Rolle.
- **Der Splash steht nur wenige Millisekunden** (Release: 153–170 ms bis zur Anzeige, gemessen); die Änderung betrifft den Farbsprung, nicht die Dauer. Einen Splash-Screen mit eigener Dauer gibt es bewusst nicht.

## Version 1.24.9 (versionCode 91)

Aufräumen, Start, Standort, Lieferkette, Lizenzen, Farben (QS-Bericht `docs/qa/2026-10-04-qa-report.md`: sieben C-Punkte — PRO-8/SCR-05/LIF-06, UID-12/LIF-21, SEC-5, SEC-7, DAT-12/14/16/18, SEC-10/SCR-24, Farbrollen).

### Toter BLE-Code entfernt (PRO-8/SCR-05/LIF-06)

- **Die Geräteliste hat nie BLE-Adapter angeboten:** `BluetoothDeviceScanner.scanBle()` hatte keinen Aufrufer, `bondedDevices` liefert nur Classic-Geräte. Entfernt wurden `BleConnection` (286 Zeilen) samt Test, der BLE-Zweig der `ConnectionFactory`, `BtType.BLE`, `scanBle()` und `hasScan()` — zusammen rund 370 Zeilen. `BtType` = `CLASSIC | SIMULATOR`. Die README warb trotzdem mit BLE-Adaptern; korrigiert.
- **Weniger Berechtigungen, weniger Aussperrung:** `BLUETOOTH_SCAN` und `BLUETOOTH_ADMIN` entfallen, und auf Android 8–11 (API ≤ 30) verlangte das Berechtigungs-Tor **Standort** — nur für den BLE-Scan, den es nie gab. Wer dort Standort ablehnte, kam nicht in die App. Jetzt: ab API 31 nur `BLUETOOTH_CONNECT`, bis API 30 nur `BLUETOOTH`. Gelesen am Code: `ClassicBtConnection` ruft kein `cancelDiscovery()`, braucht also kein SCAN.
- **`BluetoothScopeSourceTest`** hält BLE-Code, die entfallenen Berechtigungen im Manifest und das README-Versprechen draußen.

### Start ohne Blitz (UID-12/LIF-21)

Theme und Kachel-Belegung kamen asynchron aus dem DataStore; die Einstellung wurde erst während der ersten Komposition abonniert, das erste Bild zeigte kurz das Standard-Theme bzw. leere Kacheln.
- **`seedBlocking`** (`data/StartupSeed.kt`) liest den ersten Wert einmal synchron — als **Startwert**, nicht als Quelle der Wahrheit (Obergrenze 250 ms, bei Fehler/Hänger gilt der Standard). Am Pixel gemessen: ~3 ms für den ersten Zugriff beim Kaltstart. Genutzt in `MainActivity` (Theme) und `DashboardViewModel` (Slot-Belegung).
- **Am Gerät geprüft:** Farbschema Orange gewählt, App beendet, kalt gestartet — ein temporäres Log in der ersten Komposition zeigte bereits `theme=amber` (mit dem Standard-Theme als Startwert hätte dort `system` gestanden); der erste Frame danach war ohne Zwischenschritt im Cockpit-Orange. Das Prüf-Log ist wieder entfernt.

### Standort mit Altersprüfung (SEC-5)

`AndroidCurrentLocationProvider` nahm jeden gecachten Fix — und der GPS-Cache schlug einen frischeren Netzwerk-Fix; der Cache friert ein, solange keine App einen Standort anfordert. Die Außentemperatur konnte so für die falsche Gegend gelten. Jetzt: der **jüngste** Cache-Fix, wenn er höchstens 10 Minuten alt ist (`freshestFix`, monotone Uhr `elapsedRealtimeNanos`; negatives Alter = ungültig), sonst ein aktiver Fix (GPS, dann Netzwerk, je 15 s), sonst **kein Standort** — lieber keine Außentemperatur als eine falsche. Der zuletzt gute Wert bleibt wie bisher 30 Minuten stehen.

### Gradle-Prüfsummen (SEC-7)

- `distributionSha256Sum` für Gradle 9.5.0 (von services.gradle.org und der Prüfsummen-Seite von gradle.org bestätigt und selbst gehasht).
- `gradle/verification-metadata.xml` mit SHA-256 für **711 Komponenten** (Abhängigkeiten und Plugins): Gradle bricht ab, wenn ein Artefakt nicht passt oder fehlt. Geprüft mit einem Build mit `--refresh-dependencies` (alle Downloads frisch gegen die Hashes). Der erste Schreiblauf ohne `--refresh-dependencies` hatte einen Eintrag vergessen (`guava-parent`) — der Weg zum Neuschreiben steht deshalb mit diesem Schalter in der CLAUDE.md.
- **`BuildIntegrityTest`** hält Summe, Verifikation und die Abdeckung jeder direkten Abhängigkeit fest.

### Fehlerhistorie: Randfälle (DAT-12/14/16/18, soweit belegbar)

- **„Aktiv“ gilt je Verbindung:** `DtcViewModel` setzt die Live-Liste zurück, sobald die Verbindung fällt. Vorher galt nach Trennen und Wiederverbinden ohne offene Seite die alte Liste wieder als „Aktiv“.
- **Überlappende Auslesevorgänge:** Generationszähler (wie im Freeze-Frame-ViewModel) — ein spätes altes Ergebnis überschreibt weder Liste noch Zustand noch Fehler; es wird trotzdem aufgezeichnet (es war eine echte Beobachtung).
- Die Einzelbefunde DAT-12/14/16/18 stehen im Bericht nur als Stichwort; was sich am Code belegen ließ, ist gemacht, „ungenutzte DAO-Methoden“ gibt es nicht mehr.

### Lizenzseite in der App (SEC-10/SCR-24)

App-Info → „Open-Source-Lizenzen“: jede ausgelieferte Komponente mit Namen, Zweck und Lizenz, dazu der Volltext. Der Katalog ist von Hand gepflegt, **abgeleitet aus dem, was im Release-APK landet** (R8-Mapping gelesen: AndroidX, Kotlin/Coroutines, Okio, Dagger/Hilt, Sentry; dazu zwei Schriften). Die Texte (Apache 2.0 von apache.org, MIT von Sentry mit **beiden** Copyright-Zeilen des Originals, 2× OFL) liegen in `docs/licenses/` und sind zugleich die Assets der App — eine Quelle für Repo und APK. `LicenseCatalogTest` zwingt jede neue direkte Abhängigkeit zu einem Eintrag.

### Cockpit-Farbrollen (letzte Lücke)

Die drei Cockpit-Schemata definierten `primaryContainer`, `tertiaryContainer`, `errorContainer`, ihre `on…`-Rollen und `outlineVariant` nicht — M3-Violett schimmerte durch (Log-Chips, Löschen-Hintergrund, Kachel-Trennlinien). Jetzt aus der eigenen Palette abgeleitet (Surface zu 28 % Richtung `primary`/`tertiary`/Critical, `outlineVariant` zu 50 % Richtung `outline`); `ColorSchemesTest` prüft Regel, Kontrast (Text ≥ 7:1) und Farbton. **Der WARN-Chip der App-Logs ist gelb** (`StatusColors.container(Warning, surface)`), nicht `tertiaryContainer`: im blauen und grünen Schema liegt es 1–2° im Farbton neben `primaryContainer`, INFO und WARN wären nicht zu unterscheiden gewesen.

### Tests

- **1269 JVM-Unit-Tests** (vorher 1248, +21) und **307 Instrumentation-Tests auf dem Pixel 8 Pro** (vorher 301, +6), alle grün; Lint 0 Fehler / 26 Warnungen (unverändert).
- **Mutationsprüfung:** 29 gezielte Eingriffe (27 mit JVM-Tests: Standort-Alter, Verbindungswechsel, Generationszähler, Startwerte, Farbrollen, Berechtigungen, Manifest, Lizenzkatalog, Eltern-Tab, Prüfsumme; 2 mit Gerätetests: Lizenztexte aus den Assets laden, Navigation zur Lizenzseite) — **alle erkannt**. Einer lief zuerst ins Leere (ein Timeout-Eingriff hing den Test statt ihn rot zu färben); der Test hat jetzt selbst ein Zeitlimit.
- **Neue Tests u. a.:** `StartupSeedTest`, `LocationFreshnessTest`, `BuildIntegrityTest`, `BluetoothScopeSourceTest`, `LicenseCatalogTest`, `LogLevelColorsTest` (JVM); `LicensesScreenTest` (Gerät, liest die Texte aus dem echten APK); erweitert u. a. `ColorSchemesTest`, `StatusColorsTest`, `DtcViewModelTest`, `DashboardViewModelTest`, `AppPreferencesTest`, `AndroidCurrentLocationProviderTest`, `ParentTabTest`, `UiRulesSourceTest`, `PermissionGateTest`, `ConnectionFactoryTest`, `AppInfoScreenTest`. Entfallen: `BleConnectionTest` (mit dem Code).

### Bekannte Grenzen

- **Android 8–11 (API ≤ 30) nicht am Gerät geprüft:** das Testgerät läuft auf API 37. Die Berechtigungs-Entscheidung ist per JVM-Test abgesichert, der Ablauf dort nicht.
- **Ein früher gespeichertes BLE-Gerät** wird als unbekannt gelesen: kein Auto-Connect, das Gerät einmal neu wählen. Da die Liste nie BLE-Adapter anbot, dürfte das niemanden treffen.
- **Das Fenster vor dem ersten Frame** (System-Splash auf Android 12+, Hintergrund aus dem Systemmodus) bleibt unverändert; dort ist ein heller oder dunkler Moment weiter möglich. Die Startwerte betreffen nur, was die App selbst zeichnet.
- **Das JDK holt der foojay-Resolver** — Gradle kennt für Toolchain-Downloads keine Prüfsumme. Abhängigkeiten und Distribution sind geprüft, das JDK nicht.
- **Der Lizenzkatalog ist von Hand gepflegt:** der Test erzwingt Einträge für direkte Abhängigkeiten; was eine direkte Abhängigkeit transitiv mitbringt, steht nur, wenn jemand es einträgt (Stand: aus dem R8-Mapping dieses Release abgeleitet).
- **Neue oder aktualisierte Abhängigkeiten** brechen den Build, bis `verification-metadata.xml` neu geschrieben ist (Befehl in der CLAUDE.md).

## Version 1.24.8 (versionCode 90)

Touch-Ziele, Barrierefreiheit, CSV-Export und Sentry (QS-Bericht `docs/qa/2026-10-04-qa-report.md`: B33 und die kleineren C-Punkte UID-11/13/14/17, SCR-17/18, DAT-06/07/09, SEC-10).

### Touch-Ziele mindestens 56 dp — für alle Material-Komponenten (UID-11/SCR-17)

Gemessen lagen Button, TextButton und IconButton bei **48 dp Touch-Fläche** (sichtbar 40 dp): Exit-Dialog, Bluetooth-Knopf und Verbinden/Trennen. Die Handschuh-Regel (Design-Prinzip 4) verlangt 56 dp.
- **`AppTheme`** (neu, `MainActivity` nutzt es statt eines nackten `MaterialTheme`) setzt zwei Werte: die **Trefferfläche** (`ViewConfiguration.minimumTouchTargetSize`) und den **Platz im Layout** (`LocalMinimumInteractiveComponentSize`) auf 56 dp. Nur den zweiten anzuheben, ändert die Trefferfläche nicht (gemessen) — daher beide. Die Regel gilt damit auch für künftige Komponenten.
- **Dialoge und Sheets erben sie nicht** (eigenes Fenster, eigene `ViewConfiguration`; der Exit-Dialog maß dort weiter 48 dp). `ProvideMinTouchTarget { … }` setzt sie in den Slots erneut; `UiRulesSourceTest` erzwingt das für jede Datei, die `AlertDialog`/`ModalBottomSheet`/Menü/`Popup` benutzt.
- Optisch ändert sich fast nichts (Screenshots am Pixel, Hoch- und Querformat geprüft): die Flächen wachsen unsichtbar, Buttons behalten ihre Gestalt.

### Barrierefreiheit (UID-13/SCR-18)

- **Long-Press auf eine Kachel hat eine Bezeichnung** („Messwert ändern“ / „Change sensor“) — gefüllte Kachel und leerer Slot. Es ist die einzige Geste, die einen Slot belegt; TalkBack sagte nur „Doppeltippen und halten“.
- **Aufzeichnungs-Auswahl:** Die Zeile ist ein Kontrollkästchen (`toggleable`, `Role.Checkbox`) mit Zustand. Vorher meldete TalkBack ihn nicht.
- **Slider der Drehzahl-Blinkgrenze:** trägt Beschreibung und Zustandstext („6000 rpm“ bzw. „Deaktiviert“). Vorher: „Schieberegler, 60 Prozent“.
- **Drehzahlbalken:** war ein nacktes Canvas, für TalkBack unsichtbar. Jetzt „Drehzahl 2800 rpm“ — auf 100 gerundet, damit die 20-Hz-Demobewegung nicht zum Dauersprechen führt; ohne Verbindung „Drehzahl nicht verfügbar“.

### CSV-Export (DAT-06/07/09, PID-Spalte)

- **Jeder Export eine eigene Datei** (`session_<Fahrt>_<UTC-Zeit auf die Millisekunde>.csv`, atomar angelegt). Vorher löschte ein zweiter Export derselben Fahrt die Datei unter einer noch laufenden Weitergabe.
- **Aufräumen:** Exporte älter als 24 h löscht jeder Export vorab und die Aufbewahrung einmal je App-Start. Vorher wurden sie nie gelöscht. Ein fehlgeschlagener Export hinterlässt keine halbe Datei.
- **Konstanter Speicher:** `ORDER BY id` statt `ORDER BY timestamp` — der Index auf `sessionId` liefert die Einfügereihenfolge ohne Sortierlauf (am Gerät per `EXPLAIN QUERY PLAN` belegt: kein „TEMP B-TREE“). Vorher musste SQLite alle Zeilen einer Fahrt lesen und sortieren, bevor die erste geschrieben wurde, entgegen der Doku. Die Reihenfolge ist die der Aufzeichnung.
- **Neue Spalte `name`** (stabiler englischer PID-Name) **hinten**: `timestamp,pid,value,name`. Excel macht aus `0105` beim Doppelklick-Öffnen die Zahl 105; Anführungszeichen verhindern das nicht — die Spalte identifiziert den Wert trotzdem. Die ersten drei Spalten sind unverändert, Auswertungen nach Position laufen weiter. Felder gehen durch RFC-4180-Quoting.

### B33 — Sentry zur Laufzeit abschaltbar, APK halb so groß

- **Ohne DSN im Build ist Sentry nicht aktiv und es gibt keinen Schalter** — so sind Ihre Releases gebaut. **Mit DSN** startet Sentry nicht mehr von selbst (`io.sentry.auto-init=false`), sondern erst, nachdem die Einstellung gelesen ist; **„Absturzberichte senden“** (Info & Wartung, Standard an) startet und stoppt es sofort. Die App-Info zeigt den Stand (an / aus / in dieser Version nicht enthalten).
- **Warum kein Auto-Init:** Mit ihm könnte ein Absturz in den ersten Millisekunden gegen die Einstellung des Nutzers gemeldet werden. **Der Preis, ehrlich:** Ein Absturz in den ersten Millisekunden nach dem Prozessstart, bevor die Einstellung gelesen ist, wird nicht gemeldet.
- **`sentry-android-core` statt `sentry-android`:** das Sammelpaket zog den Native-Handler (`libsentry.so`, `libsentry-android.so`, in drei Architekturen) und Replay nach; die App hat keinen eigenen Native-Code. **Das Release-APK wird dadurch von 5,73 auf 2,92 MB kleiner** (R8 läuft, kein Fehler).
- README korrigiert: „optional, deaktivierbar“ war ungenau.
- **Nebenbei (SEC-10):** Der Lizenztext der Schrift **Space Grotesk** fehlte (nur der von IBM Plex Mono lag in `docs/licenses/`); Urheberrechtsvermerk aus den Schriftdateien selbst gelesen. Sentry ist jetzt in der App-Info offengelegt.

### Kleinigkeiten

- **„-0“ (UID-14):** `"%.0f".format(-0.4f)` zeigte „-0“. Eine Funktion (`formatSensorNumber`) für Kachel, Slot-Auswahl und Aufzeichnungs-Liste; ein negativer Wert, der auf 0 rundet, zeigt kein Vorzeichen.
- **Doppeltes Gelb (UID-17):** `Color(0xFFEAB308)` im Verbindungs-Punkt ist jetzt `StatusColors.Warning`.

### Tests

- **1248 JVM-Unit-Tests** (vorher 1197, +51) und **301 Instrumentation-Tests auf dem Pixel 8 Pro** (vorher 282, +19), alle grün; Lint 0 Fehler / 26 Warnungen (unverändert).
- **Mutationsprüfung:** 33 gezielte Eingriffe (23 mit JVM-Tests, 10 mit Gerätetests: Touch-Fläche, Dialog-Regel, Long-Press-Bezeichnungen, Kontrollkästchen-Rolle, Slider-Zustand, Balken-Beschreibung, Schalter, Statuszeile) — **alle von mindestens einem Test erkannt**.
- **Die Touch-Tests messen die Touch-Fläche** (`touchBoundsInRoot`), nicht die sichtbare: `assertHeightIsAtLeast` meldete für einen IconButton 40 dp, obwohl sein Touch-Ziel 48 dp war — der Test war zunächst rot mit den echten Ausgangswerten (48 dp), dann grün mit `AppTheme`; der erste Versuch, nur den Material-Wert anzuheben, blieb rot (das ist die Messung hinter der Zwei-Werte-Regel).
- **Neue Tests u. a.:** `CsvExportTest`, `SensorNumberFormatTest`, `CrashReportingControllerTest`, `CrashReportingSettingsViewModelTest`, `CrashReportingConfigTest`, `LicenseFilesTest`, `UiRulesSourceTest` (JVM); `TouchTargetTest`, `SlotA11yTest` (Gerät); erweitert u. a. `SessionRepositoryTest`, `SessionRepositoryDeviceTest` (Abfrageplan, Aufzeichnungsreihenfolge), `AppPreferencesTest`, `DataRetentionTest`, `SettingsScreenTest`, `AppInfoScreenTest`, `RpmBarTest`, `RpmThresholdSectionTest`, `LoggingConfigScreenTest`, `LiveValueTileTest`.
- **Optik am Pixel geprüft** (Querformat; Dashboard, Einstellungen, Exit-Dialog): keine sichtbare Änderung, die Flächen wachsen unsichtbar.

### Bekannte Grenzen

- Das APK trägt die Lizenztexte der Schriften nicht als eigene Seite (Hinweis und Lizenz stehen in der Namenstabelle der Schriftdateien, der lesbare Text in `docs/licenses/`); eine Lizenzseite in der App ist nicht gebaut.
- Excel zeigt `0105` in der Spalte `pid` weiter als Zahl (die Spalte `name` identifiziert den Wert trotzdem).
- Mit gesetztem DSN und eingeschaltetem Schalter ist die Sentry-Übertragung selbst auf dem Gerät nicht geprüft (Ihre Builds haben keinen DSN); geprüft sind Controller-Logik, Schalter, Statuszeile und dass die App ohne DSN nichts startet.
- Die Touch-Flächen sind gemessen (Test), nicht mit Handschuh erprobt.

## Version 1.24.7 (versionCode 89)

Sitzung und Vordergrunddienst, Release-Signatur (QS-Bericht `docs/qa/2026-10-04-qa-report.md`, Befunde B1, B4, B31).

### Die Sitzung endet, wenn nichts mehr aufzeichnet (B1)

Polling und Aufzeichnung laufen im ViewModel des Dashboards; der Vordergrunddienst hält nur den Prozess am Leben und besitzt keine Arbeit. Wurde die App aus der
Übersicht gewischt, endete die Aufzeichnung — **Verbindung und die Benachrichtigung „Fahrzeugdaten werden aufgezeichnet“ blieben aber stehen**, obwohl nichts mehr aufzeichnete.
Jetzt gilt: Die Sitzung (Verbindung + Dienst + Benachrichtigung) lebt genau so lange, wie etwas die Messwerte verbraucht.

- **Wegwischen** (`ObdForegroundService.onTaskRemoved`) und **jedes echte Beenden der Activity** (`MainActivity.onDestroy`, wenn sie nicht nur für eine Drehung/einen Themenwechsel neu erzeugt wird:
  „Aktivitäten nicht behalten“, Zurück am Root auf API ≤ 30) trennen den Adapter und beenden den Dienst — wie „Beenden“. Beides ist idempotent.
- **Zurück am Root ab API 31 beendet nichts:** Die App geht in den Hintergrund, der Dienst trägt den Prozess, die Aufzeichnung läuft weiter (so war es gedacht).
- **Antippen der Benachrichtigung öffnet jetzt die App** (vorher geschah nichts); eine laufende Activity kommt nach vorn, es entsteht keine zweite mit leerem ViewModel.
- **Eine Stelle für den Dienst:** `ObdServiceController` ersetzt zwei Kopien des Starts (Koordinator, Einstellungen) und drei des Stopps/Trennens (Beenden, Einstellungen, Dienst-Aktion „Trennen“).
- **Produktentscheidung, die ich getroffen habe:** Wegwischen heißt „fertig“. Die Alternative — Polling und Aufzeichnung in den Dienst zu verlegen, damit sie das Wegwischen überleben — ist ein großer Umbau, den niemand verlangt hat; sie ist nicht gebaut.

### Ein abgelehnter Dienst-Start ist kein Verbindungsfehler (B4)

`startForegroundService()` kann ab API 31 im Hintergrund eine Ausnahme werfen (`ForegroundServiceStartNotAllowedException`); ein fehlendes Recht für den Dienst-Typ wirft `SecurityException`.
Der Aufruf lag im selben `try` wie der Verbindungsaufbau: Eine **stehende** Verbindung wurde als „Reconnect-Versuch fehlgeschlagen“ geloggt und `Connected` blieb aus; im Dienst selbst beendete
eine `SecurityException` aus `startForeground()` den ganzen Prozess. Jetzt steht die Verbindung, das App-Log sagt „Foreground service could not be started: …“, die App läuft weiter (nur ohne
Schutz davor, im Hintergrund beendet zu werden). Gefangen werden genau diese beiden Ausnahmetypen — jeder andere Fehler läuft durch.

### Geheimnisse und Release-Signatur (B31)

- **Keystore und `~/.gradle/gradle.properties` waren für alle Benutzer lesbar** (Modus 644). Jetzt Modus 600; der Keystore liegt außerhalb des Projektbaums (`~/.android-keys/<name>.jks`, Ordner 700) —
  Prüfsumme vor und nach dem Verschieben identisch, Signaturzertifikat unverändert (derselbe Fingerabdruck wie in jedem bisherigen Release).
- **`KEYSTORE_FILE`** (Property oder Umgebung, `~/` erlaubt) legt den Ort fest; ohne Angabe gilt wie bisher `keystore.jks` im Projekt. **Zeigt der Pfad ins Leere, bricht der Release-Build ab**
  (vorher: still ein unsigniertes APK). Debug-Builds sind unberührt.
- **`scripts/release.sh` verweigert** den Release, wenn Keystore oder Gradle-Properties für andere zugänglich sind, wenn `apksigner` fehlt, oder wenn das APK nicht mit dem Zertifikat aus
  `scripts/release-cert.sha256` signiert ist (öffentlicher Fingerabdruck) — ein falscher Schlüssel ergäbe ein Update, das sich nicht über die installierte App einspielen lässt.
- **Bewusst nicht angefasst:** die Freigabeliste der Claude-Sitzung (`.claude/settings.local.json`: u. a. `cat`, `curl`, `git push`, `keytool`, `apksigner sign` ohne Rückfrage). Berechtigungen sind Ihre Entscheidung;
  ein Vorschlag steht in der Zusammenfassung des Releases. Die Datei ist git-ignoriert und nicht Teil des Releases.

### Tests

- **1197 JVM-Unit-Tests** (vorher 1167, +30) und **282 Instrumentation-Tests auf dem Pixel 8 Pro** (vorher 280, +2: Benachrichtigung hat ein `contentIntent`, `openAppIntent` zeigt auf `MainActivity` mit den richtigen Flags), alle grün; Lint 0 Fehler / 26 Warnungen (unverändert).
- **Mutationsprüfung:** 19 gezielte Eingriffe in den neuen Code (Fangnetz, Sitzung beenden, Rechte- und Zertifikatsprüfung, Keystore-Konfiguration) — **alle von mindestens einem Test erkannt**. Ein Eingriff ließ sich zunächst nicht kompilieren; zugleich zeigte er, dass „im Hintergrund“ nicht geprüft wurde (der Test hätte auch einen blockierenden Aufruf akzeptiert) — der Test prüft jetzt, dass der Aufruf zurückkehrt, ohne selbst zu trennen.
- **Echte Ausführung der Release-Prüfungen:** `ReleaseScriptLibTest` führt `scripts/release-lib.sh` mit `bash` und temporären Dateien aus (Modus 644/640/600, Zertifikat gleich/anders/keines, Gradle-Property aus Umgebung/Datei, `~`-Auflösung) — kein Text-Vergleich.
- **Geräteprüfung B1 (Pixel 8 Pro, Debug-Build mit ECU-Simulator, dieselbe Geste vorher und nachher):** Alter Stand v1.24.6 — nach dem Wegwischen verschwindet der Task, **Dienst, Benachrichtigung und Prozess laufen weiter** (der Befund, bestätigt). Neuer Stand — Dienst beendet, Benachrichtigung weg, Prozess beendet. „Home“ lässt die Sitzung wie gewollt weiterlaufen. Das `contentIntent` der Benachrichtigung ist beim neuen Stand vorhanden, beim alten nicht.

### Bekannte Grenzen

- **Das Fangnetz um `startForeground()` ist auf dem Gerät nicht ausgelöst worden:** Die Ablehnung lässt sich nicht erzwingen, ohne eine Berechtigung zu entziehen (das beendet den Testprozess). Abgesichert ist die Entscheidung
  (`catchingForegroundStart`: welche Ausnahmen, welche nicht) und die Verdrahtung im Koordinator/in den Einstellungen per JVM-Test; der Aufruf im Dienst selbst ist ein Framework-Aufruf.
- `onTaskRemoved` und `MainActivity.onDestroy` sind Framework-Methoden: JVM-getestet ist die Entscheidung (`shouldEndSessionOnDestroy`) und das Verhalten des Controllers; die Verdrahtung wurde von Hand am Pixel mit dem ECU-Simulator des Debug-Builds geprüft (s. oben).
- Wie sich verschiedene Hersteller beim Wegwischen verhalten (manche beenden den Prozess ohnehin sofort), ist nicht geprüft.

## Version 1.24.6 (versionCode 88)

Verbindungs-Robustheit, ehrliche Fehlercode-Seite und Kosmetik (QS-Bericht `docs/qa/2026-10-04-qa-report.md`, Befunde B6 (Verbindungsaufbau), B7, B9, A9 (Teil 1), B22, B26, B27, B28, B30).

### Verbindungsaufbau: begrenzt, abbrechbar, ohne Leck (B6, B7)

- **Ein hängender Verbindungsaufbau hielt „Beenden“ und „Trennen“ fest.** `BluetoothSocket.connect()` blockiert den Thread (auf einer schlechten Strecke
  rund 12 s) und lässt sich nur durch `close()` von außen abbrechen; `connect()` + Initialisierung + PID-Discovery liefen ohne Zeitgrenze unter dem
  Verbindungs-Lock. Jetzt gilt eine **Obergrenze von 60 s** für den ganzen Aufbau, und ein Wächter schließt die Verbindung in drei Fällen: Frist abgelaufen
  (Fehler „Adapter antwortet nicht“), Aufrufer abgebrochen, und **„Trennen“/„Beenden“ durch den Nutzer** — das bricht einen laufenden Aufbau sofort ab, statt dahinter zu
  warten. Die 60 s sind absichtlich großzügig: Die Protokollsuche des ELM327 dauert bei einem K-Line-Motorrad einige Sekunden; die Grenze beendet nur ein ENDLOSES
  Hängen.
- **`ClassicBtConnection` (B7):** Der Socket wurde erst nach einem erfolgreichen `connect()` gemerkt — `close()` fand beim Verbinden nichts, und bei einem Fehlschlag
  blieb der Socket offen (Deskriptor-Leck bis zur Garbage Collection, bis zu fünf je Ausfall der Wiederverbindungs-Schleife). Jetzt ist er vor `connect()` bekannt,
  wird bei Fehlschlag und Abbruch geschlossen, und `close()` wirft nie (auch nicht bei einem schon geschlossenen Socket).
- **Bewusst NICHT geändert:** der Lese-Hänger im laufenden Betrieb auf klassischem Bluetooth (die zweite Hälfte von B6). Ein Schließen nach 5 s würde eine langsame
  K-Line-Protokollsuche (über 5 s) abbrechen, die heute nur funktioniert, weil das Lesen dort nicht unterbrechbar ist — das braucht einen Test mit dem Adapter.

### Fehlercode-Seite: „Keine Antwort“ ist keine Entwarnung (A9, Teil 1)

Der Parser lieferte für **jede** Antwort, die nicht mit `43`/`47` begann, eine leere Liste — NO DATA, UNABLE TO CONNECT, CAN ERROR, „?“ — und die Seite zeigte den
grünen Haken „Keine Fehlercodes vorhanden“. Jetzt gilt nur eine **positive** Antwort (`43 00` = „keine Codes“) als Aussage des Steuergeräts:

- **NO DATA** → Meldung „Keine Antwort vom Steuergerät — ob Fehlercodes gespeichert sind, lässt sich so nicht sagen.“ mit „Erneut versuchen“; nichts landet in der Historie.
- **Busfehler des Adapters** (UNABLE TO CONNECT, CAN ERROR, BUS ERROR, BUS BUSY, FB ERROR, BUS INIT-Fehler) und **Unverständliches** → eine Fehlermeldung.
- **Modus 07 (ausstehende Codes) ist optional:** NO DATA dort wird toleriert (viele Steuergeräte schweigen, wenn nichts ansteht) — die bestätigten Codes werden trotzdem gezeigt.
  Ein Busfehler im selben Zug bleibt ein Fehler.
- Die Meldungen „SEARCHING...“/„BUS INIT: OK“ vor einer Antwort werden ignoriert. Jede gescheiterte Fehlercode-Abfrage steht mit dem Adaptertext im App-Log
  (`Fault-code read failed: …`) — genau das Material, das für Teil 2 gebraucht wird.
- **Risiko, ehrlich:** Antwortet Ihr Motorrad auf Modus 03 mit NO DATA, wenn nichts gespeichert ist (nicht normgemäß, aber bei manchen Steuergeräten üblich), sehen Sie
  jetzt „Keine Antwort“ statt des grünen Hakens. Das ist die ehrliche Aussage; das App-Log zeigt dann den Adaptertext, und Teil 2 (CAN-Count-Byte, Mehrframe) wird mit
  einem Roh-Log abschließend geklärt. Der ECU-Simulator im Debug-Build antwortet jetzt wie ein gesundes Steuergerät (`43 00`, `47 00`).

### Der Adapter liefert Leerzeichen (B9)

Die Initialisierung sendete `ATS0` („Leerzeichen aus“), aber **alle** Parser splitten an Leerzeichen — ein Adapter, der den Befehl befolgt, antwortet `410C1AF8`, und keine
PID-Antwort wäre lesbar. Jetzt wird `ATS1` gesendet (die Voreinstellung nach `ATZ`, explizit). Die Kommentare, die das Gegenteil behaupteten, sind korrigiert. Bei Ihrem
Adapter ändert sich nichts Sichtbares (er lieferte ohnehin Leerzeichen).

### Kosmetik und Randfälle (B22, B26, B27, B28, B30)

- **B22 — Getriebe-Eingaben:** Plausible Grenzen (Radumfang 1000–4000 mm, Primärübersetzung 0,5–10, Sekundär 0,5–15, Gang 0,2–8). „2.096“ (gemeint: 2096 mm) wurde als
  2,096 mm gespeichert, und die Ganganzeige blieb ohne Hinweis für immer „–“; jetzt zeigt das Feld „Erlaubt: 1000 bis 4000“ und speichert nichts. **`0` schaltet einen Gang ab**
  (ein 5-Gang-Getriebe hat keinen sechsten); die Gangerkennung überspringt ihn. Das ViewModel verwirft unplausible Werte zusätzlich und protokolliert sie.
- **B26 — dynamische Drehzahl-Schwelle:** Fehlte in einem Zyklus die Temperatur, fiel die Schwelle für diesen Zyklus auf den Kaltwert (ein falscher Schaltblitz).
  Jetzt gilt die letzte Temperatur, bis eine neue kommt oder das Steuergerät schweigt.
- **B27 — abgeschnittene Kachelwerte:** „11500“ wurde bei großer Systemschrift oder schmalem Display still zu „1150“. Der Wert **schrumpft** jetzt (bis 20 sp), bis er passt;
  die Einheit behält ihren Platz.
- **B28 — Warn-Gelb auf hellem Hintergrund:** Das kräftige Gelb hatte auf der hellen Fläche des Default-Schemas nur 1,87:1. Dort gilt ein dunkleres Gelb (`#A16207`, ≥ 4,5:1);
  auf den dunklen Cockpit-Schemata bleibt alles unverändert (`StatusColors.warningOn(surface)`).
- **B30 — Sparkline:** Jeder GPS-Fix wiederholte die letzten OBD-Werte und füllte den Puffer mit Duplikaten (Treppenkurve, Fenster ~12 s statt ~60 s). Ein Punkt entsteht
  jetzt nur für einen **neuen** Messwert, frühestens 800 ms nach dem letzten desselben PIDs.

### Tests

- **1167 JVM-Unit-Tests** (vorher 1099, +68) und **280 Instrumentation-Tests auf dem Pixel 8 Pro** (vorher 277, +3: Kachelwert schrumpft statt abgeschnitten zu werden), alle grün; Lint 0 Fehler / 26 Warnungen (unverändert).
- **Mutationsprüfung:** 27 gezielte Eingriffe in den neuen Code (Wächter, Abbruchwege, Socket-Verwaltung, Parser-Zweige, Anzeige-Regeln) — **alle von mindestens einem Test erkannt**. Zwei überlebten zunächst (die Überspringen-Regel in der Gangerkennung war für den Wert 0 überflüssig, schützt aber vor negativen Altwerten) — dafür kamen zwei Tests dazu. Ein dritter Befund betraf die Tests selbst: Eine blockierende Test-Verbindung ohne Zeitgrenze hängte bei fehlendem `close()` den ganzen Testlauf auf, statt rot zu werden; sie wartet jetzt höchstens 5 s und prüft, dass `close()` sie freigegeben hat.
- Neue Test-Dateien: `SparklineSamplingTest`, `StatusColorsTest`; erweitert u. a. `ClassicBtConnectionTest`, `ObdRepositoryTest`, `DtcParserTest`, `ElmControllerTest`, `DashboardViewModelTest`, `GearSettings*Test`, `GearIndicatorTest`.

### Bekannte Grenzen

- Das 60-s-Zeitlimit des Verbindungsaufbaus ist eine begründete Annahme, nicht am Adapter gemessen; ob `BluetoothSocket.close()` auf Ihrem Telefon ein blockiertes
  `connect()` freigibt, bestätigt erst ein Lauf mit Adapter (die Android-Dokumentation sagt ja).
- A9 Teil 2 (Anzahl-Byte nach `43` auf CAN, Mehrframe-Antworten) und A8 (Frame-Byte bei Freeze-Frame) brauchen ein Roh-Log Ihres Motorrads.
- Die drei Schwellen der Sparkline und des Warn-Gelbs sind Annahmen, keine Fahrzeugmessungen.

## Version 1.24.5 (versionCode 87)

Tests, Release-Ablauf und Doku aufgeräumt (QS-Bericht `docs/qa/2026-10-04-qa-report.md`, Befunde B32, B34, B35, B36, B37, B38, B39) — und zwei
Fehler im Verbindungs-Code, die dabei zutage kamen (B8 und eine falsche Timeout-Einstufung).

### Zwei Fehler, die die neuen Tests aufdeckten

- **Die Fahrzeug-Discovery verlor den Bereich 01–20 (B8).** Der erste OBD-Befehl nach `ATSP0` löst beim ELM327 die Protokollsuche aus; der
  Adapter stellt dann „SEARCHING...“ (bei ISO/KWP auch „BUS INIT: OK“) **vor** die Antwort `41 00 …`. Die Auswertung verlangte „41“ als allererstes
  Token — mit dem Banner ging der ganze Bereich verloren, `supportedPids` enthielt dann u. a. weder Geschwindigkeit noch Last noch Drossel. Jetzt
  wird der erste Eintrag `41 <Bereich>` gesucht; das schließt zugleich eine verspätet eintreffende Antwort auf einen anderen Bereich aus, und zu kurze
  Antworten ergeben `null` statt geratener Bytes. *Nicht an einem echten Adapter geprüft* (Grundlage: das ELM327-Datenblatt).
- **Ein stiller, aber unterbrechbarer Adapter meldete `ConnectionLost` statt `Timeout`.** Läuft das Lese-Zeitlimit ab, unterbricht Kotlin den
  blockierenden Lesevorgang; ein unterbrechbarer Eingang (die BLE-Pipe, der Simulator) wirft dann eine `InterruptedIOException`, die als
  „Verbindung verloren“ gemeldet wurde — und eine echte Ausnahme schlägt bei Kotlin die Abbruchursache. Folge: Statt des toleranten Timeout-Wegs
  (Messwert überspringen, Watchdog entscheidet) kam sofort Trennen und Reconnect. Ebenso meldete ein **von außen abgebrochener** Lesevorgang
  (Kachel-Wechsel, Trennen) `ConnectionLost` und konnte so einen überflüssigen Reconnect anstoßen. Jetzt ist ein IO-Fehler nach dem Abbruch unser
  eigener Interrupt und wird zu Timeout bzw. sauberem Abbruch. (Die klassische Bluetooth-Verbindung (RFCOMM) ist nicht betroffen: dort lässt sich
  das Lesen gar nicht unterbrechen, B6.)

### Tests

- **Locale (B35).** Zwei Tests hingen an der Standard-Locale der Maschine (`.contains(".")`, `"14.2 (V)"`) und waren unter `de_DE` rot. Sie pinnen die
  Locale jetzt (`withDefaultLocale`), prüfen exakt und decken Punkt **und** Komma ab. Die JVM-Tests laufen künftig unter `de_DE`, damit eine versteckte
  Abhängigkeit sofort auffällt.
- **Produktions-Bootstrap statt Nachbauten (B36).** Datenbankversion und Migrationsliste stehen einmal (`APP_DATABASE_VERSION`, `ALL_MIGRATIONS`,
  `appDatabaseBuilder`), `AppModule` und die Tests benutzen sie; ein JVM-Test prüft, dass die Kette lückenlos von 1 bis zur aktuellen Version reicht — wer eine
  Version anhebt oder eine Migration nicht einträgt, scheitert vor dem Release statt beim Update eines Nutzers. Der **Foreign-Key-Callback in `AppModule`
  war überflüssig** (Rooms generierter `onOpen` setzt `PRAGMA foreign_keys = ON` selbst) — am Gerät über den Produktions-Builder nachgewiesen
  (`AppDatabaseBootstrapTest`: Pragma, Cascade, abgelehnter Verstoß) und entfernt; die nachgebauten Callbacks in den DAO-Tests ebenfalls. Der
  Benachrichtigungskanal entsteht in `createObdNotificationChannel`, das `Obd2Application` **und** der Service-Test benutzen.
- **Eigener DataStore je Instrumentation-Test (B37).** Alle Tests teilten den echten `app_prefs` der Debug-App: reihenfolgeabhängig, und sie überschrieben
  die Entwicklereinstellungen auf dem Gerät. Jetzt hat jeder Test einen frischen Speicher in einer Temp-Datei (`TestDataStoreIsolationTest` beweist es mit
  zwei spiegelbildlichen Tests).
- **Timeout und „SEARCHING...“ (B38).** Das Lese-Zeitlimit des `ElmController` ist ein Konstruktor-Parameter (Standard unverändert 5 s) und damit testbar
  (100 ms); neu: Timeout-Abbildung, Abbruch von außen, die „SEARCHING...“- und „BUS INIT“-Fixtures bis in die Discovery.
- **B34** (Test für einen werfenden `writeReadings`) war seit 1.24.0 erledigt (`ReadingsPipelineTest`) und ist nur abgehakt.

### Release-Ablauf (B32) und Doku (B39)

- **`scripts/release.sh`** hält den Ablauf fest: sauberer Arbeitsbaum (der Version-Bump muss **committet** sein, bevor gebaut wird), README und Release-Notes
  nennen die Version, Tag noch frei → Unit-Tests → `assembleRelease` → eingebettete Revision == HEAD → Signatur → `mapping.txt` (45 MB) und Prüfsumme
  außerhalb des Repos archivieren → optional `--install`. Taggen und Pushen bleiben Handarbeit.
- **`ReleaseDocsConsistencyTest`** hält Version und versionCode in `build.gradle.kts`, README und `RELEASE_NOTES.md` zusammen (die README nannte bis zur QS 1.17.7).
- **Doku an den Ist-Stand:** README-Berechtigungstabelle vollständig (Hintergrund-Standort, `FOREGROUND_SERVICE*`, `INTERNET`; was zur Laufzeit angefragt
  wird und was nicht), Einstellungsliste führt die tatsächlich vorhandenen Einstellungen statt „Aufzeichnungs-Modus“ und „Log-Verbosity“, die es nicht gibt;
  CLAUDE.md beschreibt das Dashboard-Raster (feste `Row`/`Column` mit 1-dp-Trennlinien, kein `LazyVerticalGrid`) und die Screen-Übergänge (Standard-Überblendung
  statt der dokumentierten 220-ms-Slide-Vorgabe, die offen bleibt) wahrheitsgemäß.

### Zahlen

+19 JVM-Tests (1099 insgesamt) und +6 Instrumentation-Tests (277 insgesamt: Datenbank-Bootstrap 3, DataStore-Isolation 2, Benachrichtigungskanal 1). Neun bewusst eingebaute Fehler wurden erkannt; einer überlebte zunächst (das gekürzte Discovery-Fixture lieferte
zufällig nur unbekannte PIDs) und bekam ein Fixture, dessen Teilbytes sonst RPM und Speed ergäben.

### Bekannte Grenzen

- B8 ist am echten Adapter nicht bestätigt (nur per Fixture nach Datenblatt).
- Die Zeitlimit-Korrektur wirkt nur dort, wo das Lesen unterbrechbar ist; der Kern von B6 (nicht unterbrechbarer RFCOMM-Lesevorgang, Verbindungsaufbau ohne
  Zeitlimit) bleibt offen.
- Das Release-Skript ist nicht automatisch ausgeführt getestet (nur Syntax-Test und die Vorprüfung); der erste echte Lauf ist dieser Release.

## Version 1.24.4 (versionCode 86)

Dashboard-Zustände und sichtbare Berechtigungen (QS-Bericht `docs/qa/2026-10-04-qa-report.md`, Befunde B10, B20, B21, B23, B24).

### „Verbunden“ heißt verbunden — sofort (B24)

Nach einem erfolgreichen Verbinden zeigte das Dashboard 2–10 s lang „Vorschau · nicht verbunden“ und einen roten/gelben Punkt, weil
der Zustand „verbunden“ erst mit dem ersten Messwert kam (vorher liest die Sitzung FIN und Fehlercodes). Jetzt gilt der Link, sobald das
Repository ihn meldet: der Punkt wird grün, das Band sagt **„Verbunden · warte auf Werte“**, die Kacheln zeigen „--“. Kommt auch nach
10 s kein Wert (Steuergerät schweigt von Anfang an, z. B. Zündung aus), endet das bei „Keine aktuellen Werte“ statt bei ewigem
„warte“ — die Frist aus 1.24.3 startet jetzt mit der Sitzung.

### Kein Vollbild-Spinner mehr (B23)

Der Spinner („verbunden, aber keine Werte für die Slots“) verdeckte die Kacheln. Belegten alle vier Slots PIDs, die das Fahrzeug nicht
liefert, blieb er für immer stehen — der Slot-Picker war unerreichbar. Er ist entfernt. Das Dashboard kennt jetzt vier Zustände
(`dashboardPhase`: Offline · Warte · Live · Veraltet), die Kacheln, Drehzahlbalken und Band gemeinsam steuern. Fließen Werte, aber
keiner passt zu einem Slot, ist das **Live**: jede Kachel zeigt ihren Hinweis („Vom Fahrzeug nicht unterstützt“) und lässt sich per
Long-Press neu belegen.

### Fehlende Standortberechtigung wird gesagt (B10, B21)

- **GPS-Schalter AN, Berechtigung weg** (in den Systemeinstellungen entzogen, „Nur dieses Mal“ abgelaufen): GPS lieferte still nichts. Jetzt
  steht unter dem Schalter **„Standortberechtigung fehlt …“** mit dem Knopf **„In den App-Einstellungen erteilen“**; die Geschwindigkeits-Kachel
  sagt „Standortberechtigung fehlt“ statt endlos „Warte auf GPS-Signal“.
- **„Ungefähr“ oder „Ablehnen“ im System-Dialog:** Der Schalter blieb still aus. Jetzt erscheint derselbe Hinweis (die Geschwindigkeit
  braucht den genauen Standort).
- **Außentemperatur ohne Standort:** Die Berechtigung wird nur einmal angefragt; wer „Nicht erlauben“ wählte, sah nie, warum die
  Temperatur fehlt. Unter „Navigationsleiste beim Fahren ausblenden“ steht jetzt der Grund samt Knopf (genau oder ungefähr genügt).
- Der Stand wird bei jedem Zurückkehren in die App neu gelesen (`LocationPermissionState`, `ON_RESUME`).
- **Nachträglich erteilt = sofort aktiv:** `GpsSpeedSource` prüfte die Berechtigung nur beim Start des Flusses; wer sie später erteilte,
  bekam GPS-Speed erst nach einem Neustart. Jetzt wird alle 2 s neu geprüft, solange sie fehlt.

### Fehlercodes: „Erneut versuchen“ und Pull-to-Refresh im Fehler- und Leerzustand (B20)

Im Fehlerzustand gab es nur den Text, im Leerzustand („Keine Fehlercodes“) ließ sich die Seite nicht herunterziehen, weil
`PullToRefreshBox` nur über einen scrollbaren Nachfahren erkennt, dass gezogen wird. Beide Zustände liegen jetzt in einem scrollbaren
Container; der Fehlerzustand hat einen 56-dp-Knopf „Erneut versuchen“.

### Tests

+27 JVM-Tests (Dashboard-Zustand, Hinweis-Entscheidungen, Platzhalter, GPS-Nachprüfung, Link-Hoch/Warten im ViewModel, Stale ab Sitzungsbeginn)
und +6 Instrumentation-Tests (Warte-Band, kein Spinner bei Werten ohne Slot, DTC-Wiederholen und -Ziehen, Hinweiszeile). Dreizehn bewusst
eingebaute Fehler wurden erkannt; einer überlebte zunächst (das Rücksetzen von „warte“ im Reconnect-Zweig) und bekam einen Test. Der erste Lauf der Instrumentation-Tests deckte eine Regression auf, die kein JVM-Test sah: `startReconnecting()` ließ `isConnected` wahr (aus `onLinkUp`), solange `isConnectedFlow` nicht gekippt war — das Dashboard zeigte „verbunden“ statt „verbindet“. Ein gemeldeter Verbindungsverlust setzt jetzt selbst `isConnected = false` (mit eigenem Test). Ein
bestehender Test (GPS-Berechtigung fehlt: „nichts registrieren“) musste von `advanceUntilIdle()` auf `advanceTimeBy` umgestellt werden,
weil die Nachprüfung endlos weiterläuft.

### Bekannte Grenzen

- Die Hinweiszeilen sind am Gerät nicht mit einer echten Berechtigungsänderung durchgespielt (das lässt sich im Instrumentation-Test
  nicht ohne die Systemeinstellungen nachstellen); abgedeckt sind die Entscheidungsfunktionen und die Zeile selbst.
- Wird die Berechtigung WÄHREND laufender Updates entzogen („Nur dieses Mal“ läuft ab), werden die Updates nicht neu registriert; die
  Geschwindigkeit fällt nach 2 s auf „unbekannt“, und der Hinweis erscheint beim nächsten Zurückkehren in die App.

## Version 1.24.3 (versionCode 85)

Verlässlichere Anzeige im Fahrbetrieb (QS-Bericht `docs/qa/2026-10-04-qa-report.md`, Befunde B11, B12, B29).

### Das Hauptmenü flackert im Stand nicht mehr (B11)

Die Fahrterkennung lautete „Geschwindigkeit > 0“. Das Rauschen eines GPS-Empfängers im Stand reicht dafür: Hauptmenü und
Statuszeile schalteten an der Ampel hin und her, und der Wetter-Abruf lief an. Jetzt gilt eine Hysterese
(`isMoving`): Eine **Fahrt beginnt ab 5 km/h** und **endet erst unter 3 km/h**; dazwischen entscheidet der bisherige Zustand. Im
Stau (6 → 4 km/h) bleibt es Fahrt, ein einzelner Zacken im Stand startet keine. Eine fehlende oder unplausible Geschwindigkeit
gilt weiter als „steht“ — die Leiste bleibt nie dauerhaft ausgeblendet. **Verhaltensänderung:** Bei 1–4 km/h (Rangieren) bleibt das
Hauptmenü jetzt sichtbar. Die Schwellen sind Konstanten (`MOVING_ENTER_KMH`, `MOVING_LEAVE_KMH`).

### Nach einem Reconnect erscheinen keine alten Werte mehr (B12)

Der Zwischenspeicher der letzten OBD-Werte wurde nie geleert. Kam nach einem Wiederverbinden ein GPS-Update, bevor der erste
echte Zyklus eintraf, führte die App die **alten** Werte der vorigen Verbindung neu zusammen und zeigte sie als live. Jede neue
Sitzung beginnt jetzt leer (auch die Fahrterkennung beginnt bei „steht“).

### Schweigt das Steuergerät, gelten die Werte als veraltet (B29)

Antwortet das Steuergerät bei bestehender Adapterverbindung nicht mehr (typisch: Zündung aus — der Watchdog toleriert reines NO
DATA bewusst), blieben die zuletzt gelesenen Werte als „live“ stehen, und die Fahrterkennung konnte dauerhaft auf „fährt“
hängen bleiben. Kam in der Zwischenzeit ein GPS-Update, wurden die alten Werte sogar mit der frischen GPS-Geschwindigkeit
vermischt. Jetzt meldet die Pipeline nach **10 s ohne Zyklus mit Messwert** `SessionEvent.Stale`:

- Kacheln zeigen „--“, der Drehzahlbalken ist leer, die Ganganzeige „–“; das Band über den Kacheln sagt **„Keine aktuellen Werte“**
  (der Verbindungs-Punkt bleibt — der Adapter ist ja da). Ein Vollbild-Spinner erscheint dabei nicht.
- Die Fahrterkennung geht auf „steht“, und GPS-Updates führen die alten Werte nicht mehr neu zusammen.
- Der nächste echte Zyklus hebt den Zustand sofort auf und startet die Frist neu.

Die Meldung gilt nur, solange die Messwert-Quelle läuft: Eine beendete Quelle (Verbindungsverlust, Fehler) meldet das auf ihrem
eigenen Weg, und `isStale` endet mit der Verbindung.

### Tests

+19 JVM-Tests und +2 Instrumentation-Tests (ein Ende-zu-Ende-Test mit echter 10-s-Wartezeit: Kacheln werden „--“, Band erscheint,
kein Spinner). Neunzehn bewusst eingebaute Fehler (Wächter entfernt) wurden erkannt; drei überlebten zunächst (Zurücksetzen der
Hysterese beim Sitzungsstart, Aufheben des „veraltet“-Zustands durch einen frischen Zyklus, Rücksetzen von `isStale` im
Reconnect-Zweig) und bekamen je einen Test. Ein bestehender Test („GPS-Wiederholungen schalten den Grün-Timer nicht weiter“) prüfte
den Mechanismus über genau die unbegrenzten Wiederholungen, die B29 jetzt beendet; er speist sie nun direkt ein und behält so die
zweite Verteidigung im ViewModel unter Test.

### Bekannte Grenzen

- Die Schwellen 5/3 km/h und die 10 s sind begründete Annahmen, nicht an einem Fahrzeug gemessen.
- Fährt das Fahrzeug bei „steht“-Meldung der Pipeline weiter (z. B. Zündung aus im Rollen), zeigt das Hauptmenü wieder — gewollt.

## Version 1.24.2 (versionCode 84)

Stabilität der Verbindung (QS-Bericht `docs/qa/2026-10-04-qa-report.md`, Befunde B2, B3, B5).

### „Trennen“ bleibt getrennt

Bisher kannte die App keinen Unterschied zwischen „Verbindung verloren“ und „Nutzer hat getrennt“. Wer während einer
laufenden Wiederverbindung (bis zu 48 s Wartezeit pro Versuch) in der Benachrichtigung auf **Trennen** tippte, bekam die
Verbindung beim nächsten Versuch zurück — samt neu gestartetem Dienst und Benachrichtigung. Jetzt zählt das Repository
Nutzer-Trennungen (`userDisconnectCount`; „Trennen“ in der Benachrichtigung, in den Einstellungen und „Beenden“ rufen
`disconnectByUser()`), und die Wiederverbindung bricht ab:

- **sofort**, wenn sie gerade wartet (die Wartezeit endet vorzeitig),
- vor jedem Versuch,
- und nach einem Versuch, der währenddessen fertig wurde — dann wird weder der Dienst gestartet noch „verbunden“ gemeldet.

Das Dashboard geht dabei sauber auf „getrennt“, ohne Spinner und ohne den Fehler eines früheren Versuchs. Auch ein Lesefehler, der
nur **durch** das Trennen entsteht (der Socket wird geschlossen, ein laufendes Lesen scheitert), löst keinen Reconnect mehr aus —
weder beim Messen noch beim Auslesen der Fehlercodes. Eine Trennung **vor** einer späteren Aufforderung unterdrückt diese nicht:
reißt die Verbindung später wirklich ab, wird weiterhin wiederverbunden.

### Ein Reconnect reißt keine frische Verbindung mehr ab

Die Wiederverbindung prüfte „schon verbunden?“ außerhalb der Sperre des Repositories. Wurde in diesem Fenster (so lang wie ein
laufendes manuelles Verbinden) eine Verbindung hergestellt, schloss der wartende automatische Versuch sie sofort wieder und
baute neu auf. Die Prüfung läuft jetzt innerhalb der Sperre: `reconnect()` tut nichts, wenn schon verbunden ist, und der
Auto-Connect beim Start sowie die Schleife nach einem Kaltstart nutzen das neue `connectIfDisconnected()`. Das manuelle
„Verbinden“ bleibt bewusst unbedingt — der Nutzer darf den Adapter wechseln.

### Ein abgebrochener oder toter Verbindungsaufbau gilt nicht mehr als „verbunden“

Die Suche nach den vom Steuergerät unterstützten Messwerten verschluckte **jede** Ausnahme. Wurde der Aufrufer dabei abgebrochen
(z. B. Antippen von „Trennen“ oder Verlassen der App während des Verbindens), kam `connect()` trotzdem „verbunden“ zurück. Riss
der Link währenddessen ab, galt eine bereits tote Verbindung als aufgebaut. Abbruch und `ConnectionLost` werden jetzt
weitergereicht; die Verbindung wird dabei geschlossen. Alles andere (stille Bereiche, unbekannte Antworten) bleibt tolerant.

### Tests

+19 JVM-Tests; die Instrumentation-Tests bleiben bei 263 (`ObdForegroundServiceTest` und die Stubs der übrigen wurden auf die neue Repository-API angepasst). Achtzehn bewusst eingebaute Fehler (Wächter entfernt) wurden jeweils von einem
Test erkannt; einer überlebte zunächst (die Rundenanfangs-Prüfung, redundant zur Schleifenprüfung außer im „kein Gerät“-Fall) und
bekam daraufhin einen eigenen Test. Zwei bestehende Tests, die das alte Verhalten festschrieben, wurden angepasst: „reconnect
schließt die erste Verbindung“ (jetzt: nach einem verlorenen Link) und die Auto-Connect-Tests (jetzt `connectIfDisconnected`).

### Bekannte Grenzen

- Ein Nutzer-Trennen, das **genau zwischen** der Prüfung nach einem erfolgreichen Versuch und dem Dienst-Start eintrifft, kann den Dienst
  kurz neu starten (Mikrosekunden-Fenster); die darauf folgende Trennung beendet ihn wieder.
- Ein Verbindungsaufbau hat weiterhin kein Zeitlimit (B6 im Backlog): „Trennen“ wartet, bis ein laufender Versuch endet.

## Version 1.24.1 (versionCode 83)

Ehrlichere Fehleranzeige und Schutz vor einer Absturzschleife (QS-Bericht `docs/qa/2026-10-04-qa-report.md`, Befunde B15, B18, B19, B25).

### Keine falsche Entwarnung mehr

- **Fehlermeldung ohne Text.** Die Fehlercode-Seite und die Verbindungsfehler in den Einstellungen speicherten nur `e.message`.
  Hatte eine Ausnahme keine Meldung (`null`), sah die Oberfläche das als „kein Fehler“ — die Seite zeigte dann das grüne
  „Keine Fehlercodes vorhanden“, obwohl das Lesen gescheitert war. Alle Fehlerzustände (Fehlercodes, Freeze-Frame, FIN,
  Verbindung in den Einstellungen) laufen jetzt über den `ObdErrorMapper`, der **immer** einen Text liefert.
- **Lokalisierte Gründe.** Bekannte Ursachen erscheinen in der Sprache der App statt als englischer Roh-Text. Neu:
  „Verbindung zum Adapter fehlgeschlagen.“ und „Adapter nicht gefunden. Ist er gekoppelt und eingeschaltet?“. Unbekannte
  Ausnahmen zeigen weiter ihre technische Meldung (oder den Klassennamen) — fürs Fehlersuchen am Fahrzeug nützlicher als ein
  nichtssagendes „Unbekannter Fehler“.
- **Freeze-Frame und FIN ohne Verbindung.** Beide Seiten sagten „nicht verfügbar“, als hätte das Fahrzeug nichts geliefert;
  es war aber gar nicht gefragt worden. Jetzt steht dort „Nicht verbunden“, und es wird nichts gelesen. Die Seiten laden
  **von selbst**, sobald die Verbindung steht (vorher half nur das erneute Öffnen), und leeren sich beim Abbruch. Ein Lesevorgang,
  der erst nach dem Abbruch fertig wird, überschreibt nichts mehr.

### Der Grund eines Verbindungsfehlers ist sichtbar

Bisher war der rote Punkt am Bluetooth-Symbol das einzige Zeichen, dass etwas schiefging — nicht, **was**. Das Dashboard-Band
„Vorschau · nicht verbunden“ nennt jetzt den Grund darunter (z. B. „Verbindung dauerhaft verloren.“, „Bluetooth ist
deaktiviert.“, „Adapter antwortet nicht.“). Läuft gerade ein neuer Verbindungsversuch, bleibt das Band neutral, damit es nicht
dem pulsierenden Punkt widerspricht. Zusätzlich schreibt die App jeden fehlgeschlagenen Reconnect-Versuch mit seiner
Ursache ins App-Log (bisher nur das Endergebnis).

### Beschädigte Einstellungsdatei führt nicht mehr zur Absturzschleife

Wäre die Einstellungsdatei (DataStore) beschädigt, hätte schon das Lesen des Farbschemas beim Start eine Ausnahme geworfen —
bei **jedem** Start, bis die App-Daten gelöscht werden. Jetzt wird eine beschädigte Datei durch leere Einstellungen ersetzt
(alle Standardwerte), und ein E/A-Fehler beim Lesen liefert ebenfalls die Standardwerte. Die Einstellungen gehen in diesem
Fall verloren; das ist der kleinere Schaden. (Auslöser sind in der Praxis extrem selten: das Schreiben ist atomar.)

### Tests

+32 JVM-Tests und +11 Instrumentation-Tests. Elf bewusst eingebaute Fehler (Wächter entfernt) wurden jeweils von einem Test
erkannt. Die Verdrahtung des Handlers im echten `Context.dataStore` (eine Zeile) lässt sich nicht ohne die echte
Einstellungsdatei testen und ist daher nur durch den Test des Handlers selbst abgedeckt.

## Version 1.24.0 (versionCode 82)

Robustheit der Aufzeichnung und automatische Aufbewahrung (QS-Bericht `docs/qa/2026-10-04-qa-report.md`, Befunde B13, B14, B16, B17).

### Die Live-Anzeige überlebt Datenbankfehler

Bisher beendete **jeder** Datenbankfehler beim Anlegen der Fahrt oder beim Schreiben der Messwerte (Speicher voll, Datenbank
gesperrt) die gesamte Anzeige mit einer Fehlermeldung — obwohl das Aufzeichnen nur Beiwerk ist. Jetzt läuft die Messung
weiter; der Fehler wird einmal je Sitzung im App-Log vermerkt („Recording failed …“) und an den Crash-Reporter gemeldet, nicht
bei jedem Zyklus (ein Fehler mit 2 Hz würde sonst das ebenfalls betroffene Log fluten). Auch `AppLogRepository.log()` wirft
nicht mehr in den Aufrufer: eine Protokollzeile konnte über Verbindungs-, Wetter- und Messpfade den ganzen Prozess beenden.

### Keine Geisterfahrten mehr

- **Abbruch beim Anlegen einer Fahrt.** Wurde die Verbindung genau dann getrennt (oder eine Kachel umbelegt), während die
  Fahrt angelegt wurde, ging die Fahrt-Id verloren: eine leere Fahrt blieb für immer als „aktiv“ geschützt und
  unlöschbar. Das Anlegen ist jetzt abbruchsicher; wird der Aufrufer währenddessen abgebrochen, verwirft die App die leere
  Fahrt wieder, und ein bereits abgebrochener Aufrufer legt gar keine an.
- **„Nur Fahrten mit DTCs speichern“.** Meldet das Fahrzeug keine Fehlercodes, wurde die Fahrt trotzdem angelegt,
  aufgezeichnet und erst am Ende verworfen. Jetzt entsteht sie gar nicht — keine unnötige Schreiblast, und ein App-Absturz
  mittendrin hinterlässt keine DTC-freie Fahrt entgegen der Einstellung.

### Aufbewahrung beim App-Start — Verhaltensänderung

Das Löschen alter Daten lief bisher nur, wenn man **Verlauf** bzw. **App-Logs** öffnete. Es läuft jetzt **einmal bei jedem
App-Start**, im Hintergrund:

- Fahrten, die älter als **90 Tage** sind, werden gelöscht (in kleinen Blöcken; eine laufende Fahrt wird nie angefasst).
- App-Log-Zeilen älter als **30 Tage** werden gelöscht, und das Log wird auf die neuesten **1000 Zeilen** begrenzt — das war
  bisher nur das Anzeige-Limit, die Tabelle wuchs weiter.

Das geschieht **ohne Rückfrage und ohne „Rückgängig“**. Wer Fahrten länger als 90 Tage braucht, exportiert sie vorher als CSV
(Verlauf → Teilen). Gelöscht wird nur lokal, nie im Fahrzeug. Ein Fehler in einem Schritt stoppt die anderen nicht und steht
im App-Log. Die Fristen stehen jetzt in der README.

### Tests

16 neue JVM-Tests (Aufbewahrung, Abbruchsicherheit von `startSession`, Aufzeichnungsfehler in der Pipeline, `log()`) und 3 neue
Datenbank-Tests auf dem Gerät. Sechs bewusst eingebaute Fehler (Wächter entfernt) wurden jeweils von einem Test erkannt.

### Bekannte Grenze

Der Altersschnitt hängt an der Geräteuhr: stellt man sie weit vor, löscht der nächste Start entsprechend viele Fahrten.

## Version 1.23.2 (versionCode 81)

Weitere Fehlerbehebungen aus der Qualitätssicherung (Bericht: `docs/qa/2026-10-04-qa-report.md`, Befunde A4–A6).

### Behoben

- **Verbindungsabbruch beim Auslesen der Fehlercodes wurde still verschluckt.** Antwortet das Steuergerät auf
  `03`/`07` nicht rechtzeitig, trennt die App den Adapter bewusst (ein später Rest der Antwort würde sonst den
  nächsten Befehl verfälschen). Danach zeigte das Dashboard „getrennt“, aber es folgte **kein** neuer Verbindungs-
  versuch — nur ein Verbindungsverlust beim Messen löste ihn aus. Das Auslesen läuft bei jedem Verbinden **und bei
  jedem Wechsel einer Kachel**; ein langsames Steuergerät konnte die Verbindung also schon beim Umbelegen kappen.
  Jetzt stößt die Pipeline in diesem Fall den Reconnect an, beendet die Sitzung sauber und schreibt die Warnung ins
  App-Log (auch wenn die Sitzung gerade abgebrochen wird). Ein Lesefehler bei bestehender Verbindung ändert nichts.
  Außerdem trennt `readDtcs()` jetzt auch bei einem Verbindungsverlust (wie das Messen und Freeze-Frame) und lässt
  keinen toten Adapter als „verbunden“ stehen.
- **Schreibfehler am Adapter umgingen den Reconnect.** Schloss der Adapter (oder das Funkmodul) die Verbindung,
  während die App pausierte, scheiterte der nächste Schreibzugriff mit einer rohen `IOException`: kein
  Reconnect, die Anzeige blieb „verbunden“, die Sitzung endete mit einer Fehlermeldung im Klartext. Ein
  Schreib- oder Flush-Fehler gilt jetzt wie ein Lesefehler als Verbindungsverlust.
- **Die Fahrgestellnummer war der rohe Adaptertext.** Die Antwort auf Modus 09 wurde unverändert als „FIN“
  gespeichert und angezeigt — ein Hexdump (`49 02 01 57 …`) oder bei Fahrzeugen ohne Modus 09 (viele Motorräder)
  „NO DATA“; so stand es in jeder Fahrt des Verlaufs. Neu dekodiert ein `VinDecoder` die Antwort: CAN mit mehreren
  Frames (Längenzeile, `0:`/`1:`/`2:`), K-Line/J1850 mit Kopf und Auffüllung je Zeile, ohne Leerzeichen, mit
  vorangestelltem „SEARCHING...“ oder „BUS INIT: OK“, in Kleinbuchstaben und doppelt von zwei Steuergeräten. Ergibt
  die Antwort keine 17 Zeichen (oder ist sie ein Fehlertext), ist die FIN unbekannt — nie mehr ein Hexdump.
  Bereits gespeicherte Fahrten behalten ihren Rohwert in der Datenbank (keine Migration); der Verlauf zeigt für sie
  „FIN unbekannt“, nur echte FINs werden angezeigt.

### Tests

24 neue JVM-Tests (VinDecoder mit den realen Antwortformen, Schreib-/Flush-Fehler, Verbindungsverlust in
`readDtcs()`, Reconnect-Signal und Log der Pipeline) und 1 neuer Instrumentation-Test (Verlauf mit altem Rohwert).
Die bisherigen Verlaufs-Tests nutzen jetzt gültige 17-stellige FINs.

### Bekannte Grenzen

Die Dekodierung ist gegen die in ISO 15765-4/SAE J1979 beschriebenen Antwortformen getestet, **nicht** gegen einen
echten Adapter — ob Ihr Fahrzeug eine FIN liefert, zeigt erst eine Fahrt (viele Motorräder kennen Modus 09 nicht;
dann bleibt „Kein VIN verfügbar“, ohne Fehler). Offen bleiben u. a. der Freeze-Frame-Frame-Byte und das
Anzahl-Byte der Fehlercodes auf CAN (beides braucht eine echte Antwort) — siehe Backlog im QS-Bericht.

## Version 1.23.1 (versionCode 80)

Fehlerbehebungen aus der Qualitätssicherung über den gesamten Code (Bericht: `docs/qa/2026-10-04-qa-report.md`).

### Behoben

- **Absturz auf der DTC-Seite.** Steht ein Fehlercode im Auslesen sowohl als bestätigt (Modus 03) als auch als
  ausstehend (Modus 07) — typisch für einen bleibenden Fehler — oder melden zwei Steuergeräte denselben Code, stürzte
  die Seite „Aktuell" ab (doppelter Listen-Schlüssel). Jetzt steht jeder Code genau einmal da; bestätigt gewinnt
  vor ausstehend.
- **Freeze-Frame-Seite zeigte nur einen Fehler.** Bei Werten mit der Einheit „%“ (Motorlast, Gaspedal, Kraftstoff-
  Trims, Tankfüllung) wurde die Einheit als Formatzeichen gelesen und das Auslesen brach ab — samt der Werte, die
  davor gelesen waren. Die Einheit wird jetzt getrennt angehängt.
- **Dauerhafte Sperre durch die Benachrichtigungs-Berechtigung (Android 13+).** Lehnte man „Benachrichtigungen"
  zweimal ab, blieb die App für immer auf dem Berechtigungs-Bildschirm: Android zeigt den Dialog dann nicht mehr,
  und die App bot keinen anderen Weg. Jetzt sperren nur noch die Bluetooth-Berechtigungen; die Benachrichtigung wird
  weiter mit angefragt, blockiert aber nichts (der Vordergrunddienst läuft auch ohne sie, nur seine Anzeige fehlt).
  Der Bildschirm hat außerdem einen Knopf **„App-Einstellungen öffnen"** und einen Hinweis dazu; beide Knöpfe sind
  56 dp hoch, der Inhalt scrollt im Querformat und bei großer Schrift.

### Tests

17 Prüfungen in fünf Instrumentation-Testklassen (`ThemePickerItemTest`, `LiveValueTileTest`, `DashboardScreenTest`,
`SlotPickerSheetTest`, `BottomNavTest`) nutzten Kotlins `assert()` — das ist auf Android wirkungslos
(`Class.desiredAssertionStatus()` liefert dort immer `false`), die Prüfungen liefen nie. Sie sind jetzt
JUnit-Assertions; eine bewusst umgedrehte Erwartung schlägt auf dem Gerät nun fehl. Neu: Tests für das
Zusammenführen der Codes, die „%"-Einheit, die Aufteilung Pflicht/optional der Berechtigungen und den
Sperrbildschirm samt Einstellungs-Intent.

### Nicht Teil dieses Releases

Aus dem Bericht bleiben u. a. die rohe FIN-Anzeige (Modus 09), der stille Verbindungsabbruch nach einem Zeitüberlauf
beim Auslesen der Fehlercodes und der Schreibfehler am Adapter offen — sie stehen als Backlog im Bericht.
Befunde, die ein echtes Fahrzeug brauchen (Frame-Byte der Freeze-Frame-Antwort, Anzahl-Byte der Fehlercodes auf
CAN), sind nicht angefasst.

## Version 1.23.0 (versionCode 79)

### Lokale Fehlerhistorie

Die App merkt sich jetzt, welche Fehlercodes (DTCs) das Fahrzeug gemeldet hat und wann — auch nachdem es
sie nicht mehr meldet und ohne Verbindung. Auf der DTC-Seite wechselst du mit **Aktuell | Historie**:
„Aktuell" ist die Live-Liste wie bisher, „Historie" listet alle aufgezeichneten Codes mit Beschreibung,
„Erstmals … · Zuletzt …" und den Markierungen „Aktiv" (der Code stand im letzten erfolgreichen Auslesen,
bei bestehender Verbindung) und „Ausstehend" (zuletzt nur als pending gesehen). Aufgezeichnet wird passiv
bei jedem Verbinden sowie beim Öffnen und Aktualisieren der DTC-Seite; die Historie hält höchstens 200
Codes (das älteste fliegt raus — Schutz vor Phantomcodes eines defekten Adapters).

Löschen wie im Verlauf: Papierkorb oder Wischen pro Eintrag ohne Rückfrage (10 Sekunden „Rückgängig"),
„Historie löschen" in der Kopfzeile mit Rückfrage. Gelöscht wird ausschließlich die lokale Aufzeichnung —
die App löscht nie den Fehlerspeicher im Fahrzeug und schreibt nie ins Fahrzeug. Ein Code, der im
Rückgängig-Fenster erneut gelesen wird, wird mitgelöscht und erscheint beim nächsten Auslesen wieder.

### „Nicht verbunden" statt falscher Entwarnung

Ohne Verbindung zeigte die DTC-Seite „Keine Fehlercodes vorhanden." — obwohl das Fahrzeug gar nicht
gefragt wurde. Jetzt steht dort „Nicht verbunden"; es wird nichts gelesen und nichts aufgezeichnet. Beim
Verbinden liest die Seite automatisch neu, beim Trennen räumt sie die veraltete Liste weg.

### Wichtig: Datenbank-Update

Dieses Update erweitert die Datenbank (Version 2 → 3, eine neue Tabelle `dtc_history`). Das geschieht
automatisch beim ersten Start; bestehende Fahrten, Messwerte und Logs bleiben erhalten (per Migrationstest
auf dem Gerät geprüft, auch die Kette von Version 1 aus). Es ist kein destruktiver Fallback eingerichtet:
Schlüge die Migration fehl, stürzte die App beim Start ab — die Daten blieben dabei unberührt (Room
migriert in einer Transaktion). Ein Zurück auf eine ältere App-Version ist nach dem Update voraussichtlich
nur mit gelöschten App-Daten möglich (nicht ausprobiert).

### Unter der Haube

`DtcHistoryDao` schreibt per `INSERT OR IGNORE` plus abgesichertem `UPDATE` in einer Transaktion — SQLite
auf API 26 ist Version 3.18, `UPSERT` gibt es erst ab 3.24. `DtcHistoryRepository.record()` ist „best
effort" (Fehler landen im App-Log, nie in der Pipeline). Die Beschreibung wird zur Anzeige per
`dtcDescription(code)` aufgelöst (aus dem `DtcParser` herausgezogen), nicht gespeichert. Die README
behauptete „DTC-Lesen & Löschen"; die App hat nie im Fahrzeug gelöscht — korrigiert.

### Tests

936 Unit-Tests (+41) und 244 Instrumentation-Tests (+23), alle grün; Lint unverändert bei 0 Fehlern,
26 Warnungen; 0 Kotlin-Warnungen. Zwölf Mutationsprüfungen an der neuen Logik werden alle erkannt (eine
zeigte zunächst eine Testlücke, die geschlossen wurde). Nicht geprüft: ein echtes Fahrzeug (kein
bestromter Adapter) — das Aufzeichnen beim Verbinden ist nur mit Mocks getestet.

---

## Version 1.22.0 (versionCode 78)

### Verlauf: Löschen mit „Rückgängig", Wischen, laufende Fahrt geschützt

Fahrten im Verlauf lassen sich jetzt so löschen wie die App-Logs: Papierkorb **oder** Wischen nach links
löscht eine Fahrt ohne Rückfrage, eine Meldung „Fahrt gelöscht" bietet 10 Sekunden lang „Rückgängig" an.
Erst danach — oder sobald du die Seite verlässt — wird wirklich gelöscht. Neu ist „Alle löschen" in der
Kopfzeile: mit Rückfrage und ebenfalls mit „Rückgängig". Der Papierkorb bleibt immer sichtbar (56 dp,
handschuhtauglich), das Wischen ist nur ein Zusatz; es löst ab der halben Zeilenbreite oder bei
schnellem Schwung aus (Material-3-Verhalten, nicht einstellbar) — dafür gibt es das „Rückgängig".

Die Fahrt, die gerade aufgezeichnet wird, ist geschützt: Sie zeigt „Aufzeichnung läuft" statt des
Papierkorbs, lässt sich nicht wischen und bleibt bei „Alle löschen" erhalten. Bisher ließ sie sich
löschen — die nächste Speicherung scheiterte dann am Fremdschlüssel, und das Dashboard fiel auf „nicht
verbunden" zurück.

Gelöscht wird ausschließlich lokal — die App schreibt weiterhin nie ins Fahrzeug.

### Fahrten werden sauber beendet

Beim Trennen der Verbindung (und bei Slot-Wechsel oder App-Ende) lief der Abschluss der Fahrt in einer
bereits abgebrochenen Coroutine und wurde sofort abgewiesen: Die Fahrt bekam nie eine Endzeit, und „Nur
Fahrten mit DTCs speichern" konnte Fahrten ohne Fehlercodes in diesem Fall nicht verwerfen. Das stützt
sich auf Code-Analyse und einen Test, der vorher fehlschlug — im Feld habe ich es nicht beobachtet. Jetzt
läuft der Abschluss abbruchsicher durch; bereits gespeicherte Fahrten ändern sich nicht.

### Unter der Haube

`SwipeToDeleteRow` (Material3 `SwipeToDismissBox`, nur zum Zeilenende, Auslösung über `onDismiss`, nicht
über das deprecatete `confirmValueChange`), `HistoryViewModel` auf `UndoableDeletion` (die „Alle
löschen"-Menge ist der Schnappschuss der abgeschlossenen Fahrten beim Bestätigen — eine Fahrt, die im
Rückgängig-Fenster endet, wird nicht mitgerissen), `SessionRepository` mit `activeSessionId`,
`deleteSessions` (in Blöcken à 50 wegen SQLites 999-Variablen-Grenze auf API 26) und `discardSession`.

### Tests

895 Unit-Tests (+23) und 221 Instrumentation-Tests (+20), alle grün; Lint unverändert bei 0 Fehlern,
26 Warnungen; 0 Kotlin-Warnungen. Sieben Mutationsprüfungen an der Kernlogik werden alle erkannt. Zwei
ältere Tests waren von der Geräteausrichtung abhängig (quer liegendes Telefon) und sind korrigiert.

---

## Version 1.21.0 (versionCode 77)

### Einstellungen: eine gruppierte Seite — „Dashboard konfigurieren" ist aufgegangen

Die Einstellungen sind jetzt eine einzige Seite in fünf Gruppen: **Verbindung**, **Dashboard & Anzeige**,
**Aufzeichnung**, **Fahrzeug** und **Info & Wartung**. Die eigene Seite „Dashboard konfigurieren" (ein
Regler und ein Schalter) gibt es nicht mehr: Drehzahl-Blinkgrenze und der dynamische Modus stehen jetzt
in „Dashboard & Anzeige", direkt bei Farbschema, Einheiten und Ganganzeige.

Die Unterseiten (Getriebe, Aufgezeichnete PIDs, Fahrzeuginfo, App-Logs, App-Info) öffnen sich über Zeilen
mit Kurzbeschreibung und Pfeil statt über nackte Textknöpfe. Jeder Schalter lässt sich über die ganze
Zeile bedienen, nicht nur über den kleinen Schieber; jede Zeile ist mindestens 64 dp hoch. Auf allen
Unterseiten bleibt der Haupt-Tab im Menü markiert (Einstellungen bzw. DTC). Die Segmente der
Farbschema- und Helligkeitswahl waren nur 40 dp hoch und sind jetzt 56 dp (handschuhtauglich).

### Cockpit-Farben vollständiger

Die drei Cockpit-Farbschemata (Blau, Orange, Grün) definierten mehrere Farbrollen nicht, die
Material-3-Komponenten benutzen — dort erschien M3-Violett bzw. -Grau (gewähltes Segment, Menü-Indikator,
Dialog, „Rückgängig"-Meldung). Jetzt tragen sie die Töne des jeweiligen Schemas; Kontrast und Farbton sind
per Test abgesichert, auf dem Gerät in Orange, Blau und Hell geprüft. Noch nicht angepasst: die
Log-Stufen-Chips in den App-Logs und die feinen Linien zwischen den Dashboard-Kacheln — sie zeigen weiter
die M3-Standardfarben und folgen separat.

### Unter der Haube

`parentTab(route)` bestimmt für jede Unterseite den markierten Tab; `SettingsGroupHeader`,
`SettingsLinkRow` und `SettingsSwitchRow` ersetzen die wiederholten `ListItem`-Blöcke.
`RpmThresholdSection` ist nach `ui/settings/` umgezogen und wird vom `DisplaySettingsViewModel`
gespeist; `DashboardConfigScreen` und -`ViewModel` sind entfernt (samt der ungenutzten
`isConnected`-Eigenschaft). Die drei doppelten Zurück-Beschreibungen sind eine (`nav_back_desc`), die
App-Logs hatten bisher keine.

### Tests

872 Unit-Tests (+10) und 201 Instrumentation-Tests (+13), alle grün; Lint unverändert bei 0 Fehlern,
26 Warnungen.

---

## Version 1.20.0 (versionCode 76)

### App-Logs: Löschen mit Rückfrage und „Rückgängig"

Das Papierkorb-Symbol in den App-Logs löschte bisher sofort und ohne Rückfrage. Jetzt fragt zuerst ein
Dialog nach („Alle Logs löschen?"). Nach dem Bestätigen verschwinden die Einträge sofort, und eine
Meldung „Logs gelöscht" bietet 10 Sekunden lang „Rückgängig" an. Erst danach — oder sobald du die Seite
verlässt — wird wirklich gelöscht. Neue Log-Zeilen, die in dieser Zeit entstehen, bleiben erhalten. Das
Symbol ist jetzt 56 dp groß (handschuhtauglich) und ausgegraut, solange nichts zu löschen da ist; die
leere Seite zeigt ein Symbol statt nur Text.

Gelöscht wird dabei ausschließlich lokal — die App schreibt weiterhin nie ins Fahrzeug.

### Unter der Haube

Die App-Logs sind der erste Anwender eines gemeinsamen Lösch-Bausteins; Verlauf und Fehlerhistorie
folgen. `UndoableDeletion` besitzt die eine Uhr (ausblenden, nach dem Fenster endgültig löschen,
rückgängig machen; nur der jeweils letzte Löschvorgang ist rückgängig-bar), die Snackbar zeigt nur
seinen Zustand — so laufen Meldung und echtes Löschen nie auseinander. Dazu kommen
`ConfirmDeleteDialog`, `EmptyState` und der `@ApplicationScope`, die app-weite Coroutine-Scope, die die
Projektdoku bisher als vorhanden beschrieb, die es aber nie gab. Ein Test am echten Datenbankfall zeigte,
dass gelöschte Zeilen nach dem Löschen kurz zurückflackern könnten (Room meldet Änderungen asynchron);
der Baustein hält sie deshalb noch 300 ms verborgen.

### Tests

862 Unit-Tests (+24) und 188 Instrumentation-Tests (+18), alle grün; Lint unverändert bei 0 Fehlern,
26 Warnungen.

---

## Version 1.19.0 (versionCode 75)

### Verbindungs-Einstellungen als feste Auswahl

„Reconnect bei eingefrorenen Werten" und „Reconnect-Basisverzögerung" sind keine Zahlenfelder mehr,
sondern eine Auswahl aus festen Werten: 10 / 20 / 30 / 60 / 120 s bzw. 3 / 10 / 30 s. Die Segmente sind
56 dp hoch (handschuhtauglich), zeigen nur die Zahl und werden von Screenreadern als „20 Sekunden"
vorgelesen; bei großer Systemschrift bleiben die Zahlen auch auf schmalen Telefonen lesbar. Ein Vertippen
kann keinen unbrauchbaren Wert mehr erzeugen, und alle Optionen sind auf einen Blick sichtbar.

Unter der Basisverzögerung steht jetzt, was die Wahl bedeutet: die Wartezeiten vor den fünf
Reconnect-Versuchen, z. B. „Wartezeiten: 3 · 6 · 12 · 24 · 48 s (insgesamt 1:33 min)". Bei 30 s sind es
zusammen 15:30 min — das war aus dem alten Zahlenfeld nicht ablesbar.

Wichtig: Ein früher frei eingegebener Wert, der nicht in der Auswahl steht, gilt jetzt als der
nächstliegende Auswahlwert (bei Gleichstand der größere): 45 s wirkt als 60 s, 5 s als 3 s. Die Umdeutung
geschieht nur beim Lesen — gespeichert wird nichts um, eine Migration gibt es nicht. Wer die
Standardwerte (20 s und 3 s) nutzt, merkt keinen Unterschied.

### Tests

838 Unit-Tests (+18) und 170 Instrumentation-Tests (+10), alle grün; Lint unverändert bei 0 Fehlern,
26 Warnungen. Beim Bau haben die Tests zwei Fehler im ersten Entwurf gefunden — Segmente nur 40 statt
56 dp hoch, und „120" lief bei Systemschrift 1,3 auf einem 320-dp-Telefon über — beide vor dem Release
behoben.

---

## Version 1.18.1 (versionCode 74)

### Slot-Picker öffnet in voller Höhe

Der Slot-Picker (Long-Press auf eine Kachel) öffnet jetzt immer in voller Höhe. Das halbhohe
Standardverhalten zeigte im Querformat anfangs nur etwa 3 Zeilen, im Hochformat etwa 8 — der Rest war
erst nach Hochziehen des Sheets erreichbar. Jetzt sind im Querformat 6 und im Hochformat rund 15 Zeilen
sofort sichtbar (Pixel 8 Pro, volle PID-Liste). Der Preis: die Kachel hinter dem Sheet bleibt nicht mehr
sichtbar. Geschlossen wird wie bisher per Wegwischen nach unten oder Tippen neben das Sheet.

### Build-Wartung (ohne Auswirkung auf die App)

Alle Kotlin-Warnungen beseitigt (Build-Skript, Unit-Tests, Instrumentation-Tests): die Room-Schema-Assets
nutzen die AGP-9-Schreibweise, die Compose-Tests die aktuelle v2-Test-Rule, die bewusst verwendete
`requestSingleUpdate`-API ist im Test passend unterdrückt. Das Release-Artefakt ist davon inhaltlich
unverändert.

### Tests

820 Unit-Tests unverändert, 160 Instrumentation-Tests (+1: neuer Querformat-Test für das volle Sheet),
alle grün; Lint unverändert bei 0 Fehlern, 26 Warnungen.

---

## Version 1.18.0 (versionCode 73)

### Neu: Leerlauf als grünes „N"

Steht das Fahrzeug und läuft der Motor, zeigt die Ganganzeige neben dem Drehzahlbalken jetzt ein
grünes „N" statt des „–" — wie die Leerlauf-Kontrollleuchte am Motorrad. Erkannt wird das aus
Stillstand (unter 2 km/h, bevorzugt die Fahrzeuggeschwindigkeit, GPS als Ersatz) und laufendem Motor
(ab 500 U/min). Ein angezeigtes „N" bleibt bis 4 km/h stehen, damit es nicht flackert; beim Anfahren
wird daraus „–", ab 8 km/h wieder der Gang.

Wichtig: Das Motorrad meldet keinen Leerlaufschalter. „N" bedeutet deshalb „steht, Motor läuft", also
Leerlauf **oder** gezogene Kupplung (an der Ampel nicht zu unterscheiden). Leerlauf im Rollen wird
nicht erkannt. Ohne Verbindung bleibt die Anzeige „–".

Das Grün ist in allen Farbschemata das feste Grün von Bluetooth-Punkt und Öl-Balken. Auf den dunklen
Cockpit-Schemata ist es besonders kontrastreich, im hellen Standard-Schema ebenfalls klar lesbar.

### Tests

820 Unit-Tests (+22) und 159 Instrumentation-Tests (+4), alle grün; Lint unverändert bei 0 Fehlern,
26 Warnungen. Die neue Logik ist zusätzlich durch gezielte Fehlerinjektion abgesichert: sieben
absichtlich eingebaute Fehler wurden jeweils von den dafür vorgesehenen Tests erkannt.

---

## Version 1.17.10 (versionCode 72)

### Kleinere APK und Aufräumarbeiten

- Die Release-APK ist durch Ressourcen-Shrinking rund 0,5 MB kleiner (6,2 → 5,7 MB, −8 %). Entfernt
  werden nur ungenutzte Bibliotheks-Ressourcen, keine eigenen der App.
- Interne Aufräumarbeiten ohne sichtbare Änderung: eine Compiler-Warnung im Vordergrunddienst
  behoben, der Launcher-Icon-Ordner verzichtet auf den überflüssigen `-v26`-Qualifier (minSdk ist
  26), zwei Lint-Fehlalarme bei den Gang-Texten gezielt unterdrückt (Lint-Warnungen 30 → 26).

### Test-Infrastruktur

Der seit v1.17.8 ignorierte Instrumentationstest für den Slot-Picker läuft wieder. Die Ursache war
nicht das vermutete Popup-Fenster-Problem, sondern ein nur halb ausgeklapptes Bottom Sheet: Zeilen
unterhalb der halben Höhe waren zwar komponiert, lagen aber außerhalb des Bildschirms. Der Test
klappt das Sheet jetzt vor dem Scrollen auf.

### Tests

798 Unit-Tests unverändert (0 Fehler), 155 Instrumentation-Tests alle grün (zuvor 154 + 1
ignoriert), Lint 0 Fehler, 26 Warnungen (zuvor 30).

---

## Version 1.17.9 (versionCode 71)

### Code-Cleanup

Die Einstellungsfelder „OBD-Watchdog-Timeout" und „Reconnect-Basisverzögerung" waren jeweils mit
identischem Code für Zwischenspeicher, Validierung und Anzeige nachgebaut. Jetzt teilen sich beide
eine gemeinsame Komponente — keine sichtbare Änderung, nur intern weniger Duplikation.

### Tests

798 Unit-Tests unverändert (0 Fehler), 155 Instrumentation-Tests unverändert (154 grün + 1 bewusst
markiert, s. v1.17.8), Lint unverändert bei 0 Fehlern, 30 Warnungen.

---

## Version 1.17.8 (versionCode 70)

### Backlog-Cleanup: fünf kleine UI/Code-Punkte

- Fehlende `@Preview` für `RpmThresholdSection` (Dashboard-Konfiguration) ergänzt.
- Kachel-Taps (`LiveValueTile`) zeigen jetzt einen Ripple-Effekt in der Akzentfarbe der Kachel statt
  keiner sichtbaren Rückmeldung.
- Sparklines mit nahezu flachem Werteverlauf spreizen die Skala minimal, statt als einzelner Strich
  am Kachelrand zu kleben.
- Interner Code-Aufräum bei den drei Cockpit-Farbschemata (keine sichtbare Änderung).
- Neues Einstellungsfeld **„Reconnect-Basisverzögerung"**: die Wartezeit vor dem ersten
  Wiederverbindungsversuch ist jetzt einstellbar (Standard weiterhin 3 s, jeder weitere Versuch
  verdoppelt sie wie bisher).

### Test-Infrastruktur

Bei der Umsetzung eine eigene Regression gefunden und behoben: das neue Einstellungsfeld hatte zwei
darunterliegende Schalter aus dem sichtbaren Bereich verdrängt. Zusätzlich zwei vorbestehende,
von dieser Änderung unabhängige Testfehler untersucht (per Vergleich gegen den unveränderten
vorherigen Stand bestätigt) — einer davon behoben (fehlendes Scrollen zu einer Listenzeile
außerhalb des initial sichtbaren Bereichs), der zweite tiefergehend analysiert (ein Compose/
Material3-Testartefakt bei Bottom Sheets) und bewusst als bekannt markiert statt weiter verzögert.

### Tests

798 Unit- (+10) und 155 Instrumentation-Tests (154 grün + 1 bewusst markiert, s.o.), Lint
unverändert bei 0 Fehlern, 30 Warnungen.

---

## Version 1.17.7 (versionCode 69)

### Neues App-Icon

Das generische Tacho-Symbol auf Teal weicht einem eigenen Drehzahl-Bogen (Blau→Orange, Nadel,
Zentrums-Punkt) auf Navy — angelehnt an den echten RPM-Balken im Dashboard. Die Debug-Version
bleibt zur Unterscheidung bei Amber.

### Tests

788 Unit- und 155 Instrumentation-Tests unverändert grün, Lint unverändert bei 0 Fehlern,
30 Warnungen.

---

## Version 1.17.6 (versionCode 68)

### Bildschirm sperrte sich im Dashboard ohne Verbindung

Der Bildschirm blieb bisher nur an, solange eine aktive Verbindung zum OBD2-Adapter bestand — ohne
Verbindung griff nach der eingestellten Zeit die normale Bildschirmsperre, auch wenn das Dashboard
sichtbar war. Bleibt jetzt an, solange das Dashboard offen ist, unabhängig vom Verbindungsstatus.
Andere Screens (Einstellungen, Verlauf, DTCs) sperren weiterhin normal.

### Tests

788 Unit- und 155 Instrumentation-Tests grün, Lint unverändert bei 0 Fehlern, 30 Warnungen.

---

## Version 1.17.5 (versionCode 67)

### Neuer Beenden-Button im Hauptmenü

Rechts neben „Einstellungen" gibt es jetzt einen fünften Menüpunkt zum vollständigen Beenden der
App: Verbindung zum OBD2-Adapter trennen, Hintergrunddienst stoppen, App schließen — mit
Sicherheitsabfrage, damit ein versehentlicher Treffer mit Handschuhen auf holpriger Strecke nicht
gleich die App beendet.

### Tests

788 Unit- und 155 Instrumentation-Tests grün (vorher 786 / 152). Lint unverändert bei 0 Fehlern,
30 Warnungen.

---

## Version 1.17.4 (versionCode 66)

Zwei kleine Robustheits-Fixes, gefunden bei einem routinemäßigen Code-Sweep — kein Feldbug.

### Möglicher Absturz bei fehlendem Netzwerk-Standortanbieter

Auf Geräten ohne registrierten Netzwerk-Standortanbieter (z. B. ohne Google Play Services) konnte
die aktive Standortanfrage für die Außentemperatur eine unbehandelte Exception auslösen — bei
jedem 60-Sekunden-Poll, sobald die Ambient-Statuszeile sichtbar ist. Jetzt abgefangen, wie der
bereits behandelte Berechtigungsfall.

### Öl-Temperatur-Balken vergaß seinen Aufwärm-Fortschritt bei Activity-Neustart

Erzeugte das System die App-Activity neu, während Verbindung und Prozess weiterliefen (z. B. durch
aggressives Speicher-Management mancher Hersteller, oder die Entwickleroption "Nicht im
Hintergrund behalten"), setzte sich der Grün-Zähler des Öl-Temperatur-Balkens unbemerkt zurück.
Der Fortschritt übersteht das jetzt.

### Tests

786 Unit-Tests grün (vorher 784), Lint unverändert bei 0 Fehlern, 30 Warnungen.

---

## Version 1.17.3 (versionCode 65)

Schließt den letzten offenen Punkt aus dem OBD-Watchdog-Fix (v1.17.0): ein
StateFlow-Reentrancy-Risiko im Reconnect-Pfad.

### Reconnect-Trigger konnte unter seltenen Timing-Bedingungen verloren gehen

Beim Verbindungsabbruch setzt die App intern zuerst einen Status-Wert auf
"nicht verbunden" und meldet den Abbruch danach an die UI weiter, die daraufhin
den Reconnect anstößt. Auf manchen Geräten/Android-Versionen konnte dieser
Status-Wechsel die Meldung selbst noch abbrechen, bevor sie ankam — der
Reconnect-Versuch wäre dann stillschweigend ausgeblieben. Behoben durch einen
unabhängigen, gegen genau dieses Timing abgesicherten Signalweg.

784 JVM-Tests grün (unverändert in der Anzahl, ein neuer Test für genau diese
Race-Bedingung ersetzt keinen bestehenden), Lint unverändert 0 Fehler/30
Warnungen. Der Fix ist JVM-verifiziert; eine Verifikation gegen den realen
Bluetooth-Adapter bei einem echten Verbindungsabbruch steht noch aus (Adapter
war zum Zeitpunkt des Release nicht erreichbar).

---

## Version 1.17.2 (versionCode 64)

Zwei kleine, unabhängige UX-Anpassungen auf Nutzerwunsch.

### Kräftigeres Warngelb

Die Warnfarbe (Öl-Balken „wärmt sich auf", Kachel-Warnstatus, schwaches BLE-Signal, u. a.) ist jetzt
ein kräftiges Gelb (Tailwind Yellow 500, `#EAB308`) statt des vorherigen, etwas gedeckteren Amber
(`#F59E0B`) — besser lesbar bei Sonnenlicht.

### Volle Displayhelligkeit im Vordergrund

Die App setzt die Bildschirmhelligkeit jetzt automatisch auf 100 %, solange sie im Vordergrund ist,
und gibt beim Verlassen sofort wieder die System-Helligkeit frei — reines Fenster-Attribut, keine
zusätzliche Berechtigung nötig. Hilft bei direkter Sonneneinstrahlung auf dem Motorrad.

### Tests

783 Unit- und 152 Instrumentation-Tests grün (vorher 781 / 152). Lint unverändert bei 0 Fehlern,
30 Warnungen.

---

## Version 1.17.1 (versionCode 63)

Feldbug-Fix, einen Tag nach 1.17.0 gefunden: die neue Statuszeile mit Datum/Uhrzeit und
Außentemperatur zeigte auf echter Hardware nie eine Temperatur an, und der Text war beim Fahren
zu klein zum Lesen.

### Außentemperatur erschien nie

Ursache war `LocationManager.getLastKnownLocation()`: liefert ausschließlich einen bereits
gecachten Standort und fordert selbst nie aktiv einen neuen Fix an. Ohne eine andere App/Funktion,
die kürzlich einen Standort angefragt hatte (z. B. „Geschwindigkeit per GPS", standardmäßig aus),
blieb der Cache leer — die Berechtigung war erteilt und der Open-Meteo-Abruf funktionierte, nur
kam nie eine Koordinate an. Jetzt: Cache zuerst (unverändert schnell), bei leerem Cache je Provider
(GPS dann Netzwerk) ein aktiver `requestSingleUpdate()`-Fix mit 15-Sekunden-Timeout.

### Statuszeile beim Fahren unlesbar klein

Die Schriftgröße war bewusst auf die kleinste Textrolle im System gesetzt worden („sekundäre
Information, kein Live-Wert"). Auf echter Fahrt unlesbar. Jetzt deutlich größer (`titleLarge` statt
`labelSmall`, ca. 26sp statt 13sp effektiv) — weiterhin klar kleiner als die Sensor-Kacheln, aber
auf einen Blick lesbar.

### Tests

781 Unit- und 152 Instrumentation-Tests grün (vorher 778 / 152). Lint unverändert bei 0 Fehlern,
30 Warnungen.

---

## Version 1.17.0 (versionCode 62)

Bündelt drei fertige, unabhängig voneinander entwickelte Arbeitsblöcke: einen Zuverlässigkeits-Fix
und zwei neue Features.

### OBD-Watchdog: erzwungener Reconnect bei eingefrorenen Werten

Nach einer längeren Fahrt konnten die Live-Sensorwerte einfrieren, ohne dass die App das bemerkte
(GPS-Geschwindigkeit lief unbeeinflusst weiter, der Verbindungsstatus zeigte weiter „verbunden" —
nur ein Neustart half). Ursache war eine fehlende Eskalation bei wiederholten PID-Timeouts. Ein
neuer, in den Einstellungen konfigurierbarer Watchdog (Standard 20 s, mindestens 10 s) erzwingt
jetzt einen Reconnect, sobald der Adapter zu lange gar nicht oder nur unverwertbar antwortet; reines
NO DATA (z. B. Zündung aus) löst ihn nie aus.

### Simulierte Öltemperatur-Leiste

Ein schmaler Farbstreifen direkt unter der Drehzahl-Anzeige schätzt aus der Kühlmitteltemperatur
ab, ob das Motoröl wahrscheinlich noch kalt ist (Rot, ≤ 75 °C), sich aufwärmt (Gelb) oder
Betriebstemperatur erreicht hat (Grün: seit 10 Minuten ununterbrochen über 80 °C, mit Hysterese
gegen Thermostat-Pendeln zwischen 78–80 °C). Grau ohne Verbindung oder vor dem ersten Messwert.

### Datum, Uhrzeit und Außentemperatur während der Fahrt

Ist „Navigationsleiste beim Fahren ausblenden" an und das Fahrzeug in Bewegung, ersetzt eine
Statuszeile mit Datum/Uhrzeit und der Außentemperatur am aktuellen Standort das Hauptmenü — auf
jedem Screen, in exakt dessen Höhe. Temperaturquelle ist [Open-Meteo](https://open-meteo.com/)
(kostenlos, kein API-Key; Namensnennung und Lizenztext CC BY 4.0 je als eigener Link unter
App-Info), abgefragt einmal pro Minute und nur solange die Zeile sichtbar ist. Ein fehlgeschlagener
Abruf lässt den letzten Wert bis zu 30 Minuten stehen, statt die Anzeige sofort zu leeren.
Standortberechtigung (genau oder ungefähr) wird beim Einschalten des Schalters separat angefragt,
höchstens einmal pro Installation. Details: s. README, Abschnitt „Berechtigungen".

### Tests

778 Unit- und 152 Instrumentation-Tests grün (vorher 653 / 137).

---

## Version 1.16.15 (versionCode 61)

### Ganganzeige jetzt so groß wie die Kachel-Werte

Die Gang-Ziffer neben dem Drehzahlbalken ist jetzt optisch exakt genauso groß wie die
Sensorwerte in den Dashboard-Kacheln. Dafür wurde die Zeile des Drehzahlbalkens leicht höher
(72dp → 78dp) und die Ziffer-Box etwas breiter (32dp → 40dp).

### Tests

653 Unit- und 137 Instrumentation-Tests grün (unverändert).

---

## Version 1.16.14 (versionCode 60)

Bündelt alle Änderungen seit v1.16.13: ein sichtbarer UI-Fix plus zwei weitere interne
Test-Updates.

---

### Ganganzeige vergrößert

Die Gang-Ziffer neben dem Drehzahlbalken ist jetzt deutlich größer (45sp statt 40sp) — so groß wie
es die feste Anzeige-Box neben dem Balken zulässt, ohne den Balken selbst zu verändern.

### Weitere 29 Testabdeckungs-Lücken geschlossen

Nach v1.16.13 fand ein erneuter Vollsweep noch 6 komplett ungetestete und 23 teilgetestete
Dateien — alle einzeln geschlossen. Keine sichtbaren Verhaltensänderungen für Nutzer.

### Tests

653 Unit- und 137 Instrumentation-Tests grün (vorher 606 / 75).

---

## Version 1.16.13 (versionCode 59)

Wieder ein reines internes Test-Update — keine sichtbaren Änderungen für Nutzer.

---

### 24 Teillücken der Testabdeckung geschlossen

Nach dem letzten Sweep (v1.16.12) blieben 24 Dateien mit nur teilweiser Testabdeckung übrig —
alle einzeln, nacheinander bearbeitet: DTC-/PID-Parser-Fehlerfälle, Bluetooth-Verbindungsaufbau
(inkl. aller SecurityException-Pfade), Geräte-Scan, App-Logs, Einstellungen-Persistenz,
OBD-Repository-Lesepfade, CSV-Export, Standortberechtigungen, GPS-Geschwindigkeitsanbieter,
Drehzahlbalken-Schwellwertskalierung, Motortemperatur-Ableitung, PID-Auswahl-Logik,
Hintergrund-Standort-Eskalation, Fehlercode-Anzeige, Freeze-Frame-Anzeige, Lösch-Bestätigung im
Verlauf, Kachel-Statusfarben samt Long-Press (neues Testmuster für diese Geste), Sektions-Label
und der Vordergrunddienst-Fallback für ältere Android-Versionen.

### Tests

606 Unit- und 75 Instrumentation-Tests grün (vorher 483 / 59).

---

## Version 1.16.12 (versionCode 58)

Großes internes Test-Update — keine sichtbaren Änderungen für Nutzer, mit einer Ausnahme: einer
Doku-Korrektur, die kein Nutzerverhalten betrifft.

---

### Elf Testabdeckungs-Lücken geschlossen

Getriebe-Migration, Berechtigungsanfragen, Theme-Auswahl, Drehzahlbalken-Logik, Getriebe-
Einstellungen, Fahrt-Historie, Fahrzeug-Info, Logging-Konfiguration, Dashboard-Konfiguration,
App-Logs und die Theme-Auswahl haben jetzt automatisierte Tests. Dabei wurde ein echter Fehler im
Testaufbau selbst gefunden und behoben (ein zu alter Testdaten-Zeitstempel, der von der eigenen
90-Tage-Aufräumlogik sofort wieder gelöscht wurde) — kein Fehler in der App selbst.

### Doku-Korrektur: Dark Mode

„Dark Mode ist die einzige Realität" galt schon immer nur für die drei Cockpit-Farbschemata
(Blau/Amber/Grün) — das Default-Farbschema bietet weiterhin bewusst Hell/Dunkel/System zur Wahl.

### Tests

483 Unit- und 59 Instrumentation-Tests grün.

---

## Version 1.16.11 (versionCode 57)

Internes Test-Update — keine sichtbaren Änderungen für Nutzer.

---

### Testabdeckung für den Verbindungs-Dot ergänzt

Die Farblogik des Verbindungs-Status-Punkts ist jetzt vollständig durch Tests abgesichert.

### Tests

432 Unit- und 42 Instrumentation-Tests grün.

---

## Version 1.16.10 (versionCode 56)

Feinjustierung: Verbindungs-Dot sitzt jetzt direkt am Bluetooth-Symbol.

---

### Tests

426 Unit- und 42 Instrumentation-Tests grün.

---

## Version 1.16.9 (versionCode 55)

Feinjustierung: Verbindungs-Dot sitzt jetzt noch näher am Bluetooth-Symbol.

---

### Tests

426 Unit- und 42 Instrumentation-Tests grün.

---

## Version 1.16.8 (versionCode 54)

Verbindungs-Status-Punkt: klassische Ampelfarben und etwas mehr Abstand zur Ganganzeige.

---

### Verbindungs-Dot in Rot/Gelb/Grün

Der Punkt neben dem Bluetooth-Symbol zeigt den Verbindungsstatus jetzt in klassischer
Ampel-Farbgebung: Grün für verbunden, Gelb für „verbindet", Rot für einen Fehler. Der Punkt sitzt
außerdem etwas weiter von der Gang-Ziffer und näher am Bluetooth-Symbol.

### Tests

426 Unit- und 42 Instrumentation-Tests grün.

---

## Version 1.16.7 (versionCode 53)

Ganganzeige: weniger Wegspringen auf „–" beim Fahren, basierend auf Rückmeldung aus einer
Testfahrt.

---

### Ganganzeige hält den letzten Gang bei kurzen Aussetzern

Kupplung ziehen oder das Gas wegnehmen entkoppelt kurz die Motordrehzahl von der
Geschwindigkeit — die Ganganzeige sprang dabei bisher sofort auf „–" und musste sich danach
langsamer neu einpendeln, was sich auch beim Anfahren als Verzögerung bemerkbar machte. Die
Anzeige hält jetzt den zuletzt erkannten Gang, solange das Fahrzeug erkennbar weiterrollt, und
zeigt „–" erst wieder bei echtem Stillstand oder fehlendem GPS-Signal.

### Tests

422 Unit- und 42 Instrumentation-Tests grün.

---

## Version 1.16.6 (versionCode 52)

Fehlerbehebung: Die Ganganzeige nutzte bislang teilweise die Fahrzeug-Geschwindigkeit statt GPS.

---

### Ganganzeige nutzt jetzt ausschließlich GPS-Geschwindigkeit

Meldete das Fahrzeug selbst eine Geschwindigkeit über OBD2, hatte diese bisher Vorrang vor der
GPS-Geschwindigkeit — auch für die virtuelle Ganganzeige. Das widersprach der ursprünglichen
Design-Entscheidung: Die Ganganzeige soll ausschließlich auf GPS beruhen, da ihre
Toleranzschwellen (u. a. die Mindestgeschwindigkeit) auf die Eigenheiten von GPS-Messungen
abgestimmt sind. Die Ganganzeige verwendet jetzt in jedem Fall die GPS-Geschwindigkeit; liegt kein
GPS-Fix vor, bleibt der Gang „–“, unabhängig davon, ob das Fahrzeug selbst eine Geschwindigkeit
meldet. Die normale Geschwindigkeitsanzeige und Aufzeichnung sind von dieser Änderung nicht
betroffen — dort hat die Fahrzeug-Geschwindigkeit weiterhin bewusst Vorrang.

### Tests

414 Unit- und 42 Instrumentation-Tests grün.

---

## Version 1.16.5 (versionCode 51)

Robustheits- und Barrierefreiheits-Verbesserungen an der Ganganzeige und den Getriebe-Einstellungen.

---

### Warnung bei zu eng beieinanderliegenden Gangübersetzungen

Die Getriebe-Einstellungen zeigen jetzt einen Hinweis, wenn zwei eingetragene Gänge so nah
beieinander liegen, dass die Ganganzeige nicht mehr zuverlässig zwischen ihnen unterscheiden kann.
Die Werte bleiben trotzdem speicherbar — der Hinweis ist informativ, keine Sperre.

### Ganganzeige: Barrierefreiheit und Robustheit

Die Gang-Ziffer neben dem Drehzahlbalken hat jetzt eine Sprachausgabe-Beschreibung für TalkBack und
bleibt bei aktivierter Bedienungshilfe „Große Schrift" lesbar, statt in ihrer Box abgeschnitten zu
werden.

### Kleinere Robustheits-Fixes

Die Getriebe-Einstellungen lehnen jetzt auch „Infinity“ und „NaN“ als Eingabe ab. Ein
fehlgeschlagenes Speichern einer Getriebe-Einstellung stürzt die App nicht mehr ab, sondern wird
protokolliert.

### Tests

412 Unit- und 42 Instrumentation-Tests grün.

---

## Version 1.16.4 (versionCode 50)

Internes Code-Cleanup — keine sichtbaren Änderungen für Nutzer.

---

### Ungenutzte Composables entfernt, Lint-Funde behoben

`ActionButton` und `HairlineDivider` waren seit Monaten ohne eine einzige Verwendung im Code und
wurden entfernt. `SectionLabel` hatte ebenfalls keine Verwendung, ersetzt jetzt aber den
1:1-duplizierten Überschriften-Text über der Dashboard-Vorschau. Außerdem zwei
Lint-Hinweise (`ModifierParameter`) behoben, die eine falsche Reihenfolge von Compose-Parametern
bemängelten.

### Tests

406 Unit- und 42 Instrumentation-Tests grün.

---

## Version 1.16.3 (versionCode 49)

Testabdeckung und Vorschau-Bilder für die Getriebe-Einstellungen ergänzt — keine sichtbaren
Änderungen für Nutzer.

---

### Grenzwert-Tests für die Ganganzeige

Drei neue Tests für `determineGear()` decken die exakten Schwellwerte ab: die 8,0-km/h-Mindest-
geschwindigkeit sowie die 4%- und 6%-Toleranzgrenzen beim Ein- und Ausrasten eines Gangs.

### `@Preview` für die Getriebe-Einstellungen

Das Eingabefeld für Radumfang/Übersetzungen hat jetzt eine Vorschau für den gültigen und den
fehlerhaften Eingabezustand.

### Tests

406 Unit- und 42 Instrumentation-Tests grün.

---

## Version 1.16.2 (versionCode 48)

Ein verlorenes GPS-Signal zeigt nicht mehr dauerhaft die letzte bekannte Geschwindigkeit.

---

### GPS-Fix verfällt nach 2 Sekunden ohne Update

Verlor das Gerät den GPS-Empfang — etwa im Tunnel oder in der Tiefgarage — blieb die zuletzt
gemessene Geschwindigkeit unbegrenzt lange stehen, weil das Betriebssystem in diesem Fall weder
einen neuen Fix noch ein „Anbieter deaktiviert"-Signal liefert. Zusammen mit der virtuellen
Ganganzeige aus 1.16.0 konnte das im Stand einen falschen, aber überzeugend wirkenden Gang zeigen.
Jeder echte GPS-Fix startet jetzt einen 2-Sekunden-Timer neu; bleibt der nächste Fix aus, gilt die
Geschwindigkeit als unbekannt — genau wie bei fehlendem oder unplausiblem Signal.

### Tests

403 Unit- und 42 Instrumentation-Tests grün.

---

## Version 1.16.1 (versionCode 47)

Der Verbindungs-Status-Punkt zeigt jetzt auch einen eigenen Fehler-Zustand.

---

### StatusDot mit Failed-Zustand

Bisher kannte der pulsierende Status-Punkt neben dem Bluetooth-Symbol nur „verbunden" (grün
getönt) und „verbindet neu" (pulsierend). Ist die Verbindung endgültig gescheitert — kein Gerät
konfiguriert, Bluetooth deaktiviert, oder alle Wiederverbindungs-Versuche erschöpft — sah das
optisch identisch zu „noch nie verbunden" aus: kein Punkt, nur das nackte Bluetooth-Symbol. Jetzt
erscheint in diesem Fall ein roter Punkt, sodass ein stiller Verbindungsabbruch während der Fahrt
nicht mehr unbemerkt bleiben kann.

### Tests

401 Unit- und 42 Instrumentation-Tests grün.

---

## Version 1.16.0 (versionCode 46)

Neu: eine virtuelle Ganganzeige neben dem Drehzahlbalken.

---

### Virtuelle Ganganzeige

Das bisherige „rpm"-Textfeld rechts neben dem großen Drehzahl-Segmentbalken zeigt jetzt den
aktuell eingelegten Gang (1–6, oder „–" wenn nicht eindeutig bestimmbar) — errechnet aus
Motordrehzahl und GPS-Geschwindigkeit. Radumfang, Primär-/Sekundärübersetzung und die sechs
Getriebestufen sind in einem neuen Settings-Bereich „Getriebe" hinterlegt und editierbar (Defaults
passend zu einem 150/70-18-Hinterreifen und einem typischen 6-Gang-Motorradgetriebe).

Damit die Anzeige nicht an nah beieinanderliegenden Übersetzungen flackert, rastet ein Gang erst
bei deutlicher Übereinstimmung ein, bleibt danach aber stehen, solange er selbst noch plausibel
ist — unabhängig davon, ob ein Nachbar-Gang gerade knapp näher läge. Unterhalb von 8 km/h zeigt
die Anzeige bewusst „–", da GPS-Geschwindigkeit dort zu ungenau ist.

### Tests

397 Unit- und 42 Instrumentation-Tests grün.

---

## Version 1.15.2 (versionCode 45)

Die GPS-Geschwindigkeit aus 1.15.1 aktualisiert die Anzeige jetzt wirklich alle 200 ms — nicht
erst beim naechsten OBD-Zyklus.

---

### GPS-Wert wurde erst beim naechsten OBD-Zyklus sichtbar

Das gesenkte GPS-Intervall aus 1.15.1 (200 ms statt 1000 ms) kam bei der Anzeige nicht wie
erwartet an: `ReadingsPipeline` las die GPS-Geschwindigkeit nur als Momentaufnahme (`.value`)
innerhalb der OBD-Abfrage-Kette — ein neuer GPS-Fix zwischen zwei OBD-Zyklen (500 ms+) wurde
schlicht uebersprungen, bis der naechste OBD-Zyklus lief.

Die Aufzeichnung und das Sitzungsende bleiben unveraendert exklusiv am OBD-Takt haengen; ein
zweiter, unabhaengiger Strom aktualisiert nur die Anzeige, sobald ein neuer GPS-Fix eintrifft.
Ein GPS-Update loest dadurch nie zusaetzliche Aufzeichnungs-Zeilen aus und veraendert das
Sitzungsverhalten (Start, Ende, Fehlerbehandlung) nicht.

### Tests

368 Unit- und 40 Instrumentation-Tests gruen. Lint unveraendert bei 29 Warnungen.

---

## Version 1.15.1 (versionCode 44)

Drei Feinschliff-Fixes am Dashboard: schnellere GPS-Geschwindigkeit, ruhigere Sparklines,
mehr genutzter Platz im Querformat.

---

### GPS-Geschwindigkeit reagiert schneller

Die Geschwindigkeitsanzeige lief spürbar der echten Tachoanzeige hinterher. Ursache:
`AndroidLocationSpeedProvider` forderte Standort-Updates nur alle 1000 ms an. Das Intervall ist
jetzt 200 ms — die Anzeige folgt Beschleunigen/Bremsen deutlich direkter.

### Sparklines geglättet

Die Trendlinien in den Dashboard-Kacheln, besonders bei der Bordspannung, wirkten unruhig und
kaum lesbar. `Sparkline` glättet die Punkte jetzt vor der Darstellung über einen zentrierten
gleitenden Mittelwert (Fenstergröße 3, an den Rändern mit weniger Nachbarn). Die Skalierung
(Min/Max) rechnet auf den geglätteten Werten, nicht auf den Rohwerten.

### Mehr Platz im Querformat genutzt

Im Querformat blieb unterhalb der Sensor-Kacheln viel ungenutzter Raum, besonders bei
ausgeblendetem Hauptmenü während der Fahrt. Die Kachelhöhe im Querformat ist jetzt 200 dp statt
160 dp; auf kürzeren Bildschirmen schrumpft die Reihe weiterhin auf die verfügbare Höhe, genau
wie zuvor bei 160 dp.

### Tests

367 Unit- und 40 Instrumentation-Tests grün. Lint unverändert bei 29 Warnungen.

---

## Version 1.15.0 (versionCode 43)

Zwei Ergänzungen: das Dashboard zeigt jetzt einen pulsierenden Status-Punkt während des
Wiederverbindens, und die GPS-Geschwindigkeit läuft mit erteilter Berechtigung auch weiter,
wenn die App im Hintergrund ist.

---

### Connecting-Status auf dem Dashboard

`DashboardUiState.isReconnecting` wurde bisher berechnet, aber nirgends angezeigt — der
Status-Punkt neben dem Bluetooth-Symbol zeigte nur „verbunden" oder gar nichts. Jetzt pulsiert
er, solange die App eine gespeicherte Verbindung neu aufbaut (Kaltstart-Autoconnect oder die
Wiederverbindungs-Schleife nach einem Verbindungsabbruch) — das Symbol bleibt währenddessen
antippbar, man kann jederzeit zu den Einstellungen springen.

### GPS-Geschwindigkeit im Hintergrund

Bisher lieferte GPS nur Geschwindigkeit, solange das Dashboard sichtbar war — Android liefert
Standort-Updates an eine Komponente ohne sichtbare Activity nur, wenn
`ACCESS_BACKGROUND_LOCATION` erteilt ist und ein laufender Foreground Service den Typ `location`
anfordert. Beides ist jetzt der Fall: Der bestehende Foreground Service fordert den
zusätzlichen Typ an, wenn die Berechtigung vorliegt, und die Einstellungen eskalieren nach der
Fine/Coarse-Freigabe automatisch zur Hintergrund-Berechtigung (ein zweiter System-Dialog, den
Android getrennt von der ersten Anfrage verlangt). Lehnt der Nutzer ab, bleibt GPS-Geschwindigkeit
im Vordergrund weiter nutzbar; das automatische Nachfragen passiert nur einmal, nicht bei jedem
Besuch der Einstellungen.

Das galt anfangs nicht für jeden Verbindungsweg: Der manuelle „Verbinden"-Tap in den
Einstellungen startete den Foreground Service über eine eigene Codestelle, die den neuen
Berechtigungs-Typ zunächst nicht kannte — GPS im Hintergrund hätte für die meisten Verbindungen
gar nicht gegriffen. Beide Codestellen fragen jetzt dieselbe Entscheidung ab.

### Tests

361 Unit- und 40 Instrumentation-Tests grün. Lint unverändert bei 29 Warnungen.

---

## Version 1.14.1 (versionCode 42)

Die Freeze-Frame-Ansicht zeigt jetzt lokalisierte PID-Namen statt der englischen Bezeichner.

---

### Der Fehler

Seit 1.9.0 galt: `PidDefinition.name` ist der stabile, sprachunabhängige Bezeichner (Sortierung,
Logs), angezeigt wird ausschließlich `labelRes`. `ObdRepository.readFreezeFrame()` hielt sich
nicht daran — die Ergebnis-Map war nach `pid.name` geschlüsselt, und `FreezeFrameScreen` hat
diesen Schlüssel direkt als Text gerendert. Auf einem deutschen Gerät stand dort „Coolant Temp"
statt „Kühlmitteltemperatur", während der Rest der App übersetzt war.

Die Map ist jetzt nach `pid.id` geschlüsselt (z. B. „0105" statt „Coolant Temp"); die Auflösung
zum lokalisierten Label passiert über `PidRegistry` erst in der Composable — genau das Muster,
das `SensorCard` im Dashboard bereits für Live-Werte verwendet.

### Tests

Ein neuer `FreezeFrameScreenTest` deckt den Screen erstmals über einen Compose-UI-Test ab (gab
es vorher nicht). 346 Unit- und 38 Instrumentation-Tests grün, Lint unverändert bei 29 Warnungen.

---

## Version 1.14.0 (versionCode 41)

`targetSdk` von 35 auf 36. Die App erklärt sich damit für Android 16 zuständig.

---

### Was dabei zu prüfen war

Ein `targetSdk`-Sprung ist keine Zahl, sondern eine Zusage: Das System schaltet für die App alle
Verhaltensänderungen der neuen Version scharf. Vier davon betreffen diese App überhaupt.

**Edge-to-edge ohne Ausweg.** Android 16 entfernt das Opt-out `windowOptOutEdgeToEdgeEnforcement`.
Für diese App folgenlos: Die Durchsetzung gilt seit `targetSdk` 35, das Opt-out wurde nie
verwendet, und die Bildschirme kompensieren bereits über `statusBarsPadding()` im Dashboard und
`navigationBarsPadding()` in der unteren Navigation.

**Orientierung und Größenanpassung auf großen Bildschirmen.** Ab 600 dp ignoriert Android 16
`screenOrientation` und `resizableActivity` im Manifest. Die `MainActivity` deklariert beides
nicht und läuft ohnehin in beiden Orientierungen — keine Änderung.

**Predictive Back.** Für Apps mit `targetSdk` 36 sind die Systemanimationen des vorhergesagten
Zurück-Gestus standardmäßig aktiv. Die App führt keine eigene Zurück-Behandlung: Kein
`BackHandler`, kein `onBackPressed`, alle Rückwege laufen über `navController.popBackStack()`.
Damit greift das, was Compose Navigation mitbringt.

**Job-Kontingente und Broadcast-Reihenfolge.** Beides ohne Berührungspunkt — die App nutzt einen
Foreground Service, keinen `JobScheduler`, und versendet keine geordneten Broadcasts.

Lint meldet damit eine Warnung weniger: `OldTargetApi` ist erledigt.

### Was bewusst nicht Teil dieses Release ist

`MainActivity` ruft weiterhin kein `enableEdgeToEdge()`. Das wäre die ausdrückliche Fassung dessen,
was seit 1.8.0 ohnehin gilt, würde aber zugleich die Farbgebung der Systemleisten-Symbole ändern.
Eine sichtbare Änderung gehört nicht in einen Release, dessen Zweck die Prüfung von
Verhaltensänderungen ist.

---

## Version 1.13.0 (versionCode 40)

Die letzte ungetestete Stelle des GPS-Pfads bekommt eine Naht. An der App ändert sich nichts
Sichtbares.

---

### Warum das nötig war

`GpsSpeedSource` hing direkt am `LocationManager` des Frameworks, und der existiert im JVM-Test
nicht. Damit war ausgerechnet die Klasse ungeprüft, die den Wert überhaupt erst beschafft — in
einem Feature, das schon einmal zwei Releases lang tot war, ohne dass es jemand bemerkt hat.

### Die Naht

`LocationSpeedProvider` ist eine Schnittstelle mit zwei Methoden: „darf ich den Standort lesen"
und „melde mir Geschwindigkeiten in m/s, hier ist die Abmeldefunktion". `AndroidLocationSpeedProvider`
ist die einzige Klasse der App, die den `LocationManager` noch kennt — sie rechnet nicht, filtert
nicht und hält keinen Zustand. Was sich nicht prüfen lässt, enthält damit auch nichts zu Prüfendes.

Dieselbe Trennung leistet `ObdConnection` seit jeher für Bluetooth; die GPS-Seite zieht jetzt nach.

Bewusst in Metern pro Sekunde: Das ist die Einheit des Frameworks. Die Umrechnung in km/h ist
Logik und gehört damit in die getestete Klasse, nicht in die untestbare.

### Sechs neue Tests

Berechtigung fehlt, Registrierung scheitert, Umrechnung von 10 m/s auf 36 km/h, ein Fix ohne
Geschwindigkeit, unplausible Werte, und — der wichtigste — das Abmelden beim Ende der Sammlung.
Bliebe der Empfänger registriert, liefe der GPS-Empfänger weiter, nachdem das Dashboard längst
geschlossen ist.

Nebenbei enger geworden: Negative, unendliche und NaN-Werte werden jetzt an der Quelle zu `null`,
statt weiter oben aussortiert zu werden.

### Sonstiges

- 346 statt 340 Unit-Tests.

---

## Version 1.12.0 (versionCode 39)

Die Vorschau sagt jetzt, dass sie eine ist.

---

### Kacheln ohne Verbindung logen

Ohne OBD-Verbindung zeigte das Dashboard die Slot-Belegung mit dem Wert 0 — optisch nicht von
echten Messwerten zu unterscheiden. Genau diese Verwechslung stand am Anfang der Fehlersuche nach
der GPS-Geschwindigkeit: Der Eindruck, die App zeige ohne Verbindung eine Geschwindigkeit und
verliere sie beim Verbinden, kam von der Vorschau, nicht vom Fahrzeug.

Die Vorschau bleibt — sie zeigt die Belegung und macht das Layout begreifbar, bevor überhaupt
etwas verbunden ist. Aber sie gibt sich jetzt zu erkennen:

- Statt einer Zahl steht in jeder Kachel `--`, wie es die Drehzahlanzeige seit 1.9.1 schon tut.
- Die Akzentleiste ist gedämpft statt status-farbig. Ein grüner oder roter Balken über einem Wert,
  den kein Fahrzeug geliefert hat, wäre eine Aussage ohne Grundlage.
- Verlaufslinien bleiben aus, denn es gibt keinen Verlauf.
- Über dem Kachelraster steht ein schmales Band: „Vorschau · nicht verbunden".

Der Drehzahlbalken animiert weiterhin einmal beim Öffnen durch — das Band erklärt jetzt, was man
dabei sieht.

### Sonstiges

- Zwei neue Instrumentation-Tests (37 statt 35) für das Band und die `--`-Kacheln.

---

## Version 1.11.0 (versionCode 38)

Aufräumen unter der Haube. An der App ändert sich nichts Sichtbares.

---

### `DashboardViewModel` trug sieben Zuständigkeiten

427 Zeilen: Sitzungsschleife, Wiederverbindung mit wachsenden Wartezeiten, Auto-Connect beim
Start, GPS-Zusammenführung, Sparkline-Puffer, Steuerung des Foreground Service,
Sitzungsaufzeichnung. Die zugehörige Testdatei war mit über 1150 Zeilen fast ein Viertel des
gesamten Testcodes.

Wie teuer das ist, hat sich messbar gezeigt: Eine einzige geänderte Zeile im Messwert-Pfad — die
Fahrterkennung läuft seit 1.10.0 bei jedem Zyklus statt nur bei vorhandener Geschwindigkeit —
liess 18 Tests fallen, von denen keiner die Verbindung betraf. Der Produktivcode war korrekt; die
Tests hingen schlicht alle am selben Objekt.

### Zwei herausgelöste Kollaborateure

`ConnectionCoordinator` hält die Verbindung: Auto-Connect, Wiederverbindung mit den fünf
Wartestufen, Start und Stopp des Foreground Service, Protokollierung. Er meldet dem ViewModel
Ereignisse — `Attempting`, `Connected`, `Failed` — statt selbst in einen Zustand zu schreiben. Der
Rückweg ist bewusst ein Callback und kein Flow: Ein SharedFlow ohne Replay verwirft Ereignisse,
die vor dem ersten Sammler entstehen, und der Auto-Connect feuert sofort.

`ReadingsPipeline` hält den Messwert-Pfad: Auswahl der abzufragenden PIDs, Aufzeichnung der
Sitzung, Zusammenführung der Geschwindigkeitsquellen, Fahrterkennung. Auch sie meldet Ereignisse —
`Values`, `Idle`, `ConnectionLost`, `Failed`.

Geblieben ist im ViewModel, was Darstellung ist: Kachelbelegung, Sparkline-Puffer, Schwellwerte
und die Übersetzung beider Ereignisströme in einen Zustand. Aus 427 Zeilen wurden 213.

Keiner der beiden Teile besitzt einen eigenen Coroutine-Scope. Beide bekommen den des ViewModels
übergeben und sterben mit ihm — ein eigener Scope wäre eine zweite Lebensdauer, die jemand
aufräumen müsste.

### Was das für Tests bedeutet

Vierzehn neue Tests, und zwar solche, die vorher nicht möglich waren: `ConnectionCoordinatorTest`
braucht weder SessionRepository noch CrashReporter noch GPS, `ReadingsPipelineTest` weder
BluetoothAdapter noch Context noch Foreground Service. Eine Änderung am einen Pfad kann die Tests
des anderen nicht mehr umwerfen.

Die bestehenden ViewModel-Tests bleiben unverändert bestehen und prüfen weiterhin das
Zusammenspiel — sie bauen die Kollaborateure aus denselben Mocks zusammen, die sie schon vorher
übergeben haben.

### Ein Fund, den erst die Trennung sichtbar machte

Der Auslöser für die Wiederverbindung war ein `MutableSharedFlow` ohne Replay. Ein solcher Flow
verwirft, was emittiert wird, bevor der erste Sammler bereitsteht — und Auto-Connect und
Wiederverbindungs-Schleife werden in denselben Scope gestartet, ohne garantierte Reihenfolge.
Scheiterte der Auto-Connect, bevor die Schleife zu sammeln begann, ging die Aufforderung verloren
und die App blieb stumm getrennt, ohne einen einzigen Versuch.

Die Konstruktion stammt aus der Zeit vor dem Umbau; kein Test hatte sie je angefasst, weil sie im
ViewModel nicht isoliert erreichbar war. `replay = 1` schließt die Lücke. Es gibt genau einen
Sammler pro Instanz, also entsteht auch keine verspätete Doppelzustellung.

### Eine kleine Verhaltensänderung

Bei erfolgreichem Auto-Connect endete die Wiederverbindungs-Anzeige bisher erst mit dem ersten
Messwert, bei erfolgreicher Wiederverbindung dagegen sofort. Jetzt endet sie in beiden Fällen
sofort — die Verbindung steht ja.

### Sonstiges

- 340 statt 326 Unit-Tests.

---

## Version 1.10.2 (versionCode 37)

Die GPS-Geschwindigkeit aus 1.10.0 kam nie im Dashboard an. Dieses Release repariert das.

---

### Der Fehler

`gpsSpeedKmh` war ein `StateFlow`, den niemand gesammelt hat: Der Wert wurde ausschliesslich
ueber `.value` gelesen, und ein mit `SharingStarted.WhileSubscribed` geteilter Flow startet
seinen Upstream erst, wenn sich ein Sammler anmeldet. Ohne Sammler blieb `.value` fuer immer auf
dem Initialwert `null` stehen — `mergeGpsSpeed` bekam also nie eine Geschwindigkeit zu sehen und
gab die Messwerte unveraendert zurueck. Das Feature war damit von Anfang an tot, ohne dass ein
Test es bemerkt hat: Die vorhandenen Tests pruefen `mergeGpsSpeed` als reine Funktion, nicht den
Weg vom Empfaenger bis in die Oberflaeche.

Der Flow wird jetzt mit `SharingStarted.Eagerly` geteilt und ist damit tatsaechlich heiss. Damit
der GPS-Empfaenger trotzdem nur laeuft, solange der Schalter an ist, haengt ein `flatMapLatest`
auf der Praeferenz davor: Ist das Feature aus, wird `GpsSpeedSource.speedKmh` gar nicht erst
gesammelt und registriert keine Standort-Updates.

Zwei neue Tests decken jetzt den ganzen Weg ab — vom GPS-Wert bis in `uiState.readings`, und die
Gegenprobe mit ausgeschaltetem Schalter.

### Geschwindigkeit bleibt waehlbar

Sobald die PID-Discovery lief, filterte der Sensor-Picker auf die gemeldeten PIDs — die
Geschwindigkeit verschwand damit ausgerechnet bei den Fahrzeugen aus der Auswahl, fuer die das
GPS-Feature gebaut wurde. Bei eingeschaltetem GPS bleibt sie jetzt in der Liste.

### Ehrlicherer Platzhalter

Eine leere Geschwindigkeits-Kachel meldete „Vom Fahrzeug nicht unterstuetzt", auch wenn das GPS
eingeschaltet war und lediglich noch kein Fix vorlag. Dort steht jetzt „Warte auf GPS-Signal" —
eine Aussage ueber das Fahrzeug waere in diesem Zustand schlicht falsch.

### Hinweis zur Anzeige ohne Verbindung

Ohne OBD-Verbindung zeigt das Dashboard weiterhin Demo-Kacheln mit dem Wert 0, darunter eine fuer
die Geschwindigkeit. Das ist kein GPS-Wert: Die Zusammenfuehrung haengt am Messwert-Strom der
Verbindung und laeuft erst, wenn diese steht. Eine GPS-Anzeige auch ohne Fahrzeugverbindung waere
ein eigenes Feature.

### Sonstiges

- Vier neue Unit-Tests (326 statt 322).

---

## Version 1.10.1 (versionCode 36)

Ein Nachzügler aus dem Lint-Lauf gegen 1.10.0.

---

### Themed Icons

Den Adaptive Icons fehlte die `<monochrome>`-Ebene. Auf Android 13 und neuer färbt das System
App-Icons nach der Wallpaper-Palette ein, wenn der Nutzer themed icons aktiviert hat — Apps
ohne diese Ebene bleiben dann als bunte Fremdkörper zwischen lauter getönten Icons stehen.
`ic_launcher` und `ic_launcher_round` führen die Ebene jetzt, und zwar mit derselben Zeichnung
wie der Vordergrund: Das Symbol ist bereits einfarbig weiß, das System legt seine Tönung
darüber. Die Silhouette bleibt damit exakt die gewohnte.

Eine Einschränkung, die in der Natur der Sache liegt: Themed Icons verwerfen die
Hintergrundebene. Die farbliche Unterscheidung von Debug (amber) und Release (blaugrün) aus
1.8.1 greift dort also nicht — bei aktivierten themed icons sehen beide Installationen gleich
aus und sind nur am Namenszusatz „Debug" zu unterscheiden.

### Sonstiges

- Lint meldet damit 28 statt 30 Warnungen, weiterhin 0 Fehler.

---

## Version 1.10.0 (versionCode 35)

Geschwindigkeit per GPS, und ehrlichere Kacheln.

---

### Geschwindigkeit aus dem GPS

Viele Fahrzeuge liefern PID 010D nicht — bei Motorrädern ist das eher die Regel als die
Ausnahme, weil die Geschwindigkeit dort vom ABS-Steuergerät oder dem Kombiinstrument kommt und
gar nicht auf dem OBD2-Bus liegt. Ohne sie bleibt nicht nur die Kachel leer: Auch die
Fahrterkennung und damit das Ausblenden der Navigation während der Fahrt hängen daran.

- Neue `GpsSpeedSource` liest die Geschwindigkeit aus dem GPS-Fix (Framework-`LocationManager`,
  keine Play-Services-Abhängigkeit) und meldet sie sekündlich.
- Der Wert vom Fahrzeug hat immer Vorrang; GPS springt nur ein, wenn keiner ankommt oder er
  unbrauchbar ist. Die Kachel zeigt dann „GESCHW. (GPS)", die Quelle bleibt also sichtbar.
- Einzuschalten unter Einstellungen → Anzeige. Die Standortberechtigung wird **erst beim
  Einschalten** angefragt, nicht beim Start der App — wer die Funktion nicht braucht, wird nie
  danach gefragt.
- Die Fahrterkennung nutzt beide Quellen gleichermaßen. Fehlt die Geschwindigkeit ganz, gilt
  „steht", damit die Navigation nie dauerhaft ausgeblendet bleibt.

Grenze: Das GPS läuft nur, solange das Dashboard aktiv ist. Für die Aufzeichnung im Hintergrund
über den Foreground Service müsste der Service den Typ `location` führen und eine weitere
Berechtigung anfragen — das ist bewusst nicht Teil dieses Release.

### Kacheln sagen, warum sie leer sind

Eine Kachel ohne Messwert zeigte bisher immer den Hinweis auf die Belegung per Long-Press — auch
wenn der Slot belegt war und schlicht das Fahrzeug den Wert nicht liefert. Jetzt steht dort
„Vom Fahrzeug nicht unterstützt", sobald die PID-Discovery abgeschlossen ist und der zugewiesene
PID nicht in der gemeldeten Menge steht. Ohne Verbindung oder vor der Discovery bleibt es beim
neutralen Hinweis — eine Aussage über Unterstützung wäre dort nicht belegbar.

### Sonstiges

- Dreizehn neue Unit-Tests (322 statt 309) für die Zusammenführung der Geschwindigkeitsquellen,
  die Platzhalter-Logik und den GPS-Schalter in den Anzeigeeinstellungen.
- Die README-Zeile „Polling-Intervall" bei den benutzerseitigen Einstellungen ist entfallen: Eine
  solche Einstellung gab es nie, das Intervall liegt fest bei 500 ms.

---

## Version 1.9.1 (versionCode 34)

Zwei Nachbesserungen aus dem ersten Praxiseinsatz von 1.9.0.

---

### Sparkline hatte zu wenig Platz

In 1.9.0 teilten sich Sparkline und Wert eine Zeile. Bei vierstelligen Werten wie „3885" blieben
davon nur wenige dp für den Graphen übrig, und er lief optisch in die Ziffern hinein. Die
Sparkline liegt jetzt als eigenes Band über dem Wert und nutzt die volle Kachelbreite. Die
Kachel-Anatomie in `CLAUDE.md` ist entsprechend nachgezogen.

### Bordspannung war fälschlich rot

`pidTileStatus` stufte alles unter 11 V als kritisch ein — auch 0 V. Genau das liefert die
Anzeige aber, wenn der PID gar nicht beantwortet wird: Der Simulator kannte 0142 nicht, die
Kachel zeigte „0 V" und färbte sich alarmrot. Ein antwortendes Steuergerät hat immer Spannung,
0 V bedeutet also „keine Messung" und nicht „Batterie leer".

- `pidTileStatus` behandelt Werte ≤ 0 V wie fehlende Daten und lässt die Kachel neutral.
- Der ECU-Simulator meldet 0142 jetzt als unterstützt und liefert konstant 14.2 V, damit die
  Kachel im Simulatorbetrieb einen plausiblen Wert zeigt. PID 0x42 gehört dabei in den
  0140-Bereich (0x41–0x60), nicht in den 0120-Bereich — ohne das bleibt die Bordspannung in der
  Sensor-Auswahl unsichtbar, weil `availablePids` gegen die vom Steuergerät gemeldete Menge
  filtert. `buildBitmap` wirft jetzt, wenn ein PID nicht in seinen Bereich passt, statt ihn
  stillschweigend zu verschlucken.

### Verlaufslinien sichtbarer

Die Sparklines waren mit 2 dp zu dünn, um sie bei Tageslicht im Vorbeischauen zu erfassen. Sie
sind jetzt 4 dp stark mit runden Enden, unterlegt mit einem nach unten auslaufenden Verlauf
(32 % Deckkraft an der Linie, transparent am Boden), und ein Punkt markiert den jüngsten Wert —
ohne ihn liest sich die Kurve in beide Richtungen gleich. Das Band ist von 28 auf 48 dp
gewachsen.

### Drehzahlbalken in Segmenten

Der durchgehende Balken ist einer Segmentanzeige gewichen: 50 Stufen à 200 rpm über die volle
Breite. Die unbeleuchteten Segmente bleiben sichtbar, sodass die Skala auch bei 0 rpm lesbar ist
und die Position der Drehzahl im Band ohne Lesen der Zahl erfassbar wird. Segmente oberhalb des
Blink-Schwellwerts leuchten rot — wie die Schaltblitz-Skala eines Cockpit-Drehzahlmessers.

Vier neue Unit-Tests decken die Statusfarbe und den Simulator ab (309 statt 305).

---

## Version 1.9.0 (versionCode 33)

Dashboard-Layout: Die Kacheln sind wieder lesbar, und die Beschriftungen sprechen Deutsch.

---

### Kachel-Geometrie

Die Sensor-Kacheln dehnten sich über die gesamte Resthöhe des Bildschirms — gemessen rund
267 dp statt der 120 bis 140 dp, für die die Kachel-Anatomie in `CLAUDE.md` gedacht ist. Label
oben links und Wert unten rechts lagen dadurch so weit auseinander, dass die Zuordnung bei einem
halben Sekundenblick nicht mehr gelang.

- Feste Höhen: 170 dp im Hoch-, 160 dp im Querformat. Der verbleibende Platz bleibt frei, statt
  die Kacheln aufzublähen.
- Hochformat: zwei Spalten, zwei Reihen — der im Design-Profil vorgesehene Standard.
- Querformat: vier gleich breite Spalten nebeneinander statt drei mit einer gestapelten Spalte,
  in der eine halbe Kachel leer blieb.
- Sparkline und Wert teilen sich jetzt eine Zeile. Vorher lagen beide unten im selben Bereich,
  die Trendlinie lief hinter den Ziffern durch und schnitt quer durch die Einheit.
- Ein unbelegter Slot zeigt statt einer toten Fläche den Hinweis, dass ein langer Druck ihn
  belegt.

### Beschriftungen

- Kachel-Labels erscheinen in ALL-CAPS mit 0.12 em Letterspacing, wie im Profil vorgesehen.
- Die 19 PID-Namen kommen aus den String-Ressourcen und sind übersetzt: auf einem deutschen
  Gerät steht „KÜHLMITTEL" statt „Coolant Temp" über einer Navigation, die „Verlauf" und
  „Einstellungen" heißt. `PidDefinition.name` bleibt als stabiler, sprachunabhängiger
  Bezeichner für Sortierung, Logs und Freeze-Frame-Schlüssel erhalten.

### Kein Alarm beim Start

Die Demo-Animation lief beim Öffnen ohne Verbindung bis knapp 10000 rpm gegen den
Blink-Schwellwert: Der Kopfbalken wurde alarmrot und zeigte „9977 rpm", während die RPM-Kachel
darunter 0 anzeigte. Ohne Verbindung bleibt der Balken jetzt in der Primärfarbe, blinkt nicht
und zeigt „-- rpm" — die Bewegung bleibt als Lebenszeichen, gibt sich aber nicht als Messwert aus.

### Bekannte Lücke

Die Freeze-Frame-Ansicht zeigt weiterhin englische PID-Namen: `ObdRepository` nutzt
`PidDefinition.name` dort als Schlüssel der Ergebnis-Map. Das sauber zu lösen heißt, die Map auf
PID-IDs umzustellen und die Auflösung in die UI zu ziehen — ein eigener Schnitt.

---

## Version 1.8.1 (versionCode 32)

Debug- und Release-Build lassen sich jetzt parallel installieren.

---

Der ECU-Simulator erscheint nur in Debug-Builds als virtuelles Gerät „OBD Sim" in der
Geräteliste — im Release-Build fehlt der Eintrag. Weil beide Varianten bisher dieselbe
`applicationId` trugen, war zum Testen ohne Fahrzeug jedes Mal ein Deinstallieren nötig, und
damit gingen aufgezeichnete Fahrten und Logs verloren.

- Der Debug-Build trägt `applicationIdSuffix = ".debug"` und `versionNameSuffix = "-debug"`,
  läuft also als eigene App mit eigener Datenbank neben der Release-Version.
- Im Launcher heißt er **BOM Debug**: Das Label kommt jetzt aus `@string/app_label`, das die
  Debug-Quellen überschreiben.

Am ausgelieferten Release-Build ändert sich nichts — FileProvider und `SessionRepository`
leiteten ihre Authority ohnehin aus `applicationId` beziehungsweise `context.packageName` ab.

---

## Version 1.8.0 (versionCode 31)

Wartungs-Release: Build-Stack auf aktuellen Stand, Lint wieder scharf, Bluetooth-Berechtigungen
gehärtet und die Instrumentation-Tests nach über einem Jahr wieder grün.

---

### Toolchain

Der Compose-Stack lief seit 1.7.0 auf einem Build-Stack von Anfang 2025. Das war die Ursache
gleich mehrerer Altlasten und ist jetzt aufgelöst:

| Komponente | vorher | nachher |
|---|---|---|
| Android Gradle Plugin | 8.7.3 | 9.3.0 (built-in Kotlin) |
| Gradle | 9.2.1 | 9.5.0 |
| Kotlin | 2.0.21 | 2.4.10 |
| KSP | 2.0.21-1.0.27 | 2.3.11 |
| Hilt | 2.52 | 2.60.1 |
| Room | 2.6.1 | 2.8.5 |
| compileSdk | 35 | 36 |

Ab AGP 9 bringt das Android-Plugin Kotlin selbst mit — das separate `kotlin-android`-Plugin ist
entfallen, die Kotlin-Version wird im Root-Build per `buildscript`-Classpath gesetzt, und
`kotlinOptions` ist einem Top-Level-`kotlin { compilerOptions { } }` gewichen.

Der Compose-Stack bleibt bewusst auf BOM 2026.05.00: Compose 1.12 verlangt compileSdk 37 und
damit AGP 9.4 plus Gradle 9.6, während Kotlin 2.4 offiziell nur bis Gradle 9.5 freigegeben ist.
Dieser Sprung gehört in ein eigenes Release.

### Lint wieder aktiv

Der Workaround aus 1.7.0 (`checkReleaseBuilds = false`, `abortOnError = false`) ist ersatzlos
entfallen — die Lint-Engine von AGP 9.3 verträgt die Compose-Sourcen, an denen AGP 8.7.3 mit
`IncompatibleClassChangeError` zerbrochen ist. Alle 24 Befunde des ersten Laufs sind behoben,
`lintVitalRelease` läuft im Release-Build wieder mit.

### Bluetooth-Berechtigungen gehärtet

Ab Android 12 kann der Nutzer BLUETOOTH_CONNECT und BLUETOOTH_SCAN jederzeit entziehen — auch
mitten in der Fahrt. Statt die 19 Lint-Befunde zu unterdrücken, ist die Bluetooth-Schicht jetzt
dagegen abgesichert:

- Neuer Helper `BluetoothPermissions`; unterhalb API 31 liefert er ohne Context-Zugriff `true`,
  weil BLUETOOTH und BLUETOOTH_ADMIN dort Install-Time-Berechtigungen sind.
- Neue `BluetoothException.PermissionDenied`, im `ObdErrorMapper` auf eine eigene Meldung
  abgebildet (deutsch und englisch).
- `BleConnection` prüft vor dem Verbindungsaufbau und fängt `SecurityException` in jedem
  GATT-Callback ab; ein Entzug mitten im Schreibpfad wird als `IOException` gemeldet, damit
  `ObdRepository` ihn wie einen Verbindungsabbruch behandelt.
- `BluetoothDeviceScanner` prüft vor `bondedDevices()` und `scanBle()` und liest Gerätenamen nur
  noch über einen abgesicherten Zugriff.
- `SettingsViewModel.loadDevices()` zeigt eine Fehlermeldung statt die App zu beenden.

Nebenbefund aus den Gerätetests: Ein Foreground-Service vom Typ `connectedDevice` startet nur,
wenn zur Laufzeit mindestens eine Bluetooth-Berechtigung erteilt ist — sonst lehnt das System
den Start mit einer SecurityException ab.

### Weitere Korrekturen

- `HistoryScreen` löst seine Texte auf Composable-Ebene über `stringResource` auf, statt über
  `context.getString()` im `LaunchedEffect` — letzteres überging die Recomposition bei
  Sprachwechsel.
- `ACCESS_FINE_LOCATION` ist wie `ACCESS_COARSE_LOCATION` auf API ≤ 30 begrenzt; ab Android 12
  trägt `BLUETOOTH_SCAN` bereits `neverForLocation`.
- Neue Backup-Regeln (`data_extraction_rules.xml`, `backup_rules.xml`): Fahrt-Sessions,
  Sensormesswerte und Logs sind gerätegebunden und gehören weder in ein Cloud-Backup noch auf
  ein anderes Gerät.
- Überflüssiger SDK-Guard in `Obd2Application` entfernt, fünf verwaiste Strings in beiden
  Locales gelöscht, Listen-Icon der Navigation auf die AutoMirrored-Variante umgestellt.

### Tests

**305 JVM-Unit-Tests** (vorher 298) und **28 Instrumentation-Tests** — erstmals seit 1.7.0 wieder
vollständig grün auf einem Gerät (Pixel 8 Pro, Android 17). Die sieben neuen Unit-Tests decken
den Berechtigungs-Pfad ab.

Die als „strukturell" vermutete Altlast aus 1.7.0 bestand aus vier unabhängigen Ursachen:

1. Compose `ui-test` zog transitiv ein Espresso mit, das per Reflection auf die ab Android 17
   entfernte Methode `InputManager.getInstance()` zugreift — jetzt explizit auf 3.7.0 gehoben.
2. `ObdForegroundServiceTest` band über `ServiceTestRule` an einen Service, dessen `onBind()`
   `null` liefert. Neu gefasst, mit echten Zusicherungen statt `assertTrue(true)`, und mit
   `GrantPermissionRule` für den Foreground-Service-Start.
3. Die Compose-Tests hatten englische UI-Strings fest verdrahtet, das Testgerät läuft auf
   Deutsch. Erwartungen kommen jetzt aus den Ressourcen.
4. Die Display-Zeitsperre kippte die Suite mitten im Lauf; `HiltTestActivity` hält den Bildschirm
   jetzt an und zeigt sich über dem Sperrbildschirm.

Vollständiges Build-Protokoll: [`docs/plans/2026-09-21-toolchain-upgrade.md`](docs/plans/2026-09-21-toolchain-upgrade.md).

---

## Version 1.7.0 (versionCode 30)

UI-Design-Refresh: „racing-monitor + premium + utility" (Referenz DMD²), plus Modernisierung des Compose-Stacks.

---

### Typografie

- **IBM Plex Mono** für alle Live-Werte (RPM, Speed, Temperaturen, Spannung, VIN, Sensor-Snapshots). Drei Gewichte (Regular/Medium/Bold) als App-Ressource gebündelt, OFL-Lizenz mitversendet (`docs/licenses/`).
- OpenType-Feature `tnum` aktiv → Ziffern-Spalten bleiben beim Update stabil, kein horizontales Wackeln.
- `LiveValueText`-Composable (`ui/components/`) als kanonisches API; `TextStyle.asLiveValue()`-Extension für ad-hoc-Inline-Verwendung.
- Animierte Übergänge via `AnimatedContent` (slideInVertically + fadeIn, 180 ms EaseOutCubic) — opt-in `animated = true`, aktiviert für Freeze Frame und VIN. Dashboard-Tiles bleiben statisch (vermeiden Motion-Thrash bei 5–10 Hz Updates).

### Dashboard-Tiles & Status-System

- Neues `LiveValueTile`-Composable: domain-agnostische Sensor-Kachel mit 4-dp-Status-Side-Bar, Label oben links, Wert + Einheit unten rechts, optionaler Sparkline.
- `TileStatus`-Enum (`Ok | Warning | Critical`) treibt die Akzentfarbe der Side-Bar und des Wertes.
- `StatusColors`-Single-Source-of-Truth (`ui/theme/StatusColors.kt`) ersetzt hardcoded Hex-Werte: `Warning = #F59E0B`, `Critical = #EF4444`. Cross-theme — Alarm-Farben sind unabhängig vom aktiven Theme (Blau/Amber/Grün).
- **Per-PID-Thresholds:** Coolant > 110/120 °C, Oil > 120/135 °C, Voltage < 11.8/11.0 V (+ Überladen > 15 V), RPM > 6500/7500, Load > 90 %, Fuel < 20/10 %, Cat-Temp > 750/850 °C → automatischer Status-Wechsel der jeweiligen Kachel.
- `Sparkline`-Composable aus DashboardComponents extrahiert nach `ui/components/Sparkline.kt`, jetzt wiederverwendbar.
- `StatusDot`-Composable (8 dp, 4 States inkl. pulsierendes „Connecting") als wiederverwendbarer Connection-/Health-Indikator. Ein Ok-Dot erscheint jetzt auf dem Dashboard vor dem BT-Icon, wenn der Adapter verbunden ist.

### Spec-Sheet-Look für statische Werte

- `FreezeFrameScreen`: Key-Value-Zeilen jetzt im Spec-Sheet-Layout (linksbündiges Label, rechtsbündiger Mono-Wert), spacing 4 → 8 dp.
- `VehicleInfoScreen`: VIN in Plex Mono — 17 Zeichen alphanumerisch, drastisch besser lesbar (`0` vs `O`, `1` vs `l` vs `I`).

### Komponent-Vokabular vervollständigt

- `SectionLabel(text)`: ALL-CAPS labelMedium mit 0.12.em letter-spacing für Sektions-Header.
- `ActionButton(label, onClick)`: min 56 dp Höhe + 160 dp Breite, rectangular Shape, Glove-Friendly.
- `HairlineDivider()`: 1-dp Outline-Divider mit 8 dp vertikalem Atemraum.

### Compose-Stack

- **Compose BOM 2024.12.01 → 2026.05.00** (Compose 1.7.6 → 1.11.x, Material3 1.4.0).
- Material Icons als explizite Dependencies (1.7.8) — Google hat sie ab Compose 1.8 aus dem BOM entfernt.

### Design-Profil (CLAUDE.md)

Neue Datei `CLAUDE.md` im Repo-Root mit komplettem Design-System-Profil: Aesthetic Direction (DMD²-Referenz), Color-Tokens, Typografie-Hierarchie, LiveValueTile-Anatomie, Animation-Disziplin, Iconography, Verbotene Patterns, Material-3-Anker, Code-Konventionen. Wird in jeder Claude-Code-Session automatisch geladen.

### Tests

- **27 neue Unit-Tests** für `pidTileStatus()`-Mapping (Threshold-Edges, NaN/Inf, unbekannte PIDs).
- Gesamt: **298 JVM-Unit-Tests**, alle grün.

### ECU-Simulator & Test-Ausbau

Vor dem Design-Refresh entstanden (versionCode 29 → 30), bisher nicht dokumentiert:

- **ECU-Simulator:** `BtType.SIMULATOR` mit `SimulatedEcuDispatcher` und `SimulatorObdConnection` — synthetische Steuergerät-Antworten mit animiertem RPM-Profil (900–8500 über 20 s Periode). In Debug-Builds als Gerät „OBD Sim" in der Geräteliste, wird nie als `lastDevice` persistiert und löst kein `isMoving` aus.
- **CSV-Export** streamt jetzt über einen Cursor, statt alle Readings in den Speicher zu laden.
- **Refactorings:** `DashboardScreen` und `SettingsScreen` in `DashboardComponents.kt` bzw. `SettingsComponents.kt` aufgeteilt, `DashboardViewModel`-Init-Blöcke in private Funktionen zerlegt, Magic Number 60 durch `SPARKLINE_WINDOW` ersetzt.
- **Test-Offensive:** androidTest-Infrastruktur (Hilt, Compose UI, MockK), PidRegistry-Formelgrenzen für alle 19 PIDs, `ObdErrorMapper`-Mapping, Basistests für `ClassicBtConnection` und `BleConnection`, Room-DAO-Instrumentationstests (AppLog, SensorReading, Session), `ObdForegroundService`-Test sowie Compose-UI-Tests für Dashboard, DTC und Settings.

### Bekannte Issues

- ~~`connectedDebugAndroidTest` schlägt auf Android 16 (API 36) weiter fehl~~ — **behoben in 1.8.0.** Die Ursache war nicht strukturell, sondern vierfach: veraltetes Espresso, ein Test an einem Service ohne Binder, fest verdrahtete englische UI-Strings und die Display-Zeitsperre.

---

## Versionen 1.1 bis 1.6

> Diese Abschnitte wurden am 21.09.2026 aus der Git-Historie rekonstruiert und bündeln die Patch-Releases je Minor-Version. Die versionCodes 24 bis 27 wurden seinerzeit ohne eigene Bump-Commits in den jeweiligen Fix-Commits mitgeführt.

---

## Version 1.6.0 (versionCode 29)

Hintergrund-Betrieb: die Verbindung überlebt den App-Wechsel.

### Foreground Service

- `ObdForegroundService` mit eigenem Notification-Channel, Manifest-Eintrag und persistenter Benachrichtigung.
- Start und Stopp aus `SettingsViewModel` (Connect/Disconnect) und `DashboardViewModel`.
- Disconnect ist gegen Scope-Cancellation abgesichert, damit der Adapter beim Service-Stopp sauber getrennt wird.
- `POST_NOTIFICATIONS` wird ab Android 13 zur Laufzeit angefragt.

### OBD-Schicht

- Live-Polling filtert die konfigurierten PIDs gegen die vom Steuergerät tatsächlich unterstützte Menge.
- `PRAGMA foreign_keys = ON` per `RoomDatabase.Callback` — verhindert verwaiste `sensor_readings` beim Bulk-Delete.

---

## Versionen 1.5.0 bis 1.5.11 (versionCode 17 bis 28)

Zwölf Releases: App-Logs, Farbthemen und eine breite Stabilitätswelle.

### App-Logs

- `AppLogEntity`, `AppLogDao`, `AppLogRepository` und Room-Migration 1 → 2; `LogLevel` ins `data`-Package verschoben.
- `AppLogsScreen` in der Settings-Navigation, Bluetooth-Verbindungsereignisse werden protokolliert.
- Automatisches Pruning nach 30 Tagen plus `LIMIT 1000` auf der Query; thread-sicherer `DateTimeFormatter`.

### UI-Redesign

- Drei dunkle Farbthemen (Blau, Amber, Grün) mit zweistufigem Farb- und Helligkeits-Picker.
- Flaches Edge-to-Edge-Dashboard: 0 dp Elevation, 1-dp-Divider, Primary-Akzent.
- Diverse Layout-Korrekturen an Theme-Picker und BottomNav; der RPM-Alarm blinkt themeunabhängig rot; RPM-Label bricht bei 10000 nicht mehr um.
- Status- und DTC-Banner aus dem Dashboard entfernt — aktive Fehlercodes landen stattdessen im App-Log.

### Funktionen

- Dynamischer RPM-Blink-Threshold, skaliert über die Motortemperatur.
- Slot-Picker und „Aufgezeichnete PIDs"-Liste zeigen den jeweils aktuellen Live-Wert.
- Toggle „Alle gekoppelten Geräte anzeigen" plus erweiterte OBD2-Keyword-Liste im Scanner.

### Stabilität

- Spurious `slotPids`-Emissionen unterdrückt — verhinderte unnötige Neustarts der OBD-Session.
- `ConnectionLost` wird aus `readFreezeFrame` propagiert statt verschluckt.
- Explizites `deleteBySession` im `SensorReadingDao` vor dem Session-Delete.
- `FLAG_KEEP_SCREEN_ON` auf das Dashboard und den verbundenen Zustand eingeschränkt.
- BLE-GATT-Writes serialisiert (Semaphore + 5-s-Timeout) — behebt verlorene Kommandos auf Clone-Adaptern.
- Gepufferte Reads im `ElmController`, `convertUnit` nach `obd/UnitConverter.kt` verschoben, Fuel-Trim-Formelgrenze dokumentiert.

---

## Versionen 1.4.0 bis 1.4.9 (versionCode 7 bis 16)

Cockpit-Layout, frei belegbare Kacheln und Auto-Connect.

- **Tile-Slots:** `slotPids`/`setSlotPid` ersetzen `selectedPidIds`/`togglePid`. Long-Press auf eine Kachel öffnet den Sensor-Picker, die Slot-Reihenfolge bestimmt die Anzeige. `availablePids` filtert gegen die ECU-Unterstützung, `DEFAULT_SLOT_PIDS` ist immer auf vier Einträge aufgefüllt.
- **Layout:** TopAppBar-Titel entfernt, Bluetooth-Icon in die RPM-Bar-Zeile geholt; SensorCard mit Label oben links und Wert unten rechts; BottomNav auf 56 dp, RpmBar auf 72 dp.
- **Auto-Connect:** Beim App-Start wird das zuletzt verwendete Bluetooth-Gerät automatisch verbunden (testgetrieben entwickelt).
- **Display:** `FLAG_KEEP_SCREEN_ON` eingeführt (in 1.5.x auf das Dashboard eingegrenzt).
- **Fixes:** RPM-Blink nutzt `rememberUpdatedState`, damit die Coroutine nicht neu startet; ruhigere Demo- und Blink-Timings.

---

## Versionen 1.3.0 und 1.3.1 (versionCode 5 und 6)

Motorrad-Dashboard und Typografie für die Lesbarkeit in Fahrt.

- **Fixes-4-Slot-Layout** für Portrait und Landscape, Platzhalter-Kacheln im getrennten Zustand.
- **Space Grotesk** als App-weite Schrift, globale Typo-Skalierung 1.18× und größere SensorCard-Styles.
- **RPM-Bar** mit konfigurierbarem Blink-Threshold (Maximum auf 10000 angehoben), RPM wird immer gepollt, Schwellwert-Slider im DashboardConfig, Demo-Animation beim Öffnen ohne Motorlauf.
- **Ruhigeres UI:** Icon-Jumping behoben (BottomNav nach `AppNavHost` gehoben, `pinnedScrollBehavior`, `contentWindowInsets = 0` auf inneren Scaffolds), BottomNav-Labels immer sichtbar.
- Ganzzahlige Dashboard-Werte, Spannung mit einer Nachkommastelle, neuer App-Info-Screen.
- **1.3.1:** Das Bluetooth-Icon führt im getrennten Zustand direkt in die Einstellungen, mit korrekten Navigations-Flags gegen einen kaputten Back-Stack.

---

## Version 1.2.0 (versionCode 4)

Anzeige-Einstellungen und Layout-Politur.

- **Display-Sektion:** imperiale Einheiten über den `convertUnit`-Helper, Theme-Auswahl, in `MainActivity` auf das `MaterialTheme` angewandt.
- **Fahrmodus:** BottomNav blendet während der Fahrt aus — `isMovingFlow` im `ObdRepository`, Präferenz `hideNavOnDrive`, gebündelt im neuen `DriveStateViewModel`.
- **Dashboard:** Bluetooth-Status und DTC-Warnung direkt im Dashboard, SensorCard mit Value/Unit-Split und Akzentstreifen, Grid bleibt im getrennten Zustand sichtbar.
- **Screens:** Scroll-Behavior, PullToRefresh und Icon-Empty-States für DTC- und History-Screen, einheitliches `contentPadding`.
- **Build:** Gradle 8.11.1 → 9.2.1 plus foojay-Toolchain-Resolver.
- App-Name auf „Bike OBD2 Monitor" geändert.

---

## Version 1.1.0 (versionCode 3)

Aufzeichnungs-Einstellungen.

- Recording-Präferenzen (Modus und gefilterte PIDs) in `AppPreferences`.
- Neuer LoggingConfig-Screen für die Auswahl der aufgezeichneten Sensoren.
- `DashboardViewModel` wendet die Recording-Filter an und prüft die DTC-Session.

---

## Version 1.0.1 (versionCode 2)

Produktionsreife-Fixes nach dem Post-Release-Audit.

---

### Änderungen

#### App-Icon
- Adaptives Tachometer-Icon (Material Design „speed") auf Teal-Hintergrund (`#006874`) ersetzt das generische Android-Icon. Unterstützt runde und eckige Launcher (minSdk 26 → Adaptive Icons vollständig).

#### Fahrthistorie
- **Session-Löschung:** Jede Session kann über einen Löschen-Button mit Bestätigungs-Dialog entfernt werden. Readings werden via `ForeignKey ON DELETE CASCADE` automatisch mitgelöscht.
- **90-Tage-Cleanup:** Sessions älter als 90 Tage werden beim App-Start automatisch bereinigt.

#### Bluetooth-Verbindung
- Der Reconnect-Loop bricht sofort ab, wenn Bluetooth deaktiviert ist, statt alle fünf Versuche zu durchlaufen (bisher bis zu 93 Sekunden Wartezeit).

#### Crash-Reporting
- Sentry SDK (`sentry-android:8.29.0`) ersetzt das reine Logcat-Logging. Exceptions werden via `Sentry.captureException`, Breadcrumbs via `Sentry.addBreadcrumb` erfasst.
- Sentry ist inaktiv, solange `SENTRY_DSN` nicht gesetzt ist — kein Pflicht-Setup für Dev-Builds.
- **Konfiguration:** `SENTRY_DSN=https://...@sentry.io/...` in `local.properties` oder als Umgebungsvariable.

---

### Technische Hinweise

- **versionCode** muss vor dem nächsten Release-Build in `app/build.gradle.kts` auf `2` erhöht werden.
- Kein Datenbankschema-Bump — nur neue DAO-Queries, keine Schema-Änderung.

---

## Version 1.0 (versionCode 1)

Erste Veröffentlichung.

---

### Features

#### Dashboard
- Echtzeit-Sensormesswerte in einem 2-spaltigen Karten-Grid
- Live-Sparkline-Diagramm pro Karte (Ringpuffer mit 60 Werten, Canvas-basiert)
- Konfigurierbare PID-Auswahl: Nur die gewählten Sensoren werden abgefragt
- Verbindungsstatus-Anzeige mit Fehlermeldungen

#### Bluetooth-Verbindung
- Unterstützt ELM327-Adapter über Bluetooth Classic und BLE
- Automatische Wiederverbindung bei Verbindungsverlust: 5 Versuche mit exponentiellem Backoff (3 s → 6 → 12 → 24 → 48 s)
- Letztes verbundenes Gerät wird persistiert (DataStore) — Cold-Launch-Reconnect ohne manuelle Auswahl
- Mutex-serialisierter Befehl/Antwort-Austausch mit dem Adapter

#### OBD2-Protokoll
- ELM327 AT-Initialisierungssequenz
- Automatische PID-Discovery (Modes 0100 / 0120 / 0140)
- 25 vordefinierte PIDs: RPM, Geschwindigkeit, Kühlmitteltemperatur, Motorlast, Drosselklappe, Kurzzeit-/Langzeit-Kraftstofftrim, Zündzeitpunkt, Einlasstemperatur, MAF, O₂-Sensoren u. a.
- VIN-Abfrage (Mode 09)
- Fehlercodes (Mode 03 / 07) mit P/C/B/U-Klassifikation und Beschreibung
- Freeze Frame (Mode 02)

#### Fehlercodes (DTC)
- Liste bestätigter und ausstehender Fehlercodes
- Pending-Badge pro Eintrag
- Direktlink zum Freeze Frame des jeweiligen Codes

#### Freeze Frame
- Sensor-Snapshot zum Zeitpunkt des DTC-Auftretens als Key-Value-Tabelle

#### Fahrthistorie
- Automatische Aufzeichnung jeder Verbindungssession in Room (SQLite)
- Paginierte Ansicht mit Paging 3 (20 Einträge pro Seite)
- Export einer Session als CSV (`timestamp,pid,value`) via Android Share Intent

#### Fahrzeuginfo
- Anzeige der VIN

#### Einstellungen
- Liste gekoppelter Bluetooth-Geräte mit Verbinden/Trennen-Aktionen
- Navigation zur Dashboard-Konfiguration und Fahrzeuginfo

#### Dashboard-Konfiguration
- Checkboxen zur PID-Auswahl
- Mindestens ein PID muss aktiv bleiben

---

### Lokalisierung

Englisch (Standard) und Deutsch (`values-de`).

---

### Technische Hinweise für den Release

- **Signing:** `signingConfig` greift nur wenn `keystore.jks` im Projektroot vorhanden ist. Ohne Keystore wird eine unsignierte APK erzeugt (CI/Dev). Schlüsseldaten werden über `local.properties` oder Umgebungsvariablen (`KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) eingelesen.
- **Datenbankschema:** Version 1 — vor jedem Schema-Bump muss eine explizite `Migration(from, to)` registriert werden.
- **Backup:** `android:allowBackup="false"` — die gespeicherte Bluetooth-MAC-Adresse ist auf einem anderen Gerät wertlos und würde sofort Verbindungsfehler produzieren.
- **Minimalanforderung:** Android 8.0 (API 26)

---

### Bekannte Einschränkungen

- Sehr lange Sessions (viele Stunden, hohe PID-Frequenz) laden alle Readings beim CSV-Export in den Arbeitsspeicher. Für solche Szenarien wird in einer späteren Version ein cursor-basierter Export ergänzt.
- Der TOCTOU-Zeitraum zwischen dem zweiten Verbindungsguard und der Mutex-Akquisition im Reconnect-Loop ist konstruktionsbedingt minimal, aber theoretisch vorhanden (dokumentiert in `DashboardViewModel`).
