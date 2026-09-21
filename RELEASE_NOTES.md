# Release Notes — OBD2 Monitor

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
