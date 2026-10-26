# Testabdeckung

> Erzeugt von `scripts/coverage.sh` — nicht von Hand ändern. Neu messen: `scripts/coverage.sh` (JVM- und Geräte-Tests, rund 17 Minuten, Telefon wach und entsperrt) oder `scripts/coverage.sh --jvm-only` (wenige Minuten, nur die JVM-Tests).

**Stand:** 2026-10-26 · **Version:** 1.44.6 (versionCode 144) · **Messweise:** JVM- und Geräte-Tests (Debug-Build, JaCoCo)

**Gesamt: 97,3 % der Zeilen (11156 von 11470 Zeilen)**, 86,7 % der Zweige (4769 von 5502).

Tests im Lauf:

- JVM-Tests: 2925 (0 fehlgeschlagen, 0 übersprungen)
- Geräte-Tests: 819 (0 fehlgeschlagen, 0 übersprungen)

## Je Paket

| Paket | Zeilen | Zeilen % | Zweige % |
|---|---|---|---|
| `de.adv990de.obd2` | 95 / 184 | 51,6 % | 65,3 % |
| `de.adv990de.obd2.alarm` | 349 / 353 | 98,9 % | 90,2 % |
| `de.adv990de.obd2.bluetooth` | 164 / 170 | 96,5 % | 85,5 % |
| `de.adv990de.obd2.crash` | 27 / 35 | 77,1 % | 93,3 % |
| `de.adv990de.obd2.data` | 931 / 938 | 99,3 % | 92,5 % |
| `de.adv990de.obd2.data.db` | 25 / 25 | 100,0 % | 100,0 % |
| `de.adv990de.obd2.data.db.entity` | 42 / 42 | 100,0 % | – |
| `de.adv990de.obd2.di` | 3 / 23 | 13,0 % | – |
| `de.adv990de.obd2.fuel` | 167 / 168 | 99,4 % | 98,7 % |
| `de.adv990de.obd2.location` | 118 / 125 | 94,4 % | 98,6 % |
| `de.adv990de.obd2.logbook` | 480 / 482 | 99,6 % | 96,6 % |
| `de.adv990de.obd2.obd` | 374 / 375 | 99,7 % | 91,2 % |
| `de.adv990de.obd2.obd.model` | 26 / 26 | 100,0 % | 100,0 % |
| `de.adv990de.obd2.terrain` | 206 / 209 | 98,6 % | 87,1 % |
| `de.adv990de.obd2.ui` | 36 / 37 | 97,3 % | 90,0 % |
| `de.adv990de.obd2.ui.appinfo` | 130 / 131 | 99,2 % | 47,6 % |
| `de.adv990de.obd2.ui.components` | 1172 / 1183 | 99,1 % | 78,8 % |
| `de.adv990de.obd2.ui.dashboard` | 2180 / 2199 | 99,1 % | 91,1 % |
| `de.adv990de.obd2.ui.dtc` | 262 / 263 | 99,6 % | 71,8 % |
| `de.adv990de.obd2.ui.freezeframe` | 88 / 88 | 100,0 % | 72,2 % |
| `de.adv990de.obd2.ui.fuel` | 5 / 5 | 100,0 % | – |
| `de.adv990de.obd2.ui.history` | 460 / 478 | 96,2 % | 73,7 % |
| `de.adv990de.obd2.ui.licenses` | 76 / 77 | 98,7 % | 83,3 % |
| `de.adv990de.obd2.ui.logbook` | 1463 / 1493 | 98,0 % | 81,3 % |
| `de.adv990de.obd2.ui.navigation` | 66 / 66 | 100,0 % | 100,0 % |
| `de.adv990de.obd2.ui.permission` | 100 / 111 | 90,1 % | 83,3 % |
| `de.adv990de.obd2.ui.settings` | 1683 / 1751 | 96,1 % | 80,7 % |
| `de.adv990de.obd2.ui.theme` | 236 / 241 | 97,9 % | 59,5 % |
| `de.adv990de.obd2.ui.trips` | 38 / 38 | 100,0 % | 100,0 % |
| `de.adv990de.obd2.ui.vehicleinfo` | 67 / 67 | 100,0 % | 80,6 % |
| `de.adv990de.obd2.ui.weather` | 25 / 25 | 100,0 % | 100,0 % |
| `de.adv990de.obd2.weather` | 62 / 62 | 100,0 % | 93,3 % |

