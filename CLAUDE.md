# OBD2 Monitor — Projekt-Instruktionen

> Diese Datei wird automatisch in jeder Claude-Code-Session in diesem Repo geladen. Sie definiert verbindliche Konventionen für Code und vor allem für UI/UX-Entscheidungen.

## Projekt-Kontext

Android-App zur OBD2-Live-Diagnose. Single-Module (`app/`), Kotlin 2.4.10 (built-in Kotlin von AGP 9.3), Jetpack Compose (Material 3), Hilt, Room, minSdk 26 / targetSdk 36 / compileSdk 36. Architektur-Highlights stehen in `README.md` und im Auto-Memory.

---

## UI/UX Design-Profil

**Aesthetic Direction: racing-monitor + premium + utility**
**Referenz-App: [DMD²](https://www.dmdnavigation.com/dmd2-app/) (Drive Mode Dashboard 2 von Thork Racing)**

Diese drei Worte sind **gleichberechtigt** — verletze keines davon:
- **racing-monitor** — Glanceable, Cockpit-Feeling, große Zahlen, Performance-Vibe (nicht Toy-Racing)
- **premium** — Refined Typografie, durchdachte Spacings, harte Kanten erlaubt aber nie billig
- **utility** — Daten zuerst, Deko zuletzt; jede Pixel-Entscheidung muss Funktion rechtfertigen können

### Design-Prinzipien (Top-Down)

1. **Glanceability First.** Der Fahrer schaut max. 0.5 s aufs Display. Primäre Werte müssen ohne Lesen erfassbar sein — Größe + Position + Farbe transportieren Bedeutung, nicht Text.
2. **Die drei Cockpit-Farbschemata (Blau/Amber/Grün) sind dark-only.** Keine Light-Variante für diese drei — Ambient-Light-Probleme im Auto/Motorrad bei Tag lösen sie über *Kontrast*, nicht über Helligkeit. Background ist immer fast-schwarz mit Farbhauch (`#050D1A` Blau, `#110A02` Amber, `#030F08` Grün — siehe `ui/theme/ColorSchemes.kt`). **Korrektur (2026-09-23):** Das gilt NUR für diese drei Cockpit-Schemata, nicht für die App insgesamt — das „Default"-Farbschema (M3-Standardfarben, keines der drei Cockpit-Schemata) bietet bewusst zusätzlich Hell/Dunkel/System zur Wahl (`AppPreferences.THEME_LIGHT`/`THEME_DARK`/`THEME_SYSTEM`, s. `SettingsComponents.kt`/`MainActivity.kt`) — Light Mode ist dort eine unterstützte, reguläre Option, kein Fehler. Eine frühere Version dieser Datei behauptete fälschlich „Dark Mode ist die einzige Realität" für die ganze App.
3. **Tabular Numerals immer.** Wo Zahlen sich ändern (RPM, Speed, Temp, alle Sensorwerte), MUSS die Schrift `fontFeatureSettings = "tnum"` setzen. **IBM Plex Mono** ist als Live-Wert-Schrift in `R.font.ibm_plex_mono_{regular,medium,bold}` geladen; verwenden über `LiveValueText` Composable (`ui/components/LiveValueText.kt`) oder `TextStyle.asLiveValue()` Extension (`ui/theme/AppTypography.kt`). Space Grotesk bleibt für UI-Text (Labels, Body, Headlines).
4. **Glove-Friendly Tap-Targets.** Minimum 56 dp × 56 dp für jeden Touch. Bei Settings-Listen mind. 64 dp Zeilenhöhe. Keine Swipe-Only-Gesten an kritischen Stellen (DMD2-Regel: „avoid swipes while wearing gloves").
5. **Information Density ≥ Decoration.** Karte ohne Daten ist verloren. Wenn ein Tile leerer Raum ist, dann mit Absicht (Atemraum), nicht aus Faulheit.

### Color & Theme

Drei vorhandene Themes (`blueColorScheme`, `amberColorScheme`, `greenColorScheme`) → vom User wählbar in Settings. Beim Bauen neuer Screens **nur** über `MaterialTheme.colorScheme.*` zugreifen — **niemals** hardcoded `Color(0xFF…)` außerhalb der Theme-Datei.

| Semantik | Token | Beispielwert (Blue Scheme) |
|---|---|---|
| App-Hintergrund | `background` | `#050D1A` |
| Tile-Hintergrund | `surface` | `#0A1628` |
| Tile-Hintergrund (akzentuiert) | `surfaceVariant` | `#0D2035` |
| Primärer Live-Wert | `primary` | `#38BDF8` (cyan) |
| Sekundär (Labels, Sub-Werte) | `onSurfaceVariant` | `#9DC5D6` |
| Outline (Hairlines) | `outline` | `#3D6070` |

**Statusfarben (cross-theme, immer gleich):** definiert in `ui/theme/StatusColors.kt` — nicht hardcoded inline!
- `StatusColors.Warning` (`#F59E0B`, Tailwind Amber 500) — non-blocking Caution (Coolant zu heiß, Voltage niedrig, BLE schwach)
- `StatusColors.Critical` (`#EF4444`, Tailwind Red 500) — Redline, Fault, harter Limit-Treffer
- OK / Verbunden: `MaterialTheme.colorScheme.tertiary` (per-Theme getönt — bewusst nicht fixe Farbe, da OK kein Alarm-Signal ist)
- `StatusColors.TrafficLightGreen` (`#22C55E`, Tailwind Green 500) — **nur** das feste Grün der beiden unten dokumentierten Rot/Gelb/Grün-Ampel-Ausnahmen (Bluetooth-Dot, Öl-Temperatur-Balken), **kein** allgemeines OK-Grün
- Form-Validation / Empty-State-Error-Texte: `MaterialTheme.colorScheme.error` (M3-Standard, andere Semantik als Dashboard-Critical)

**Dokumentierte Ausnahme — Bluetooth-Verbindungs-Dot (Stand 2026-09-23, Nutzerwunsch):** Der
Verbindungsstatus-Dot im Dashboard (neben dem Bluetooth-Icon in `DashboardScreen.kt`) nutzt
entgegen der obigen Regel eine klassische Rot/Gelb/Grün-Ampel-Semantik statt theme-getönter
Non-Alarm-Farben: `connectionDotColor()` (`ui/dashboard/DashboardStatusDot.kt`) liefert `Ok` →
`StatusColors.TrafficLightGreen` (`#22C55E`, Tailwind Green 500 — seit 2026-09-27 benannte Konstante
statt Inline-Literal, geteilt mit dem Öl-Temperatur-Balken unten), `Connecting` → `#EAB308`
(Tailwind Yellow 500), `Critical` weiterhin `StatusColors.Critical`. Gilt **nur** für diesen einen
Dot — `StatusDot()`s neuer optionaler `color`-Parameter ist ein Override; ohne ihn behält die
Komponente ihre bisherige, nicht-alarmierte Standardfarbgebung für jede andere Verwendung.

**Dokumentierte Ausnahme — Öl-Temperatur-Balken (Stand 2026-09-27, Nutzerwunsch):** Der
simulierte Öl-Temperatur-Balken unter der RPM-Segmentanzeige (`OilTemperatureBarStrip` in
`ui/dashboard/DashboardComponents.kt`) nutzt **invertierte** Rot/Gelb/Grün-Semantik statt der
üblichen App-Farb-Logik: Rot = zu kalt (≤75 °C), nicht gefährlich/heiß. `oilBarColor()`
(`ui/dashboard/OilTemperatureBar.kt`) liefert den Zustand, `oilBarStripColor()`
(`DashboardComponents.kt`, reine Funktion, JVM-getestet) ordnet ihm die Farbe zu:
- `Red` (≤75 °C) → `StatusColors.Critical` (Rot)
- `Yellow` (>75 °C, unter dem Grün-Schwellwert) → `StatusColors.Warning` (Gelb)
- `Green` (>80 °C **ununterbrochen für 10 Minuten**, mit Hysterese-Zone 78–80 °C) → `StatusColors.TrafficLightGreen` (`#22C55E`, dasselbe Grün wie der Bluetooth-Dot oben)

**Korrektur (2026-09-27, finales Review):** Grün war ursprünglich `MaterialTheme.colorScheme.tertiary`
— das ist aber nur im grünen Cockpit-Schema tatsächlich grün (`#86EFAC`); im blauen ist es
hellblau (`#7DD3FC`), im amber-farbenen amber (`#FCD34D`, kaum vom Gelb-Zustand `#F59E0B` zu
unterscheiden) und im Default-Schema mauve (`#7D5260`, hell) bzw. rosa (`#EFB8C8`, dunkel —
M3-1.4.0-Standardwerte). Deshalb jetzt das feste Ampel-Grün, auf Nutzerwunsch exakt die Farbe des
Bluetooth-Dots.

Die invertierte Semantik ist inhaltlich korrekt (ein kalter Motor braucht längere Aufwärm-Zeit,
nicht als Alarm sondern als Hilfe-Info) und vom User bestätigt. Alle drei Farben sind benannte
`StatusColors`-Tokens; keine Hardcoded-Werte im Composable. Hysterese-Details: `GREEN_ENTER_THRESHOLD_C = 80 °C`,
`GREEN_RESET_THRESHOLD_C = 78 °C`, `GREEN_HOLD_MS = 10 min` — die Zone 78–80 °C hält den
Timer unverändert (verhindert Thermostat-Pendeln).

### Typografie

Display-Font: **Space Grotesk** (bereits geladen, `R.font.space_grotesk_*`).
Globaler Scale: `BIKE_SCALE = 1.18f` (motorcycle readability) — nicht ändern ohne Begründung.

**Hierarchie (verbindlich):**
| Verwendung | M3-Token | Beispiel |
|---|---|---|
| Live-Hauptwert (Speed/RPM) | `displayLarge` (oder größer via custom) | „2.840" RPM in 64 sp |
| Live-Werte in Kacheln | `ValueTileStyle` (`displayMedium`-Größe, Plex Mono, `tnum`) | „87 °C" |
| Tile-Label | `labelMedium` ALL-CAPS letterspaced | „COOLANT" |
| Body / Settings-Text | `bodyMedium` | Standardtext |
| Kleine Hinweise / Footer | `labelSmall` | „last update 0.4 s" |

**ALL-CAPS-Labels** mit `letterSpacing = 0.12.em` für Cockpit-Charakter. Sparsam — nur Tile-Labels und Sektions-Überschriften.

**`GearIndicatorStyle` == `ValueTileStyle` (Stand 2026-09-24, User-Wunsch):** Die Gang-Ziffer neben
der RpmBar (`ui/theme/AppTypography.kt`) ist jetzt bewusst identisch mit dem Kachel-Wert-Style —
optisch exakt so groß wie die Sensorwerte in den Dashboard-Kacheln. Damit sie hineinpasst, wurden
zwei zuvor feste Maße in `RpmBar` (`ui/dashboard/DashboardComponents.kt`) vergrößert: die
Zeilenhöhe von 72 dp auf **78 dp** (Innenhöhe der Gang-Box 56 dp → 62 dp, für
`ValueTileStyle.lineHeight` 61,36 dp) und die Gang-Box-Breite von 32 dp auf **40 dp** (für
`ValueTileStyle.fontSize` 53,1 sp — breiter als die vorherige, auf 46 sp zugeschnittene Box
erlaubte). Beides visuell auf dem Pixel 8 Pro verifiziert (schmale und breite Ziffer, kein
Clipping, kein Überlappen mit Balken/Icon).

**Historie dieser Ziffer** (frühere, jetzt überholte Zwischenstände): 40 sp (ursprünglich, bewusst
NICHT mit `BIKE_SCALE` skaliert) → 45 sp → 46 sp (jeweils an die feste RpmBar-Innenhöhe angepasst,
ohne `ValueTileStyle` zu erreichen) → jetzt `ValueTileStyle` exakt, mit vergrößerter Box statt
weiterer eigener Größenpflege. Da `GearIndicatorStyle` nun eine reine Referenz auf `ValueTileStyle`
ist, zieht eine künftige `BIKE_SCALE`-Änderung automatisch nach — keine unabhängige Nachpflege mehr
nötig (anders als bei den früheren Zwischenständen).

Die System-Schriftskalierung (`fontScale`) bleibt für diese Ziffer weiterhin lokal auf `1f` fixiert
(`CompositionLocalProvider`/`LocalDensity` in `RpmBar`) — auch die jetzt größere, feste 40×62dp-Box
würde bei der Bedienungshilfe „Große Schrift" sonst zu klein. Die `contentDescription` der Box
bleibt davon unberührt lesbar.

### Layout-Patterns

**`LiveValueTile`** (in `ui/components/LiveValueTile.kt`) ist das zentrale UI-Element. Verbindliche Anatomie (Stand 2026-05-15):

```
┌─┬───────────────────────────┐
│▓│ COOLANT                   │  ← labelMedium ALL-CAPS, 0.12 em, onSurfaceVariant
│▓│                           │
│▓│ ╲╱╲╱────╲╱╲──╲╱╲╱╲──╲╱●  │  ← Sparkline (primary), eigenes Band ueber dem
│▓│                    87 °C  │    Wert, volle Kachelbreite, 48 dp hoch
└─┴───────────────────────────┘  ← Wert ValueTileStyle (Plex Mono, accent),
 ↑                                 Einheit titleSmall onSurfaceVariant
 4 dp Status-Side-Bar:
   `TileStatus.Ok`       → `MaterialTheme.colorScheme.primary`
   `TileStatus.Warning`  → `StatusColors.Warning`
   `TileStatus.Critical` → `StatusColors.Critical`
```

Signatur: `LiveValueTile(label, value, unit?, modifier, sparkline, status = Ok, onClick?, onLongPress?)`. Domain-agnostisch — die Adapter-Logik (PID-Name, Einheit-Konvertierung, NaN-Handling) liegt im rufenden Code (z.B. `SensorCard` für die Dashboard-PIDs).

- Höhe: mindestens 120 dp, idealerweise 140–170 dp (vom Grid-Caller via Modifier gesetzt).
  **Nie** `weight(1f)` ueber die volle Resthoehe: bei ~270 dp liegen Label und Wert so weit
  auseinander, dass die Zuordnung beim Blick von 0,5 s nicht mehr gelingt (Stand 1.9.0).
  **Dokumentierte Ausnahme:** Dashboard-Kacheln im Querformat sind bis zu 200dp hoch (Stand
  2026-09-22, kein fester Wert — auf kuerzeren Bildschirmen schrumpft die Reihe auf die
  verfuegbare Hoehe, genau wie 160dp es zuvor tat) — dort bleibt sonst Bildschirmhoehe unter der
  Kachel-Reihe ungenutzt, besonders bei ausgeblendetem Hauptmenue. Der zusaetzliche Raum ist
  bewusster Atemraum, kein neuer Inhalt.
- Sparkline liegt **ueber** dem Wert, nicht daneben und nicht dahinter: neben dem Wert bleiben
  bei vierstelligen Zahlen nur wenige dp, dahinter schneidet die Linie durch die Ziffern.
- Form: `RectangleShape` (scharfe Kanten — Cockpit-Look). Falls später Rundungen gewünscht: über Modifier-Clip von außen.
- Elevation: 0 dp. **Keine Shadows.** Tile-Abgrenzung über `surface`/`background`-Kontrast und die 4 dp Status-Side-Bar.
- Padding innen: 12 dp
- Tap: optional via `onClick`/`onLongPress`; combinedClickable, kein Ripple-Override aktuell (TODO falls als zu auffällig empfunden)

**Grid:**
- Portrait: 2 Spalten Standard, optional 1 Spalte für Premium-Slots (RPM dominant)
- Landscape: 3-4 Spalten (DMD2 erlaubt bis zu 9 Slots)
- `LazyVerticalGrid` mit `GridCells.Fixed`, `verticalArrangement = spacedBy(12.dp)`, `horizontalArrangement = spacedBy(12.dp)`

### RPM-Balken

Segmentanzeige statt durchgehendem Balken: 50 Stufen à 200 rpm über die volle Breite, 1,5 dp
Abstand. Die unbeleuchteten Segmente bleiben in `surfaceVariant` sichtbar, damit die Skala auch
bei 0 rpm lesbar ist — die Position der Drehzahl im Band erfasst man so, ohne die Zahl zu lesen.
Segmente ab dem Blink-Schwellwert leuchten in `StatusColors.Critical` (Schaltblitz-Prinzip),
darunter in `primary`. Ohne Verbindung: keine Alarmfarbe, kein Blinken.

Neben dem Balken steht seit der virtuellen Ganganzeige (Stand 2026-09-23) keine Drehzahl-Zahl
mehr, sondern eine schmale Box (40 dp breit, volle Balkenhöhe — Stand 2026-09-24, zuvor 32 dp,
s. „`GearIndicatorStyle` == `ValueTileStyle`" oben) mit der aktuell erkannten
Gangstufe als einzelne Ziffer (`GearIndicatorStyle`, Plex Mono, `tnum`) in `primary`. Ist der Gang
nicht bestimmbar — keine Verbindung, oder verbunden aber z.B. im Stillstand — zeigt die Box „–“ in
`onSurfaceVariant` statt einer Zahl. Die Berechnung selbst (`determineGear`, Hysterese via
`STAY_TOLERANCE`/`ENTER_TOLERANCE`) liegt in `ui/dashboard/GearIndicator.kt`, domain-agnostisch und
ohne Android-Bezug.

**Anzeige-Persistenz (seit 2026-09-23, Nutzer-Feedback nach Testfahrt):** Ein einzelner
unplausibler Messzyklus (Kupplung gezogen, Gas weg) soll die Anzeige nicht auf „–“ zurückfallen
lassen. `holdGear()` (`ui/dashboard/GearIndicator.kt`, ebenfalls reine Funktion) hält den zuletzt
angezeigten Gang, solange die GPS-Geschwindigkeit über `MIN_SPEED_KMH` bleibt, und löscht ihn erst
bei echtem Stillstand oder fehlendem GPS-Fix. `DashboardViewModel.applyReadings()` reicht dafür
`determineGear()`s Ergebnis durch `holdGear()`, bevor es in den UI-State geschrieben wird.

**Öl-Temperatur-Balken (seit 2026-09-27):** Direkt unterhalb der RPM-Segmente sitzt ein schmaler,
simulierter Öl-Temperatur-Streifen (`OilTemperatureBarStrip`) — ohne Label, reine Farb-Anzeige mit
invertierter Semantik (s. „Statusfarben” oben). Aufbau: Das `weight(1f)`-Feld der Segmente in
`RpmBar` ist eine `Column` aus Segment-Box (Gewicht 0,8), 2 dp Lücke und Streifen (Gewicht 0,2) —
bei 62 dp Innenhöhe 48 dp / 2 dp / 12 dp. Der Streifen liegt damit **links neben** der Gang-Box,
nicht über oder unter ihr: Die Gang-Box ist eine eigene Spalte der äußeren `Row` über die volle
Zeilenhöhe und bleibt unverändert, ebenso die 78 dp Zeilenhöhe. Breite: exakt die Segment-Breite
(nicht über Gang-Box oder Icons hinaus). Die 2-dp-Lücke, in der der Hintergrund durchscheint, hält
Segmente und Streifen auch bei gleicher Farbe als zwei Formen lesbar (grau auf grau ohne Verbindung,
rot auf rot bei kaltem Motor im Schaltblitz-Bereich).

Verhalten (`DashboardViewModel.applyReadings()`, reine Logik in `ui/dashboard/OilTemperatureBar.kt`):
- **Grau** (`surfaceVariant`, wie die unbeleuchteten Segmente), solange keine Verbindung besteht
  (`isLive = false`) oder noch kein Kühlmittelwert eingetroffen ist (`oilBarColor == null`, auch
  nach jedem Verbindungsabbruch bis zum ersten neuen Messwert).
- **Hält die letzte Farbe**, wenn ein Zyklus keinen plausiblen Kühlmittelwert enthält (fehlt oder
  NaN) — kein Rückfall auf Grau oder Rot wegen eines einzelnen Aussetzers (analog `holdGear()`).
- **Nur echt neue Messwerte zählen:** `ReadingsPipeline.gpsRefresh()` stellt bei jeder GPS-Änderung
  die letzten OBD-Werte erneut zu. Ein Kühlmittelwert, dessen `SensorValue.timestamp` nicht neuer ist
  als der des zuletzt verarbeiteten, lässt Timer und Farbe unverändert — sonst bestätigte GPS-Rauschen
  bei abgestellter Zündung (Steuergerät liefert nur noch NO DATA) einen alten Wert über 80 °C, bis der
  Balken grün würde. Bekannte Grenze: Springt die Systemzeit zurück, steht der Balken für die Dauer
  des Sprungs still (nie fälschlich grün).
- **Lücken-Limit 60 s** (`MAX_SAMPLE_GAP_MS`): Liegen zwischen zwei verarbeiteten Messwerten mehr als
  60 s, beginnt die Grün-Zählung von vorn. Ein normaler Reconnect (Watchdog-Default 20 s plus Backoff
  und Verbindungsaufbau) behält die Zählung — der Timer wird in `applyIdle()` bewusst nicht
  zurückgesetzt —, eine längere Trennung (auch bei im Hintergrund weiterlebender App) nicht.
- Zeitbasis von Timer und Lücken-Limit ist `SystemClock.elapsedRealtime()` (monoton, zählt Tiefschlaf
  mit), nicht die Wanduhr.
- Die Kühlmitteltemperatur wird dafür **immer** abgefragt, wie RPM — unabhängig von Slot-Belegung und
  dynamischer Schwelle (`ReadingsPipeline.selectPids()`).

### Animation-Disziplin

- **Werte-Updates:** `AnimatedContent` mit `slideInVertically + fadeIn` über 180 ms, `EaseOutCubic`. **Nie Spring/Bouncy** — das ist Toy-Aesthetic.
- **Connection-Status:** Pulsierender Dot via `infiniteRepeatable` `tween(600)` für „connecting"; statisch für „connected"/„disconnected".
- **Screen-Transitions:** `slideIntoContainer` 220 ms, **nicht** `fadeIn` allein (zu unbestimmt).
- **Verboten:** parallax scroll, decorative particle effects, anything that draws attention away from data.

### Iconography

- **Material Symbols** (outlined Variante als Default, filled für aktive States).
- Konsistente Größen: 20 dp inline, 24 dp Toolbar, 32 dp in Tiles, 48 dp in Empty-States.
- **Keine** Drittanbieter-Icon-Packs vermischen.

### Komponent-Vokabular (verbindliche Namen)

Wenn du neue Composables schreibst, halte dich an dieses Vokabular:

| Composable | Verwendung |
|---|---|
| `LiveValueText(value, style, color, animated)` | Inline-Wert in Plex Mono + `tnum`; `animated=true` für langsam wechselnde Werte (Snapshots/VIN) |
| `LiveValueTile(label, value, unit, status, sparkline)` | Zentrales Tile-Pattern (s.o.) — Side-Bar-Akzent via `TileStatus` |
| `StatusDot(state, size, color?)` | 8-dp Kreis, theme-`tertiary` ok / `StatusColors.Warning` / `StatusColors.Critical` / gepulst auf primary für „connecting"; optionaler `color`-Override für Ausnahmefälle (s. Dashboard-Verbindungs-Dot unten) |
| `SectionLabel(text, textAlign?)` | ALL-CAPS labelMedium für Section-Header (0.12.em letter-spacing) |
| `Sparkline(points, color)` | Trend-Linie (immer primary, unabhängig von TileStatus): 4 dp, runde Enden, darunter ein auslaufender Verlauf (32 % → transparent), Punkt auf dem jüngsten Wert, Band 48 dp — bei Tageslicht auf dem Motorrad war 2 dp ohne Fläche unlesbar |

Bestehende Screens vor neuen Composables prüfen — Pattern wiederverwenden.

### Verbotene Patterns

- ❌ Default Material `Card`-Elevation > 0 (wir nutzen 0)
- ❌ `Inter`, `Roboto`, `Arial`, System-Fonts irgendwo im UI
- ❌ Light-Variante der drei Cockpit-Farbschemata Blau/Amber/Grün — die bleiben dark-only. (Das „Default"-Farbschema selbst darf Hell/Dunkel/System anbieten, s. Design-Prinzip 2 oben — das ist keine Ausnahme von dieser Regel, sondern ein eigenes, separates Farbschema.)
- ❌ Lineare lila/pink Gradients oder generische „Material You" Auto-Farben
- ❌ Lottie-Animationen (Bloat + Off-Brand)
- ❌ Bottom Sheets für kritische Aktionen während Fahrt (zu klein, schwer zu treffen)
- ❌ Wert + Label in derselben Zeile ohne klare visuelle Hierarchie
- ❌ Random Farb-Coding („gelb weil mir gerade danach ist") — Farben tragen Semantik (s. Statusfarben oben)

### Material 3 als Anker

Bei Unsicherheit: **Material 3 default gewinnt**, aber wir biegen es Richtung Cockpit (siehe oben). Jede Abweichung von M3 muss in dieser Datei dokumentiert sein. Wenn du etwas tust, das M3 widerspricht und es nicht hier steht → frag nach.

---

## Code-Konventionen

- **Tests pflichtig** für jeden neuen ViewModel, jeden Parser, jede Repository-Methode. Erst Tests schreiben (TDD), dann Implementation (siehe `superpowers:test-driven-development`).
- **UI-Strings** ausschließlich über `stringResource(R.string.…)`, niemals hardcoded. Beide Locales `values/` + `values-de/` pflegen.
- **Coroutines:** `viewModelScope` für ViewModel-internes, `applicationScope` (definiert in `AppModule`) für Lifecycle-überspannendes (Foreground-Service-Repository-Calls).
- **Hilt:** `@Singleton` nur wo unverzichtbar. Repositories ja, ViewModels nie.
- **Logging:** `AppLogRepository` für persistente App-Logs (Settings-Screen sichtbar). `Log.d/w/e` nur in Tests oder für Debug-Build-Output.

## Build & Test (lokal)

```bash
./gradlew :app:testDebugUnitTest          # 703 JVM-Unit-Tests
./gradlew :app:connectedDebugAndroidTest  # 142 Instrumentation-Tests (Gerät nötig)
./gradlew assembleDebug                    # APK
./gradlew assembleRelease                  # signiert via env-vars (s. README)
```

## Bei UI-Änderungen IMMER

1. Vorher: bestehende Composables im selben Bereich gelesen — Pattern reused, nicht dupliziert
2. Während: `Modifier`-Chain in der Standardreihenfolge: `fillMaxWidth/Height → padding → background → clip → border → clickable`
3. Nachher: `@Preview` für jeden neuen Composable in Light- UND Dark-Surface (auch wenn wir Light nicht ausliefern — hilft beim Kontrast-Check)
4. Falls Live-Werte beteiligt: explizit `fontFeatureSettings = "tnum"` setzen oder Mono-Font wählen
