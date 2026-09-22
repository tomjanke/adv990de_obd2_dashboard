# OBD2 Monitor

Android-App zur Echtzeit-Diagnose über einen OBD2-Adapter (ELM327 via BLE oder klassisches Bluetooth). Liest Live-Sensordaten, zeigt Fehlercodes (DTCs), speichert Fahrt-Sessions lokal und visualisiert konfigurierbare Dashboards in Jetpack Compose.

> Aktuelle Version: **1.14.1** (versionCode 42) · minSdk **26** (Android 8.0) · targetSdk **36** (Android 16) · compileSdk **36**

---

## Funktionen

- **Live-Dashboard** mit frei konfigurierbaren PID-Kacheln (Drehzahl, Geschwindigkeit, Kühlmitteltemperatur, Motorlast, Kraftstoffstand, etc.)
- **Automatische PID-Discovery** — die App fragt das Steuergerät, welche PIDs überhaupt unterstützt werden, und filtert die Live-Abfrage entsprechend
- **DTC-Lesen & Löschen** (Diagnostic Trouble Codes) mit menschenlesbaren Beschreibungen
- **Freeze Frame** — Sensorwerte zum Zeitpunkt eines gespeicherten Fehlers
- **Fahrzeug-Info** (VIN, ECU-Name, Kalibrier-IDs via Mode 09)
- **Geschwindigkeit per GPS** als Rückfallebene, wenn das Fahrzeug PID 010D nicht liefert — bei Motorrädern der Normalfall; läuft mit erteilter Hintergrund-Berechtigung auch weiter, wenn die App minimiert ist
- **Fahrt-Historie** — Sessions werden in Room persistiert, einzeln löschbar, mit CSV-Export
- **Foreground Service** — Verbindung bleibt im Hintergrund aktiv (persistente Benachrichtigung mit „Disconnect"-Action)
- **Connection-Backends**:
  - **BLE** (ELM327-BLE-Adapter, z.B. Vgate, OBDLink CX)
  - **Klassisches Bluetooth (RFCOMM)** für ältere ELM327-Clones
  - **Simulator** — synthetischer ECU für Entwicklung und UI-Tests
- **App-Logs** — eigene Log-Persistenz (Room) mit 30-Tage-/1000-Einträge-Limit, durchsuchbar im Settings-Bereich
- **Material 3** UI mit Light/Dark-Theme, deutsch und englisch lokalisiert
- **Crash-Reporting** über Sentry (optional, deaktivierbar)

---

## Hardware-Voraussetzungen

- Android-Gerät mit **Bluetooth Classic + BLE**, ab Android 8.0
- **ELM327-kompatibler OBD2-Adapter** — getestet u.a. mit Vgate iCar Pro BLE und OBDLink CX
- Fahrzeug mit OBD2-Buchse (ab Bj. ~2001 in EU, ~1996 in US verpflichtend)

---

## Build & Run

### Voraussetzungen
- JDK **17** (Android Gradle Plugin 9.3 setzt mindestens JVM 17 voraus)
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

### Debug- und Release-Build parallel

Der Debug-Build trägt `applicationIdSuffix = ".debug"` und heißt im Launcher **BOM Debug**;
seine Version endet auf `-debug`. Beide Varianten lassen sich damit gleichzeitig installieren,
jede mit eigenen Daten — kein Deinstallieren, kein Datenverlust beim Wechsel.

Optisch unterscheiden sie sich am Launcher-Icon: Der Release-Build behält den Teal-Hintergrund
(`#006874`), der Debug-Build ist amber (`#B45309`). Überschrieben wird dafür allein
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

Die Datei `keystore.jks` muss im Projekt-Root liegen (per `.gitignore` ausgeschlossen).

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
# JVM Unit Tests (346 Tests, < 30 Sekunden)
./gradlew :app:testDebugUnitTest

# Instrumented Tests (benötigen Gerät/Emulator)
./gradlew :app:connectedDebugAndroidTest
```

| Test-Layer | Frameworks | Anzahl |
|---|---|---|
| JVM Unit Tests | JUnit 4 + MockK + kotlinx-coroutines-test | 346 |
| Compose UI Tests | androidx.compose.ui.test + Espresso 3.7.0 + Hilt (`HiltTestRunner`) | 12 (Dashboard, DTC, Settings, Freeze Frame) |
| Room Integration Tests | Room in-memory + `androidx.test.ext.junit` | 16 (AppLogDao, SensorReadingDao, SessionDao) |
| End-to-End über den ECU-Simulator | echtes `ObdRepository` + `SimulatorObdConnection` | 7 (Session, Discovery, Messwert-Strom, Dashboard) |
| Service Tests | `HiltAndroidRule` + `GrantPermissionRule` | 3 (ObdForegroundService) |

---

## Modulstruktur

Single-Module-App (`app/`):

```
app/src/main/java/de/adv990de/obd2/
├── MainActivity.kt              # Compose-Host
├── Obd2Application.kt           # @HiltAndroidApp
├── ObdForegroundService.kt      # Hält ObdRepository am Leben (Background)
├── bluetooth/                   # BleConnection, ClassicBtConnection, SimulatorObdConnection,
│                                # ConnectionFactory, BluetoothDeviceScanner, SimulatedEcuDispatcher
├── obd/                         # ElmController, PidRegistry, PidParser, DtcParser, UnitConverter
├── data/                        # ObdRepository (@Singleton), SessionRepository, AppPreferences (DataStore),
│   └── db/                      # Room: AppDatabase, *Dao
├── ui/                          # Compose-Screens + ViewModels
│   ├── dashboard/               # Live-Werte
│   ├── dashboardconfig/         # PID-Slot-Belegung
│   ├── dtc/                     # Fehlercode-Reader
│   ├── freezeframe/             # Snapshot-Daten
│   ├── history/                 # Fahrt-Sessions
│   ├── loggingconfig/           # Aufzeichnungs-Einstellungen
│   ├── settings/                # Verbindung, Display, App-Logs
│   ├── vehicleinfo/             # Mode 09 (VIN etc.)
│   ├── appinfo/                 # Über-Bildschirm
│   ├── permission/              # Runtime-Permissions Gate
│   ├── navigation/              # Compose-Navigation
│   └── theme/                   # Material 3 Theme
├── location/                    # GpsSpeedSource (Geschwindigkeit aus dem GPS-Fix)
├── di/                          # Hilt-Module (AppModule, CrashReporterModule)
└── crash/                       # CrashReporter (Sentry-Wrapper)
```

---

## Architektur-Highlights

### `ObdRepository` als Singleton
Die zentrale Repository-Schicht ist als Hilt-`@Singleton` markiert. Foreground-Service und alle ViewModels teilen sich die gleiche Instanz, kein Service-Binding nötig.

### BLE-Writes serialisiert
`BleConnection.kt` nutzt einen `Semaphore(0)` als Mutex für BLE-Writes: jeder Write blockiert, bis `onCharacteristicWrite` feuert (5 s Timeout). Verhindert verlorene Pakete bei schnellen Sequenzen.

### Foreground Service hält Verbindung
`ObdForegroundService` läuft mit `foregroundServiceType="connectedDevice"` und einer persistenten Notification. Die Verbindung überlebt, wenn der User die App in den Hintergrund schickt — Aufzeichnung läuft weiter.

### Room mit Foreign Keys
SQLite aktiviert Foreign-Key-Constraints standardmäßig **nicht**. Die App setzt `PRAGMA foreign_keys = ON` via `RoomDatabase.Callback` in `AppModule.kt` — sonst hätte `ON DELETE CASCADE` keine Wirkung.

### PID-Discovery vor Live-Polling
Vor dem ersten Live-Polling fragt `ObdRepository.discoverSupportedPids()` per Mode 01, welche PIDs das Steuergerät überhaupt antwortet. Das Dashboard filtert die konfigurierten Slots gegen diese Menge — unbekannte PIDs werden übersprungen, RPM und Kühlmitteltemperatur werden immer abgefragt (Dashboard-Logik benötigt sie).

### Wake-Lock nur wo nötig
`FLAG_KEEP_SCREEN_ON` wird ausschließlich im Dashboard-`DisposableEffect` gesetzt — und nur, wenn `isConnected = true`. Damit bleibt der Bildschirm während der Fahrt an, ohne die ganze App permanent wach zu halten.

### Live-Wert-Typografie
Sich ändernde Werte (RPM, Speed, Temperatur, Spannung, VIN) werden in **IBM Plex Mono** mit OpenType-`tnum`-Feature gerendert — Ziffern haben konstante Glyph-Breite, springen also beim Update nicht horizontal. Kanonisches Composable: `LiveValueText` (`ui/components/`); für ad-hoc-Verwendung gibt es `TextStyle.asLiveValue()` als Extension. Restliche UI bleibt in Space Grotesk. Volle Design-Doku in [`CLAUDE.md`](CLAUDE.md).

### Dashboard-Tiles & Status-Signale
Sensor-Kacheln nutzen `LiveValueTile` (`ui/components/LiveValueTile.kt`) — domain-agnostische Kachel mit Label oben links, Plex-Mono-Wert + Einheit unten rechts, optionaler Trend-Sparkline und 4 dp Status-Side-Bar. Der `TileStatus`-Enum (`Ok | Warning | Critical`) treibt die Akzentfarbe: `MaterialTheme.colorScheme.primary` (theme-getönt) für OK, `StatusColors.Warning` (`#F59E0B`) und `StatusColors.Critical` (`#EF4444`) cross-theme. `SensorCard` ist der Adapter für die PID-Domäne (Einheit-Konvertierung, NaN-Handling). Status-Tokens leben in `ui/theme/StatusColors.kt`.

---

## Berechtigungen

Die App fordert zur Laufzeit (via `PermissionGate`) an:

| Permission | API-Range | Zweck |
|---|---|---|
| `BLUETOOTH_SCAN` | 31+ | BLE/Classic-Scan |
| `BLUETOOTH_CONNECT` | 31+ | Verbindung herstellen |
| `BLUETOOTH` / `BLUETOOTH_ADMIN` | ≤30 | Legacy-Bluetooth |
| `ACCESS_FINE_LOCATION` | alle | BLE-Scan vor Android 12; ab 1.10.0 zusätzlich Quelle der Geschwindigkeit (opt-in, wird erst beim Einschalten angefragt) |
| `ACCESS_COARSE_LOCATION` | alle | zusammen mit FINE angefragt (Android 12+ lässt den Nutzer zwischen genau und ungefähr wählen) |
| `POST_NOTIFICATIONS` | 33+ | Foreground-Service-Notification |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | 34+ | Service-Type-Required ab Android 14 |

---

## Konfiguration

### Sentry (optional)
Wenn die Env-Variable bzw. Gradle-Property `SENTRY_DSN` gesetzt ist, wird der Crash-Reporter aktiviert. Ohne DSN bleibt Sentry inaktiv (kein Netzwerktraffic). Konfiguriert über Manifest-Placeholder in `app/build.gradle.kts`.

### Benutzerseitige Einstellungen
Persistiert via Jetpack DataStore:
- Auto-Connect zum letzten Gerät
- Dashboard-Slot-Belegung (welche PIDs in welche Kachel)
- Geschwindigkeit per GPS, wenn das Fahrzeug keine liefert
- Anzeige-Einheiten (metrisch/imperial)
- Aufzeichnungs-Modus (immer / nur bei Fahrt / aus)
- App-Log-Verbosity

---

## Lokalisierung

- Deutsch (`values-de/`) — Default für deutsche Locale
- Englisch (`values/`) — Fallback

Alle UI-Strings werden über `stringResource()` resolved. ViewModels nutzen die `UiText`-Sealed-Klasse, damit Texte testbar bleiben (kein direkter `Context`-Zugriff).

---

## Tech-Stack

| Bereich | Bibliothek / Version |
|---|---|
| Sprache | Kotlin 2.4.10 |
| Build | Android Gradle Plugin 9.3.0 (built-in Kotlin) · Gradle 9.5.0 · KSP 2.3.11 |
| UI | Jetpack Compose (BOM 2026.05.00, Compose 1.11.x) · Material 3 (1.4.0) · Material Icons 1.7.8 (explizit) |
| Typografie | Space Grotesk (UI-Text) · IBM Plex Mono (Live-Werte, tabular numerals) |
| Navigation | androidx.navigation:navigation-compose 2.8.5 |
| DI | Hilt 2.60.1 |
| Persistenz | Room 2.8.5 (mit Paging-3-Integration) · DataStore Preferences 1.1.1 |
| Coroutines | kotlinx-coroutines 1.9.0 |
| Lifecycle | androidx.lifecycle 2.8.7 |
| Crash-Reporting | Sentry Android 8.29.0 |
| Tests | JUnit 4 · MockK 1.13.12 · Compose UI Test · Room Testing · Hilt Testing |

---

## Release-Historie

Siehe [`RELEASE_NOTES.md`](RELEASE_NOTES.md) — chronologische Änderungen seit Version 1.0.

---

## Verzeichnis-Konventionen

| Pfad | Inhalt |
|---|---|
| `app/src/main/` | Produktiv-Code |
| `app/src/test/` | JVM-Unit-Tests (346) |
| `app/src/androidTest/` | Instrumented Tests (37, Hilt + Compose + Room) |
| `app/schemas/` | **Im Repo** — Room-Schema-Exports für Migrationstests |
| `docs/plans/` | Historische Implementierungs-Pläne (Dokumentation, nicht Build-Input) |
| `gradle/libs.versions.toml` | Version-Catalog (Single Source of Truth für Dependencies) |

---

## Bekannte Einschränkungen

- **Kein iOS-Port** geplant.
- **CAN-Bus direkt** wird nicht unterstützt — nur OBD2-PIDs (Mode 01, 02, 03, 04, 09).
- **Multi-Frame-Antworten** (Mode 09 VIN, langwierige DTC-Listen) werden gelesen, aber die ELM327-Adapter müssen den `AT CAF1`-Modus unterstützen.
- **Klassisches Bluetooth** ist ab Android 12 deprecated für neue Adapter — funktioniert noch, aber für neue Hardware-Käufe BLE-Adapter bevorzugen.