## Dateien mit der niedrigsten Abdeckung (ab 20 Zeilen)

| Datei | Zeilen | Zeilen % |
|---|---|---|
| `de/adv990de/obd2/MainActivity.kt` | 13 / 44 | 29,5 % |
| `de/adv990de/obd2/ObdForegroundService.kt` | 24 / 67 | 35,8 % |
| `de/adv990de/obd2/ui/settings/SettingsScreen.kt` | 195 / 244 | 79,9 % |
| `de/adv990de/obd2/crash/CrashReportingController.kt` | 23 / 28 | 82,1 % |
| `de/adv990de/obd2/ui/permission/PermissionGate.kt` | 68 / 79 | 86,1 % |
| `de/adv990de/obd2/ui/settings/AppLogsScreen.kt` | 137 / 152 | 90,1 % |
| `de/adv990de/obd2/ui/logbook/LogbookScreen.kt` | 214 / 234 | 91,5 % |
| `de/adv990de/obd2/location/AndroidCurrentLocationProvider.kt` | 36 / 39 | 92,3 % |
| `de/adv990de/obd2/ui/history/HistoryScreen.kt` | 199 / 215 | 92,6 % |
| `de/adv990de/obd2/location/GpsSpeedSource.kt` | 28 / 30 | 93,3 % |
| `de/adv990de/obd2/ui/dashboard/DashboardScreen.kt` | 144 / 154 | 93,5 % |
| `de/adv990de/obd2/terrain/TerrainSource.kt` | 30 / 32 | 93,8 % |
| `de/adv990de/obd2/ObdServiceController.kt` | 58 / 61 | 95,1 % |
| `de/adv990de/obd2/location/AndroidLocationSpeedProvider.kt` | 40 / 42 | 95,2 % |
| `de/adv990de/obd2/ui/dashboard/ValuesState.kt` | 20 / 21 | 95,2 % |

## Klassen, die nicht zugeordnet werden konnten

Der Bytecode dieser Klassen war zur Laufzeit ein anderer als der im Bericht (Hilt ändert `@AndroidEntryPoint`-Klassen beim Bauen). Sie sind nicht zugeordnet und zählen als nicht abgedeckt — die Zahl oben ist um ihren Anteil zu niedrig:

- `de.adv990de.obd2.ObdForegroundService`

## Was die Zahl aussagt und was nicht

- Es ist eine **Zeilenabdeckung**: sie sagt, welche Zeilen ein Test durchläuft, nicht, ob er sie auch prüft. Für die Qualität der Tests sind die Mutationsproben bei den Änderungen die bessere Stichprobe (sie stehen in `CLAUDE.md` beim jeweiligen Abschnitt).
- **Nicht gemessen** ist erzeugter Code: Hilt/Dagger, Room (`*_Impl`), `R` und `BuildConfig`. Er würde die Zahl verzerren. Die Ausnahmen stehen in `app/build.gradle.kts` (`generatedCode`).
- **Compose-Lambdas** (`ComposableSingletons`) sind dabei: ihre Rümpfe sind der echte Quelltext der Bildschirme. Compose erzeugt dazu viele Zweige, deshalb ist die **Zweigabdeckung** weniger aussagekräftig als die der Zeilen.
- Gemessen wird der **Debug-Build**; Tests laufen auf dem Testgerät in einer Ausrichtung (Geräte-Tests, die eine bestimmte Ausrichtung brauchen, erzwingen sie selbst).
- Ein Stand ist eine **Momentaufnahme**: er gilt für die genannte Version und wird nur auf Zuruf neu gemessen, nicht bei jedem Release.
