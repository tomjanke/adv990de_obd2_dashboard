# OBD2 Monitor — Projekt-Instruktionen

> Diese Datei wird automatisch in jeder Claude-Code-Session in diesem Repo geladen. Sie definiert verbindliche Konventionen für Code und vor allem für UI/UX-Entscheidungen.

## Projekt-Kontext

Android-App zur OBD2-Live-Diagnose. Single-Module (`app/`), Kotlin 2.4.10 (built-in Kotlin von AGP 9.3), Jetpack Compose (Material 3), Hilt, Room, minSdk 26 / targetSdk 35 / compileSdk 36. Architektur-Highlights stehen in `README.md` und im Auto-Memory.

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
2. **Dark Mode ist die einzige Realität.** Keine Light-Theme-Varianten. Ambient-Light-Probleme im Auto/Motorrad bei Tag → wir lösen das über *Kontrast*, nicht über Helligkeit. Background ist immer fast-schwarz mit Farbhauch (`#050D1A` Blau, `#110A02` Amber, `#030F08` Grün — siehe `ui/theme/ColorSchemes.kt`).
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
- Form-Validation / Empty-State-Error-Texte: `MaterialTheme.colorScheme.error` (M3-Standard, andere Semantik als Dashboard-Critical)

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
darunter in `primary`. Ohne Verbindung: keine Alarmfarbe, kein Blinken, Wert „-- rpm".

### Animation-Disziplin

- **Werte-Updates:** `AnimatedContent` mit `slideInVertically + fadeIn` über 180 ms, `EaseOutCubic`. **Nie Spring/Bouncy** — das ist Toy-Aesthetic.
- **Connection-Status:** Pulsierender Dot via `infiniteRepeatable` `tween(1200)` für „connecting"; statisch für „connected"/„disconnected".
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
| `StatusDot(state, size)` | 8-dp Kreis, theme-`tertiary` ok / `StatusColors.Warning` / `StatusColors.Critical` / gepulst auf primary für „connecting" |
| `SectionLabel(text)` | ALL-CAPS labelMedium für Section-Header (0.12.em letter-spacing) |
| `ActionButton(label, onClick)` | min. 56 dp Höhe, full-width oder min. 160 dp, rectangular |
| `HairlineDivider()` | 1-dp `outline`, vertikales Padding 8 dp |
| `Sparkline(points, color)` | Trend-Linie (immer primary, unabhängig von TileStatus): 4 dp, runde Enden, darunter ein auslaufender Verlauf (32 % → transparent), Punkt auf dem jüngsten Wert, Band 48 dp — bei Tageslicht auf dem Motorrad war 2 dp ohne Fläche unlesbar |

Bestehende Screens vor neuen Composables prüfen — Pattern wiederverwenden.

### Verbotene Patterns

- ❌ Default Material `Card`-Elevation > 0 (wir nutzen 0)
- ❌ `Inter`, `Roboto`, `Arial`, System-Fonts irgendwo im UI
- ❌ Light Theme („irgendwann mal nice to have" zählt nicht)
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
./gradlew :app:testDebugUnitTest          # 309 JVM-Unit-Tests
./gradlew :app:connectedDebugAndroidTest  # 28 Instrumentation-Tests (Gerät nötig)
./gradlew assembleDebug                    # APK
./gradlew assembleRelease                  # signiert via env-vars (s. README)
```

## Bei UI-Änderungen IMMER

1. Vorher: bestehende Composables im selben Bereich gelesen — Pattern reused, nicht dupliziert
2. Während: `Modifier`-Chain in der Standardreihenfolge: `fillMaxWidth/Height → padding → background → clip → border → clickable`
3. Nachher: `@Preview` für jeden neuen Composable in Light- UND Dark-Surface (auch wenn wir Light nicht ausliefern — hilft beim Kontrast-Check)
4. Falls Live-Werte beteiligt: explizit `fontFeatureSettings = "tnum"` setzen oder Mono-Font wählen
