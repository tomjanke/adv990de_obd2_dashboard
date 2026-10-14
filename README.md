# OBD2 Monitor

Android-App zur Echtzeit-Diagnose über einen OBD2-Adapter (ELM327 über klassisches Bluetooth). Liest Live-Sensordaten, zeigt Fehlercodes (DTCs), speichert Fahrt-Sessions lokal und visualisiert konfigurierbare Dashboards in Jetpack Compose.

> Aktuelle Version: **1.27.3** (versionCode 105) · minSdk **26** (Android 8.0) · targetSdk **37** (Android 17) · compileSdk **37**

---

## Funktionen

- **Live-Dashboard** mit frei konfigurierbaren PID-Kacheln (Drehzahl, Geschwindigkeit, Kühlmitteltemperatur, Motorlast, Kraftstoffstand, etc.)
- **Automatische PID-Discovery** — die App fragt das Steuergerät, welche PIDs überhaupt unterstützt werden, und filtert die Live-Abfrage entsprechend
- **DTCs lesen** (Diagnostic Trouble Codes, nur lesend — die App schreibt nie ins Fahrzeug) mit menschenlesbaren Beschreibungen (37 häufige Codes, deutsch und englisch; jeder andere Code zeigt „Unbekannter Fehlercode“ neben dem Code) und **lokaler Fehlerhistorie** (erstmals/zuletzt gesehen, auch offline; einzeln oder komplett löschbar mit 10 s „Rückgängig“)
- **Freeze Frame** — Sensorwerte zum Zeitpunkt eines gespeicherten Fehlers
- **Fahrzeug-Info** — die Fahrgestellnummer (VIN, 17 Zeichen, via Mode 09 PID 02, aus CAN-, K-Line- und J1850-Antworten dekodiert; liefert das Fahrzeug keine, steht dort „Kein VIN verfügbar“). ECU-Name und Kalibrier-IDs werden nicht gelesen.
- **Geschwindigkeit per GPS** als Rückfallebene, wenn das Fahrzeug PID 010D nicht liefert — bei Motorrädern der Normalfall; läuft mit erteilter Hintergrund-Berechtigung auch weiter, wenn die App minimiert ist
- **Virtuelle Ganganzeige** — errechnet den eingelegten Gang aus Motordrehzahl und Geschwindigkeit (Hysterese gegen Flackern), konfigurierbar über einen eigenen Getriebe-Einstellungsbildschirm (Radumfang, Primär-/Sekundärübersetzung, Übersetzung je Gang)
- **Simulierte Öltemperatur-Leiste** — schmaler Farbstreifen unter der Drehzahl-Anzeige, der aus der Kühlmitteltemperatur abschätzt, ob das Motoröl wahrscheinlich noch kalt ist (Rot, ≤ 75 °C), sich aufwärmt (Gelb) oder Betriebstemperatur hat (Grün: seit 10 Minuten über 80 °C, kurze Einbrüche unter 78 °C bis zu 2 Minuten werden toleriert); grau ohne Verbindung
- **Datum, Uhrzeit und Außentemperatur während der Fahrt** — ist „Navigationsleiste beim Fahren ausblenden" an und das Fahrzeug in Bewegung, erscheint an Stelle des Hauptmenüs (auf jedem Screen, in exakt dessen Höhe) eine Statuszeile mit Datum/Uhrzeit und der Außentemperatur am aktuellen Standort — frei über [Open-Meteo](https://open-meteo.com/) (kein API-Key, Namensnennung UND Lizenztext CC BY 4.0 je als eigener Link unter App-Info), in °C oder °F je nach Einheiten-Einstellung. Abgefragt wird einmal pro Minute und nur, solange die Zeile sichtbar ist; ein fehlgeschlagener Abruf lässt den letzten Wert bis zu 30 Minuten stehen. Datenschutz und Berechtigung: s. [Berechtigungen](#berechtigungen)
- **Fahrt-Historie** — Sessions werden in Room persistiert, mit CSV-Export; einzeln löschbar per Papierkorb oder Swipe (ohne Rückfrage, 10 s „Rückgängig“), „Alle löschen“ mit Rückfrage; die laufende Aufzeichnung bleibt immer erhalten (lokal, die App schreibt nie ins Fahrzeug)
- **Foreground Service** — Verbindung bleibt im Hintergrund aktiv (persistente Benachrichtigung mit „Disconnect"-Action)
- **Connection-Backends**:
  - **Klassisches Bluetooth (RFCOMM)** — gekoppelter ELM327-Adapter (Seriell-Profil SPP). Einen BLE-Zweig gibt es seit 1.24.9 nicht mehr: Die Geräteliste hat nie BLE-Adapter angeboten (der Scan wurde nirgends aufgerufen), der Code war tot.
  - **Simulator** — synthetischer ECU für Entwicklung und UI-Tests
- **Aufbewahrung** — einmal je App-Start löscht die App lokal Fahrten, die älter als **90 Tage** sind (die laufende Fahrt nie), App-Log-Zeilen älter als **30 Tage** und alles über den neuesten **1000** Log-Zeilen. Es gibt dafür keine Rückfrage und kein „Rückgängig“; wer Fahrten länger braucht, exportiert sie vorher als CSV. Gelöscht wird nur lokal, nie im Fahrzeug. Exportierte CSV-Dateien (Cache) räumt die App nach **24 Stunden** auf.
- **App-Logs** — eigene Log-Persistenz (Room) mit 30-Tage-/1000-Einträge-Limit (greift beim App-Start), einsehbar im Settings-Bereich; Löschen mit Rückfrage und 10 s „Rückgängig“ (lokaler Lösch-Vertrag, nie ein Schreibzugriff aufs Fahrzeug)
- **Material 3** UI mit drei dark-only Cockpit-Farbschemata (Blau/Amber/Grün, s. Design-Profil) sowie einem Default-Farbschema mit Hell/Dunkel/System-Wahl, deutsch und englisch lokalisiert
- **Crash-Reporting** über Sentry — nur in einer Version, die beim Bauen einen DSN bekommen hat; dann in den Einstellungen abschaltbar („Absturzberichte senden“)

---

## Hardware-Voraussetzungen

- Android-Gerät mit **Bluetooth Classic**, ab Android 8.0 (das Manifest verlangt die Bluetooth-Hardware: `uses-feature android.hardware.bluetooth`, `required="true"`; Standort-Hardware ist optional, `required="false"`)
- **ELM327-kompatibler OBD2-Adapter mit klassischem Bluetooth (SPP)** — im Betrieb mit einem MWT_OBD2 am Motorrad; **BLE-only-Adapter** (z. B. Vgate iCar Pro BLE, OBDLink CX im BLE-Modus) funktionieren nicht
- Fahrzeug mit OBD2-Buchse (ab Bj. ~2001 in EU, ~1996 in US verpflichtend)

---

## Build & Run

### Voraussetzungen
- JDK **21** von JetBrains (die Daemon-JVM-Kriterien in `gradle/gradle-daemon-jvm.properties`; Android Studio bringt die JetBrains Runtime mit, auch `~/.gradle/jdks` wird gefunden). Gradle lädt **kein** JDK nach — s. „Prüfsummen“
- Android Studio Ladybug oder neuer (für Compose Preview / Layout Inspector)
- Optional: ADB im `PATH` für Command-Line-Installation

### Build
```bash
./gradlew assembleDebug
```
Das APK liegt danach unter `app/build/outputs/apk/debug/app-debug.apk`.

### Auf Gerät installieren
```bash
./gradlew installDebug
```
Voraussetzung: USB-Debugging aktiviert, Gerät verbunden (`adb devices`).

### Prüfsummen (Lieferkette)
Die Gradle-Distribution ist per `distributionSha256Sum` (`gradle/wrapper/gradle-wrapper.properties`) festgelegt, Abhängigkeiten und Plugins per
`gradle/verification-metadata.xml` (SHA-256, 998 Komponenten): passt ein Artefakt nicht oder fehlt es, bricht der Build ab. **Neue oder aktualisierte
Abhängigkeit → Metadaten neu schreiben** — Befehl und Fallstrick (`--refresh-dependencies`) stehen in der [`CLAUDE.md`](CLAUDE.md). Das JDK lädt Gradle nie von selbst herunter (`org.gradle.java.installations.auto-download=false` in `gradle.properties`): für Toolchains gibt es keine Prüfsumme, und ein unbemerkt
nachgeladenes JDK wäre ein ungeprüftes Programm im Build. Fehlt es, bricht der Build mit „No matching toolchains found“ ab; dann einmal selbst installieren oder bewusst per
`./gradlew -Porg.gradle.java.installations.auto-download=true help` nachladen.

### Debug- und Release-Build parallel

Der Debug-Build trägt `applicationIdSuffix = ".debug"` und heißt im Launcher **BOM Debug**;
seine Version endet auf `-debug`. Beide Varianten lassen sich damit gleichzeitig installieren,
jede mit eigenen Daten — kein Deinstallieren, kein Datenverlust beim Wechsel.

Optisch unterscheiden sie sich am Launcher-Icon: Der Release-Build hat den marineblauen Hintergrund
(`#0B1B2E`), der Debug-Build ist amber (`#B45309`). Überschrieben wird dafür allein
`src/debug/res/drawable/ic_launcher_background.xml`; Vordergrund und Adaptive-Icon-Definition
bleiben gemeinsam in `src/main`, sodass beide Icons dieselbe Form behalten.

Hat der Nutzer ab Android 13 *themed icons* aktiviert, greift diese Unterscheidung nicht: Das
System verwirft dann die Hintergrundebene und tönt allein die `<monochrome>`-Ebene nach der
Wallpaper-Palette. Beide Installationen sehen dort gleich aus und sind nur am Namenszusatz
„Debug" auseinanderzuhalten.

Das ist vor allem für den **ECU-Simulator** relevant: Das virtuelle Gerät „OBD Sim" erscheint
in der Geräteliste nur, wenn `BuildConfig.DEBUG` gesetzt ist (siehe
`BluetoothDeviceScanner.bondedDevices`). Im Release-Build gibt es den Eintrag nicht — wer ohne
Fahrzeug testen will, nutzt die Debug-Variante.

---

## Release-Build

Der Release-Build wird über einen lokalen Keystore signiert. Schlüssel und Passwörter werden **nicht im Repository abgelegt**, sondern über Environment-Variablen oder `~/.gradle/gradle.properties`:

```properties
# ~/.gradle/gradle.properties (NICHT im Projekt-Ordner!)
KEYSTORE_PASSWORD=<dein-keystore-passwort>
KEY_ALIAS=<alias>
KEY_PASSWORD=<key-passwort>
SENTRY_DSN=<optional-sentry-dsn>
```

Der Keystore liegt standardmäßig als `keystore.jks` im Projekt-Root (per `.gitignore` ausgeschlossen). **Besser außerhalb des Projektbaums**
(z. B. `~/.android-keys/<name>.jks`, Ordner Modus 700, Datei Modus 600): dann `KEYSTORE_FILE=~/.android-keys/<name>.jks` in
`~/.gradle/gradle.properties` oder als Umgebungsvariable setzen (`~/` wird aufgelöst). Zeigt `KEYSTORE_FILE` ins Leere, **bricht der
Release-Build ab** (statt still ein unsigniertes APK zu bauen); ohne jede Angabe und ohne `keystore.jks` entsteht wie bisher ein unsigniertes APK.
`scripts/release.sh` verweigert den Release, wenn Keystore oder `~/.gradle/gradle.properties` für andere Benutzer lesbar sind (`chmod 600`), und
prüft, dass das APK mit dem Zertifikat aus `scripts/release-cert.sha256` signiert ist (öffentlicher SHA-256-Fingerabdruck, kein Geheimnis) — ein
falscher Schlüssel ergäbe ein Update, das sich nicht über die installierte App einspielen lässt. Wechselt der Schlüssel absichtlich (nie ohne Not:
Nutzer müssten neu installieren), den Fingerabdruck dort mit-ändern.

Release bauen:
```bash
./gradlew assembleRelease
```
Output: `app/build/outputs/apk/release/app-release.apk`

### Release-Reihenfolge

1. `appVersionCode` und `appVersionName` in `app/build.gradle.kts` erhöhen und committen:
   ```bash
   git commit -am "release: 1.9.0 (versionCode 32)"
   ```
2. Release bauen: `./gradlew assembleRelease`
3. APK auf dem Gerät installieren und kurz gegenprüfen.
4. Den Release-Commit taggen und beides pushen:
   ```bash
   git tag -a v1.9.0 -m "Release 1.9.0 (versionCode 32)"
   git push && git push --tags
   ```
5. Änderungen in `RELEASE_NOTES.md` festhalten.

**Tag-Konvention:** annotierte Tags im Format `vMAJOR.MINOR.PATCH`, gesetzt auf den Release-Commit (also den Commit, aus dem das ausgelieferte APK gebaut wurde). Die Tags für 1.0 bis 1.7.0 wurden am 21.09.2026 nachträglich aus der Historie rekonstruiert; `v1.0` sitzt mangels eigenem Bump-Commit auf dem letzten Commit vor dem 1.0.1-Bump.

---

## Tests

```bash
# JVM Unit Tests (1490 Tests, < 30 Sekunden)
./gradlew :app:testDebugUnitTest

# Instrumented Tests (benötigen Gerät/Emulator)
./gradlew :app:connectedDebugAndroidTest
```

| Test-Layer | Frameworks | Anzahl |
|---|---|---|
| JVM Unit Tests | JUnit 4 + MockK + kotlinx-coroutines-test | 1490 |
| Compose UI Tests | androidx.compose.ui.test + Espresso 3.7.0 + Hilt (`HiltTestRunner`) | 258 (Dashboard, Preview Banner, RpmBar, Slot Picker, Slot-Barrierefreiheit, Touch-Ziele, Lizenzseite, Statusleisten-Symbole, DTC, Settings, Theme Picker, Choice Setting, Confirm Delete Dialog, Empty State, Undo Snackbar, Swipe To Delete Row, Device Row, App Logs, App Info, Freeze Frame, Gear Ratio Field, Vehicle Info, History, Logging Config, Rpm Threshold Section, Settings Rows, Permission Gate, Live Value Tile, Live Value Text, Bottom Nav, Section Label, Sparkline, UiText) |
| Room Integration Tests | Room in-memory + `androidx.test.ext.junit` + `MigrationTestHelper` | 52 (AppLogDao, SensorReadingDao, SessionDao, DtcHistoryDao, AppDatabase-Migration 1→2→3→4, Datenbank-Bootstrap mit Foreign Keys, SessionRepository) |
| End-to-End über den ECU-Simulator | echtes `ObdRepository` + `SimulatorObdConnection` | 7 (Session, Discovery, Messwert-Strom, Dashboard) |
| Service Tests | `HiltAndroidRule` + `GrantPermissionRule` | 10 (ObdForegroundService, Benachrichtigungskanal, Sitzungsende, gemerkter Dienst-Typ) |
| Test-Infrastruktur | `HiltAndroidRule` | 2 (eigener DataStore je Test) |
| Vorschauen | `runAndroidComposeUiTest` | 1 (jede der 63 `@Preview`-Funktionen wird auf dem Gerät komponiert und muss etwas zeichnen) |
| Geräte-Test der Wetter-Uhr | `AndroidJUnit4` | 2 (`elapsedRealtimeSource` gegen `SystemClock.elapsedRealtimeNanos()`) |

---

## Modulstruktur

Single-Module-App (`app/`):

```
app/src/main/java/de/adv990de/obd2/
├── MainActivity.kt              # Compose-Host
├── Obd2Application.kt           # @HiltAndroidApp
├── ObdForegroundService.kt      # Hält den Prozess am Leben (Background), beendet die Sitzung beim Wegwischen
├── ObdServiceController.kt      # Dienst starten/stoppen, Sitzung beenden — eine Stelle
├── bluetooth/                   # ClassicBtConnection, SimulatorObdConnection,
│                                # ConnectionFactory, BluetoothDeviceScanner, SimulatedEcuDispatcher
├── obd/                         # ElmController, PidRegistry, PidParser, DtcParser, UnitConverter
├── data/                        # ObdRepository (@Singleton), SessionRepository, AppPreferences (DataStore),
│                                # StartupSeed (Startwerte fürs erste Bild: Theme, Kachel-Belegung),
│   └── db/                      # Room: AppDatabase, *Dao
├── ui/                          # Compose-Screens + ViewModels
│   ├── components/              # Wiederverwendbare Composables (LiveValueText/-Tile, Sparkline,
│   │                            # BottomNav, AmbientStatusRow = Datum/Uhrzeit/Außentemperatur)
│   ├── dashboard/               # Live-Werte
│   ├── dtc/                     # Fehlercodes: Aktuell | Historie (lokale Fehlerhistorie)
│   ├── freezeframe/             # Snapshot-Daten
│   ├── history/                 # Fahrtenverlauf (Papierkorb/Swipe mit Undo, Alle löschen, aktive Fahrt geschützt)
│   ├── loggingconfig/           # Aufzeichnungs-Einstellungen
│   ├── settings/                # gruppierte Einstellungsseite (Verbindung · Dashboard & Anzeige · Aufzeichnung · Fahrzeug · Info),
│   │                            # App-Logs, Getriebe-/Gangverhältnisse, Drehzahl-Blinkgrenze
│   ├── vehicleinfo/             # Mode 09 (VIN etc.)
│   ├── appinfo/                 # Über-Bildschirm
│   ├── licenses/                # Open-Source-Lizenzen (Katalog + Volltexte aus den Assets)
│   ├── permission/              # Runtime-Permissions Gate
│   ├── navigation/              # Compose-Navigation
│   ├── theme/                   # Material 3 Theme, Statusleisten-Symbole (SystemBars), System-Splash-Themes (SplashTheme)
│   └── weather/                 # WeatherViewModel (Polling nur bei sichtbarer Zeile) +
│                                # 30-Minuten-Halte-Logik (OutsideTemperatureHold)
├── location/                    # GpsSpeedSource (Geschwindigkeit aus dem GPS-Fix),
│                                # CurrentLocationProvider (Standort fürs Wetter: jüngster Cache-Fix ≤ 10 min, sonst aktiver Fix)
├── weather/                     # WeatherApi/OpenMeteoApi (HTTP ohne Zusatz-Library),
│                                # WeatherRepository
├── di/                          # Hilt-Module (AppModule, CrashReporterModule, LocationModule,
│                                # WeatherModule)
└── crash/                       # CrashReporter (Sentry-Wrapper), CrashReportingController (Schalter)
```

---

## Architektur-Highlights

### `ObdRepository` als Singleton
Die zentrale Repository-Schicht ist als Hilt-`@Singleton` markiert. Foreground-Service und alle ViewModels teilen sich die gleiche Instanz, kein Service-Binding nötig.

### Foreground Service hält Verbindung
`ObdForegroundService` läuft mit `foregroundServiceType="connectedDevice|location"` und einer persistenten Notification (Antippen öffnet die App, „Trennen“ beendet die Sitzung). Die Verbindung überlebt, wenn der User die App in den Hintergrund schickt — Aufzeichnung läuft dabei weiter. Polling, Aufzeichnung und Reconnect hängen am Dashboard-ViewModel, nicht am Dienst; der Dienst hält nur den Prozess am Leben. Deshalb gilt: **die Sitzung (Verbindung + Dienst + Benachrichtigung) lebt genau so lange, wie etwas die Messwerte verbraucht.** Wird die App aus der Übersicht weggewischt oder die Activity wirklich beendet (nicht nur für eine Drehung neu erzeugt), trennt die App und beendet den Dienst — wie „Beenden“; „Zurück“ am Root ab Android 12 schickt die App nur in den Hintergrund, die Aufzeichnung läuft weiter. Start, Stopp und Beenden der Sitzung liegen in einer Klasse (`ObdServiceController`); lehnt Android den Dienststart ab (ab API 31 im Hintergrund), bleibt die Verbindung bestehen und das App-Log nennt den Grund (QS-Bericht B1/B4).

### Room mit Foreign Keys
SQLite aktiviert Foreign-Key-Constraints standardmäßig **nicht**. Die App setzt `PRAGMA foreign_keys = ON` via `RoomDatabase.Callback` in `AppModule.kt` — sonst hätte `ON DELETE CASCADE` keine Wirkung.

### PID-Discovery vor Live-Polling
Vor dem ersten Live-Polling fragt `ObdRepository.discoverSupportedPids()` per Mode 01, welche PIDs das Steuergerät überhaupt antwortet. Das Dashboard filtert die konfigurierten Slots gegen diese Menge — unbekannte PIDs werden übersprungen, RPM und Kühlmitteltemperatur werden immer abgefragt (Dashboard-Logik benötigt sie).

### Wake-Lock nur wo nötig
`FLAG_KEEP_SCREEN_ON` wird ausschließlich im Dashboard-`DisposableEffect` gesetzt — solange das Dashboard sichtbar ist — unabhängig vom Verbindungsstatus (seit v1.17.6), und er wird beim Verlassen des Dashboards wieder freigegeben. Damit bleibt der Bildschirm während der Fahrt an, ohne die ganze App permanent wach zu halten.

### Volle Displayhelligkeit im Vordergrund
`MainActivity.onResume()`/`onPause()` setzen `Window.attributes.screenBrightness` auf `1f` bzw. `BRIGHTNESS_OVERRIDE_NONE` (System-Standard) zurück — ein reines Fenster-Attribut statt einer System-Einstellung, braucht daher keine `WRITE_SETTINGS`-Berechtigung. Gilt app-weit (jeder Screen, nicht nur das Dashboard) und ausschließlich solange die App im Vordergrund ist — für Sonnenlicht-Lesbarkeit auf dem Motorrad.

### Live-Wert-Typografie
Sich ändernde Werte (RPM, Speed, Temperatur, Spannung, VIN) werden in **IBM Plex Mono** mit OpenType-`tnum`-Feature gerendert — Ziffern haben konstante Glyph-Breite, springen also beim Update nicht horizontal. Kanonisches Composable: `LiveValueText` (`ui/components/`); für ad-hoc-Verwendung gibt es `TextStyle.asLiveValue()` als Extension. Restliche UI bleibt in Space Grotesk. Beide Schriften stehen unter der SIL Open Font License 1.1; die Lizenztexte liegen in `docs/licenses/` (`IBMPlexMono-OFL.txt`, `SpaceGrotesk-OFL.txt`). Die App zeigt alle ausgelieferten Open-Source-Komponenten samt Lizenztext unter App-Info → „Open-Source-Lizenzen“ (Katalog von Hand gepflegt, `LicenseCatalogTest` zwingt jede neue direkte Abhängigkeit zu einem Eintrag; die Texte in `docs/licenses/` sind zugleich die Assets der App). Volle Design-Doku in [`CLAUDE.md`](CLAUDE.md).

### Dashboard-Tiles & Status-Signale
Sensor-Kacheln nutzen `LiveValueTile` (`ui/components/LiveValueTile.kt`) — domain-agnostische Kachel mit Label oben links, Plex-Mono-Wert + Einheit unten rechts, optionaler Trend-Sparkline und 4 dp Status-Side-Bar. Der `TileStatus`-Enum (`Ok | Warning | Critical`) treibt die Akzentfarbe: `MaterialTheme.colorScheme.primary` (theme-getönt) für OK, `StatusColors.Warning` (`#EAB308`) und `StatusColors.Critical` (`#EF4444`) cross-theme. `SensorCard` ist der Adapter für die PID-Domäne (Einheit-Konvertierung, NaN-Handling). Status-Tokens leben in `ui/theme/StatusColors.kt`.

---

## Release-Ablauf

`scripts/release.sh` hält den Ablauf fest (und warum er so ist): **erst Version bumpen und committen, dann bauen** — das 1.23.0-APK wurde aus
einem noch nicht committeten Bump gebaut, seine eingebettete Revision war nicht die des Tags (QS-Bericht B32). Das Skript

1. prüft vorab: sauberer Arbeitsbaum, README („Aktuelle Version“) und oberster Release-Notes-Eintrag nennen die Version aus `app/build.gradle.kts`, der Tag existiert noch nicht, Keystore und `~/.gradle/gradle.properties` sind nur für den Eigentümer lesbar;
2. führt die JVM-Tests aus und baut `assembleRelease`;
3. prüft, dass die im APK eingebettete Revision **HEAD** ist, die Signatur (`apksigner`) und dass das Zertifikat dem festgehaltenen Fingerabdruck entspricht;
4. archiviert `mapping.txt`, die APK-Prüfsumme und die Revision außerhalb des Repos (`$RELEASE_ARCHIVE_DIR`, Standard `~/obd2-release-archive/<version>/`; die `mapping.txt` hat ~45 MB);
5. mit `--install` installiert es auf das angeschlossene Gerät und prüft den `versionCode`.

`scripts/release.sh --check` macht nur die Vorprüfung. Der **Smoke-Test** am entsperrten Gerät ist `python3 -u scripts/smoke.py <versionName> <versionCode>`
(uiautomator: Start, Reiter, Beenden-Dialog, App-Info mit Version und Build, Logcat ohne Absturz; er prüft keinen echten Adapter). Mit `--simulator` (Debug-Build, Berechtigungen erteilt; Paket dann `de.adv990de.obd2.debug`) prüft er zusätzlich den Datenfluss gegen den ECU-Simulator: verbinden, Werte im Dashboard, „Trennen“ und danach kein Reconnect.
**Taggen und Pushen bleiben Handarbeit** nach dem Smoke-Test: `git tag vX.Y.Z && git push origin main vX.Y.Z`. Der Test `ReleaseDocsConsistencyTest` hält Version und versionCode in `build.gradle.kts`, README und
`RELEASE_NOTES.md` zusammen.

---

## Berechtigungen

Das Manifest deklariert die folgenden Berechtigungen. **Zur Laufzeit** fragt die App nur Bluetooth und Benachrichtigungen beim Start an
(`PermissionGate`; Bluetooth ist Pflicht, Benachrichtigungen sind optional) sowie Standort erst beim Einschalten der jeweiligen Einstellung.
Die übrigen sind „normale“ Berechtigungen ohne Dialog.

| Permission | API-Range | Zweck |
|---|---|---|
| `BLUETOOTH_CONNECT` | 31+ | Verbindung zu einem gekoppelten Adapter herstellen (kein `BLUETOOTH_SCAN` seit 1.24.9: die App sucht nicht) |
| `BLUETOOTH` | ≤30 | Legacy-Bluetooth (`BLUETOOTH_ADMIN` entfiel mit dem BLE-Scan) |
| `ACCESS_FINE_LOCATION` | alle | Quelle der Geschwindigkeit (opt-in, wird erst beim Einschalten angefragt) und Standort für die Außentemperatur; für Bluetooth wird kein Standort gebraucht |
| `ACCESS_COARSE_LOCATION` | alle | zusammen mit FINE angefragt (Android 12+ lässt den Nutzer zwischen genau und ungefähr wählen); genügt allein für die Außentemperatur |
| `ACCESS_BACKGROUND_LOCATION` | 29+ | optional, wird einmal angefragt, nachdem „Geschwindigkeit per GPS“ eingeschaltet wurde: hält die GPS-Geschwindigkeit am Leben, solange `ObdForegroundService` ohne sichtbare Activity läuft |
| `POST_NOTIFICATIONS` | 33+ | Foreground-Service-Notification (optional: ohne sie läuft der Dienst, nur die Benachrichtigung fehlt) |
| `FOREGROUND_SERVICE` | alle | Foreground Service (normale Berechtigung, kein Dialog) |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | 34+ | Service-Type-Required ab Android 14 (normale Berechtigung) |
| `FOREGROUND_SERVICE_LOCATION` | 34+ | Service-Typ `location` für die GPS-Geschwindigkeit im Hintergrund (normale Berechtigung; der Typ wird nur angefordert, wenn auch `ACCESS_BACKGROUND_LOCATION` erteilt ist) |
| `INTERNET` | alle | ausschließlich die Wetterabfrage (Open-Meteo, normale Berechtigung); Sentry bleibt ohne DSN inaktiv |

**Standort für die Außentemperatur:** Genau (FINE) oder ungefähr (COARSE) genügt. Angefragt wird
beim Einschalten von „Navigationsleiste beim Fahren ausblenden" — unabhängig von „Geschwindigkeit per
GPS" (dort ist FINE Pflicht) — sowie nachträglich beim nächsten Öffnen der Einstellungen, für Nutzer
bei denen der Schalter schon aus einer früheren App-Version an ist. Dieser Nachtrag erscheint
höchstens einmal pro Installation (persistierter Guard, dieselbe Form wie bei der
Hintergrund-Standort-Eskalation) — kein wiederholtes Nachfragen bei jedem weiteren Settings-Besuch.
Anders als beim GPS-Schalter folgt die Einstellung immer dem Schalter: ohne Berechtigung fehlt in der
Statuszeile nur die Temperatur, Datum und Uhrzeit bleiben. Eine schon vorher erteilte
Standortberechtigung (z. B. über den GPS-Schalter) wird mitgenutzt.
Ein vom System zwischengespeicherter Standort zählt nur, wenn er höchstens 10 Minuten alt ist (der jüngste von GPS und Netzwerk); sonst fordert die App einen
frischen Fix an, und gelingt auch der nicht, fehlt die Temperatur — lieber keine Anzeige als eine für die falsche Gegend.

**Datenschutz:** Für die Außentemperatur schickt die App die Koordinaten an einen Dritten —
Open-Meteo (`api.open-meteo.com`) —, auf zwei Nachkommastellen gerundet (rund 1,1 km; Open-Meteo
rechnet ohnehin auf einem Raster dieser Größenordnung). Das geschieht nur, solange die Statuszeile
sichtbar ist (Fahrzeug in Bewegung, Einstellung an, App im Vordergrund), dann einmal pro Minute —
und sofort, wenn die Zeile nach einer Pause von mehr als 5 Sekunden wieder erscheint.
Dafür deklariert die App `INTERNET` (normale Berechtigung, kein Dialog; das Sentry-SDK bringt sie
ohnehin schon mit). Die Anfrage nennt die App im `User-Agent` (`OBD2Monitor/<Version>`, sonst nichts —
keine Geräte- oder Nutzerkennung), und die Antwort wird höchstens 16 KiB weit gelesen (die echte ist rund
400 Byte groß): ein fehlerhafter oder bösartiger Server kostet die App weder Speicher noch Daten.

---

## Konfiguration

### Sentry (optional)
Ist beim Bauen die Env-Variable bzw. Gradle-Property `SENTRY_DSN` gesetzt (Manifest-Placeholder in `app/build.gradle.kts`), kann die App Fehlerberichte an Sentry senden. **Ohne DSN ist Sentry nicht aktiv** (kein Netzwerktraffic, auch kein Schalter in der App) — so sind die bisherigen Releases gebaut.

Mit DSN gilt zur Laufzeit: Sentry startet **nicht von selbst** (`io.sentry.auto-init=false`), sondern erst, nachdem die Einstellung gelesen ist; die Einstellung **„Absturzberichte senden“** (Info & Wartung, Standard an) startet und stoppt es sofort. Gesendet werden bei einem Absturz oder unerwarteten Fehler technische Fehlerdetails, Gerätemodell und Android-Version. Die App-Info zeigt, ob Absturzberichte an, aus oder in dieser Version nicht enthalten sind. Eingebunden ist nur `sentry-android-core` — ohne Native-Handler und Replay (vorher lagen `libsentry.so`/`libsentry-android.so` in drei Architekturen im APK, obwohl die App keinen eigenen Native-Code hat). Bekannte Grenze: Ein Absturz in den ersten Millisekunden nach dem Prozessstart, bevor die Einstellung gelesen ist, wird nicht gemeldet.

### CSV-Export einer Fahrt
Ein Export (Verlauf → Teilen) schreibt `session_<Fahrt>_<UTC-Zeit>.csv` in den Cache — je Export eine eigene Datei, nie eine überschriebene. Spalten: `timestamp` (Millisekunden seit 1970), `pid` (z. B. `010C`), `value` (metrisch, Punkt als Dezimaltrennzeichen), `name` (stabiler englischer PID-Name, z. B. `RPM`), `source` (`ECU` oder `GPS`). Die ersten drei Spalten sind unverändert; `name` und `source` stehen hinten, damit Auswertungen nach Position weiterlaufen. `source` unterscheidet die Geschwindigkeit des Fahrzeugs von der des GPS (beide tragen die PID `010D`); bei Fahrten, die vor Version 1.25.0 aufgezeichnet wurden, bleibt das Feld leer, weil ihre Herkunft nicht bekannt ist. Die Zeilen stehen in Aufzeichnungsreihenfolge und werden ohne Sortierlauf gestreamt (konstanter Speicher auch bei langen Fahrten). Bekannte Grenze: Excel macht beim Doppelklick-Öffnen aus der Spalte `pid` Zahlen wie `0105` → 105 (Anführungszeichen verhindern das nicht); die Spalte `name` identifiziert den Wert trotzdem, oder die Datei als Text importieren.

### Benutzerseitige Einstellungen
Persistiert via Jetpack DataStore; eine Seite, gruppiert wie in der App:
- **Verbindung:** gekoppeltes Gerät (Auto-Connect zum letzten Gerät), „Alle gekoppelten Geräte“ anzeigen, Reconnect bei eingefrorenen Werten (OBD-Watchdog), Reconnect-Basisverzögerung
- **Dashboard & Anzeige:** Farbschema (drei Cockpit-Schemata plus Standard mit Hell/Dunkel/System), Einheiten (metrisch/imperial), Navigationsleiste beim Fahren ausblenden (an ihrer Stelle Datum, Uhrzeit und Außentemperatur), Geschwindigkeit per GPS (wenn das Fahrzeug keine liefert), Drehzahl-Blinkgrenze mit dynamischer Schwelle, Getriebe (Radumfang, Primär-/Sekundärübersetzung, Übersetzung je Gang für die virtuelle Ganganzeige)
- **Aufzeichnung:** „Nur Fahrten mit DTCs speichern“, „Nur vom Fahrzeug gemeldete Werte speichern“ (verwirft Werte, die das Steuergerät beim Verbinden nicht als unterstützt gemeldet hat, auch die GPS-Geschwindigkeit, wenn das Fahrzeug keine meldet), „Aufzeichnung filtern“ (gespeichert wird nur, was die App ohnehin misst: Kacheln, Drehzahl, Kühlmittel; dort wählst du Werte ab — ein später ergänzter PID wird also aufgezeichnet, sofern er gemessen wird)
- **Fahrzeug:** Fahrzeuginfo (FIN)
- **Info & Wartung:** „Absturzberichte senden“ (nur in einer Version mit eingebautem Sentry-DSN), App-Logs, App-Info (zeigt u. a. den Stand der Absturzberichte; von dort die Open-Source-Lizenzen)
- Die Dashboard-Slot-Belegung (welche PIDs in welche Kachel) steht ebenfalls im DataStore und wird per Long-Press auf eine Kachel geändert.

Zu den beiden Zeitwerten der Verbindung:
- **OBD-Watchdog:** nach wie vielen Sekunden ohne erfolgreiche Messung ein Reconnect erzwungen wird, sobald der Adapter gar nicht mehr (Timeout) oder nur noch unverwertbar antwortet; reines NO DATA (z. B. Zündung aus) löst ihn nie aus und startet die Frist neu — Auswahl 10 / 20 / 30 / 60 / 120 s, Standard 20 s (früher frei eingegebene Werte werden beim Lesen auf die nächste Auswahl gesetzt, z. B. 45 s → 60 s)
- **Reconnect-Basisverzögerung:** Wartezeit vor dem ersten Reconnect-Versuch, jeder weitere verdoppelt sie (5 Versuche) — Auswahl 3 / 10 / 30 s, Standard 3 s; die Einstellungen zeigen die resultierenden Wartezeiten (z. B. 3 · 6 · 12 · 24 · 48 s, insgesamt 1:33 min)

Die Getriebe-Eingaben haben plausible Grenzen (z. B. Radumfang 1000–4000 mm; „2.096“ wird abgelehnt statt als 2,096 mm gespeichert); `0` bei einem Gang schaltet ihn ab, falls das Getriebe weniger Gänge hat.

Die Fehlercode-Seite behauptet nur „Keine Fehlercodes“, wenn das Steuergerät positiv geantwortet hat; bleibt die Antwort aus (NO DATA) oder meldet der Adapter einen Busfehler, sagt sie das, mit „Erneut versuchen“.

„In Bewegung“ heißt: ab 5 km/h, bis die Geschwindigkeit unter 3 km/h fällt — GPS-Rauschen im Stand schaltet das Hauptmenü nicht um. Liefert das Steuergerät bei bestehender Verbindung 10 s lang keinen Messwert (z. B. Zündung aus), zeigen die Kacheln „--“ und das Band „Keine aktuellen Werte“ statt alter Zahlen.

Fehlt die Standortberechtigung (entzogen, „Nur dieses Mal“ abgelaufen oder nur „ungefähr“ erteilt), steht unter dem GPS-Schalter und unter „Navigationsleiste beim Fahren ausblenden“ ein Hinweis mit Knopf zu den App-Einstellungen; wird sie dort erteilt, läuft GPS-Speed ohne Neustart an. Nach dem Verbinden zeigt das Dashboard sofort „Verbunden · warte auf Werte“.

Trennt der Nutzer selbst („Trennen“ in der Benachrichtigung oder den Einstellungen, „Beenden“), verbindet die App nicht von selbst wieder — auch nicht, wenn gerade eine Wiederverbindung läuft; sie bricht dann sofort ab. Nur ein verlorener Link löst Wiederverbindungs-Versuche aus.

Ist die Einstellungsdatei beschädigt oder nicht lesbar, startet die App mit den Standardwerten (statt bei jedem Start abzustürzen); die bisherigen Einstellungen gehen dann verloren.

---

## Lokalisierung

- Deutsch (`values-de/`) — Default für deutsche Locale
- Englisch (`values/`) — Fallback

Alle UI-Strings werden über `stringResource()` resolved. ViewModels nutzen die `UiText`-Sealed-Klasse, damit Texte testbar bleiben (kein direkter `Context`-Zugriff).

---

## Tech-Stack

| Bereich | Bibliothek / Version |
|---|---|
| Sprache | Kotlin 2.4.21 |
| Build | Android Gradle Plugin 9.4.1 (built-in Kotlin) · Gradle 9.6.1 · KSP 2.3.11 |
| UI | Jetpack Compose (BOM 2026.09.00, Compose 1.12.x) · Material 3 (1.4.0) · Material Icons 1.7.8 (explizit) |
| Typografie | Space Grotesk (UI-Text) · IBM Plex Mono (Live-Werte, tabular numerals) |
| Navigation | androidx.navigation:navigation-compose 2.10.2 |
| DI | Hilt 2.60.1 |
| Persistenz | Room 2.8.5 (mit Paging-3-Integration) · DataStore Preferences 1.2.1 |
| Coroutines | kotlinx-coroutines 1.11.0 |
| Lifecycle | androidx.lifecycle 2.11.0 |
| Crash-Reporting | Sentry Android 8.60.0 |
| Tests | JUnit 4 · MockK 1.14.11 · Compose UI Test · Room Testing · Hilt Testing |

---

## Release-Historie

Siehe [`RELEASE_NOTES.md`](RELEASE_NOTES.md) — chronologische Änderungen seit Version 1.0.

---

## Verzeichnis-Konventionen

| Pfad | Inhalt |
|---|---|
| `app/src/main/` | Produktiv-Code |
| `app/src/test/` | JVM-Unit-Tests (1490) |
| `app/src/androidTest/` | Instrumented Tests (332, Hilt + Compose + Room) |
| `app/schemas/` | **Im Repo** — Room-Schema-Exports für Migrationstests |
| `docs/plans/` | Historische Implementierungs-Pläne (Dokumentation, nicht Build-Input) |
| `gradle/libs.versions.toml` | Version-Catalog (Single Source of Truth für Dependencies) |

---

## Bekannte Einschränkungen

- **Kein iOS-Port** geplant.
- **CAN-Bus direkt** wird nicht unterstützt — nur OBD2-PIDs (Mode 01, 02, 03, 07, 09) — die App liest nur und schreibt nie ins Fahrzeug (kein Mode 04).
- **Multi-Frame-Antworten** (Mode 09 VIN, langwierige DTC-Listen) werden gelesen, aber die ELM327-Adapter müssen den `AT CAF1`-Modus unterstützen.
- **Nur klassisches Bluetooth (SPP):** Ein Adapter, der ausschließlich BLE spricht, lässt sich nicht verwenden. Ein früher gespeichertes BLE-Gerät wird beim Start als unbekannt behandelt (kein Auto-Connect, das Gerät einmal neu wählen).
