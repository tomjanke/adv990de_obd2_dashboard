# OBD2 Monitor — Projekt-Instruktionen

> Diese Datei wird automatisch in jeder Claude-Code-Session in diesem Repo geladen. Sie definiert verbindliche Konventionen für Code und vor allem für UI/UX-Entscheidungen.

## Projekt-Kontext

Android-App zur OBD2-Live-Diagnose. Single-Module (`app/`), Kotlin 2.4.21 (built-in Kotlin von AGP 9.4), Jetpack Compose (Material 3), Hilt, Room, minSdk 26 / targetSdk 37 / compileSdk 37. Architektur-Highlights stehen in `README.md` und im Auto-Memory.

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
4. **Glove-Friendly Tap-Targets.** Minimum 56 dp × 56 dp für jeden Touch (seit v1.24.8 von `AppTheme` für alle Material-Komponenten erzwungen, s. „Touch-Ziele und Barrierefreiheit“). Bei Settings-Listen mind. 64 dp Zeilenhöhe. Keine Swipe-Only-Gesten an kritischen Stellen (DMD2-Regel: „avoid swipes while wearing gloves"); Swipe-to-Delete gibt es nur **zusätzlich** zum sichtbaren Papierkorb-Button (s. „Lösch-Vertrag").
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
- `StatusColors.Warning` (`#EAB308`, Tailwind Yellow 500 — **Korrektur 2026-09-28**, User-Wunsch nach kräftigerem Gelb, vorher `#F59E0B`/Amber 500) — non-blocking Caution (Coolant zu heiß, Voltage niedrig, Bluetooth schwach). **Als TEXT oder Akzent auf einer Fläche immer über `StatusColors.warningOn(surface)`** (seit v1.24.6, B28): auf hellen Flächen (Leuchtdichte > 0,5, das helle Default-Schema) liefert das `WarningOnLight` (`#A16207`, Tailwind Yellow 700, ≈ 4,9:1) statt des kräftigen Gelbs, das dort nur 1,87:1 hat; auf den dunklen Cockpit-Schemata bleibt es `Warning`. Punkte und Balken in den dokumentierten Ampel-Ausnahmen behalten das kräftige Gelb.
- `StatusColors.Critical` (`#EF4444`, Tailwind Red 500) — Redline, Fault, harter Limit-Treffer
- OK / Verbunden: `MaterialTheme.colorScheme.tertiary` (per-Theme getönt — bewusst nicht fixe Farbe, da OK kein Alarm-Signal ist)
- `StatusColors.TrafficLightGreen` (`#22C55E`, Tailwind Green 500) — **nur** das feste Grün der unten dokumentierten Ausnahmen (Bluetooth-Dot und Öl-Temperatur-Balken als Rot/Gelb/Grün-Ampel, Neutral-Anzeige „N“ der Ganganzeige), **kein** allgemeines OK-Grün
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
(`RpmBarLogic.kt`, reine Funktion, JVM-getestet) ordnet ihm die Farbe zu:
- `Red` (≤75 °C) → `StatusColors.Critical` (Rot)
- `Yellow` (>75 °C, unter dem Grün-Schwellwert) → `StatusColors.Warning` (Gelb)
- `Green` (>80 °C **ununterbrochen für 10 Minuten**, mit Hysterese-Zone 79–80 °C und Entprellen von Einbrüchen ≤ 78 °C um 120 s, s. u.) → `StatusColors.TrafficLightGreen` (`#22C55E`, dasselbe Grün wie der Bluetooth-Dot oben)

**Korrektur (2026-09-27, finales Review):** Grün war ursprünglich `MaterialTheme.colorScheme.tertiary`
— das ist aber nur im grünen Cockpit-Schema tatsächlich grün (`#86EFAC`); im blauen ist es
hellblau (`#7DD3FC`), im amber-farbenen amber (`#FCD34D`, kaum vom Gelb-Zustand — Stand 2026-09-28
`#EAB308`, davor `#F59E0B` — zu unterscheiden) und im Default-Schema mauve (`#7D5260`, hell) bzw. rosa (`#EFB8C8`, dunkel —
M3-1.4.0-Standardwerte). Deshalb jetzt das feste Ampel-Grün, auf Nutzerwunsch exakt die Farbe des
Bluetooth-Dots.

Die invertierte Semantik ist inhaltlich korrekt (ein kalter Motor braucht längere Aufwärm-Zeit,
nicht als Alarm sondern als Hilfe-Info) und vom User bestätigt. Alle drei Farben sind benannte
`StatusColors`-Tokens; keine Hardcoded-Werte im Composable. Hysterese-Details: `GREEN_ENTER_THRESHOLD_C = 80 °C`,
`GREEN_RESET_THRESHOLD_C = 78 °C`, `GREEN_HOLD_MS = 10 min`, `RESET_DEBOUNCE_MS = 120 s` — die Zone 79–80 °C hält den
Timer unverändert (verhindert Thermostat-Pendeln) und unterbricht einen laufenden Einbruch; ein Einbruch (≤ 78 °C bei laufendem Timer) setzt den Timer erst zurück,
wenn er **120 s ununterbrochen** anhält (seit 2026-10-12, s. „Entprellen des Resets“ unten); bei ≤ 75 °C (Rot) gilt sofort der Reset.

**Dokumentierte Ausnahme — Neutral-Anzeige „N“ in der Ganganzeige (Stand 2026-10-04, Nutzerwunsch):**
Steht das Fahrzeug und läuft der Motor, zeigt die Gang-Box neben dem RPM-Balken (s. „RPM-Balken“) ein
**grünes „N“** statt des „–“. Das Grün ist in jedem Farbschema `StatusColors.TrafficLightGreen`
(`#22C55E`, dasselbe wie Bluetooth-Dot und Öl-Balken) — wie die grüne Leerlauf-Kontrollleuchte am
Motorrad; Theme-`tertiary` scheidet aus, weil es nur im grünen Cockpit-Schema grün ist. Die
Farbzuordnung steckt in `gearBoxColor()` (`ui/dashboard/RpmBarLogic.kt`, reine Funktion,
JVM-getestet). Kontrast: auf den drei dunklen Cockpit-Schemata ≈ 8:1; auf dem hellen Default-Schema
nur ≈ 2,3:1, dort wegen der großen Ziffer (53 sp) und der gesättigten Farbe aber klar lesbar
(Screenshot-Prüfung auf dem Pixel 8 Pro, 2026-10-04). Falls das je bemängelt wird: dunkleres Grün für
helle Oberflächen (z. B. `#15803D`, ≈ 5:1) als zweiter Token.

**Dokumentierte M3-Abweichung — Hintergrund der Navigationsleiste (Stand 2026-09-27):** Die
`NavigationBar` in `ui/components/BottomNav.kt` setzt `containerColor = MaterialTheme.colorScheme.surface`
statt des Material3-1.4.0-Defaults `surfaceContainer` (die Default-Tonal-Elevation ist dort 0 dp —
beides per `javap` im Material3-1.4.0-AAR geprüft). Zwei Gründe: (1) Die `AmbientStatusRow`, die die
Leiste während der Fahrt ersetzt, ist eine `surface`-Fläche — mit dem Default gäbe es beim
`AnimatedContent`-Wechsel einen Farbsprung. Auf dem Pixel 8 Pro pixelgenau gemessen: Trennlinie,
64-dp-Fläche und System-Inset-Streifen sind in beiden Zuständen identisch (Blau und Default-Hell).
(2) [seit 2026-10-04 erledigt: die drei Cockpit-Schemata definieren `surfaceContainer` jetzt selbst, = `surface`, s. „Ergänzte Farbrollen“ unten — Grund (1) trägt die Abweichung allein] Die drei Cockpit-Schemata definierten kein `surfaceContainer`; der Default fiel dort auf das
neutrale M3-Grau (`#211F26`) zurück statt auf die Cockpit-Tönung. Nebenwirkung im Default-Farbschema:
dort sind `surface` und `background` gleich, die Leiste hebt sich nur noch über die Trennlinie ab.

**Fünftes Hauptmenü-Item „Beenden" (seit 2026-09-29, User-Wunsch):** Rechts neben „Einstellungen"
sitzt ein fünftes `NavigationBarItem` (`Icons.Default.PowerSettingsNew`, kein Navigationsziel —
`onClick` öffnet einen Bestätigungsdialog statt `navController.navigate()`). Bestätigt, ruft
`ExitAppViewModel.exitApp()` (dieselbe Logik wie der bestehende `SettingsViewModel.disconnect()`:
`ObdRepository.disconnect()` + `ObdForegroundService`-Stop) und danach `Activity.finishAffinity()`
auf — vollständiges Beenden, kein Monitoring läuft im Hintergrund weiter. Der Dialog (Material3
`AlertDialog`, **keine** Bottom Sheet, s. „Verbotene Patterns" unten) ist bewusst vorgeschaltet:
das Icon sitzt permanent im Hauptmenü, ein Griff mit Handschuhen auf holpriger Strecke könnte es
sonst versehentlich treffen.

**Nav-Label-Schriftgröße 10sp, unskaliert (seit demselben Tag):** Mit fünf statt vier Spalten reichte
im Hochformat (~72dp pro Spalte) selbst `MaterialTheme.typography.labelSmall` nicht mehr für
„Einstellungen" auf einer Zeile — das Wort brach um, die zweite Zeile wurde vom festen
64dp-Leistenrahmen abgeschnitten (auf dem Pixel 8 Pro visuell bestätigt). `labelSmall` trägt wie
jede Rolle in `AppTypography` `BIKE_SCALE` (1.18×) und wurde dadurch nur unwesentlich kleiner als
`labelMedium`. Alle fünf Nav-Labels nutzen deshalb ein explizites `TextStyle(fontSize = 10.sp)`,
bewusst ohne `BIKE_SCALE` — Nav-Labels sind sekundäre Chrome-Elemente, keine Live-Werte, und stehen
in keiner Zeile der Typografie-Hierarchie unten. Im Querformat (deutlich breitere Spalten) wäre die
Kürzung nicht nötig gewesen, gilt hier aber einheitlich für beide Ausrichtungen.

**Ergänzte Farbrollen der Cockpit-Schemata (Stand 2026-10-04, v1.21.0):** Die drei Cockpit-Schemata
(`ui/theme/ColorSchemes.kt`) definierten neben den zwölf Basisfarben keine der Rollen, die Material-3-Komponenten
tatsächlich benutzen — M3 fiel dort auf seine violett-grauen Standardtöne zurück (gewähltes Segment und Nav-Indikator
im Orange-Schema violett-grau, Snackbar helles Lavendel mit violetter Aktion, Dialog neutral-grau). Jetzt definiert
jedes Schema: `secondaryContainer`/`onSecondaryContainer` (gewählte Segmente, Nav-Indikator), `inverseSurface`/
`inverseOnSurface`/`inversePrimary` (Snackbar), `surfaceContainerLowest…Highest` (Dialog = `High`; Schalter- und
Slider-Spur = `Highest`). Ableitung aus der eigenen Palette: Container = `surface` zu 28 % Richtung `primary`;
`inverse*` = helle `onSurface`-Tonlage mit dunklem `inversePrimary`; Container-Leiter = `surfaceVariant` zu 6 % bzw.
12 % Richtung `onSurface`. `ColorSchemesTest` hält fest: Text ≥ 7:1, Snackbar-Aktion ≥ 4,5:1, gewähltes Segment
≥ 1,5:1 gegen `surface`, alle neuen Rollen im Farbton ihres Schemas (≤ 30°) und eine aufsteigende Leiter. **Neue
Material-Komponenten zuerst im Orange-Schema ansehen** — fehlt eine Rolle, erscheint wieder M3-Violett; dann die
Rolle im Schema ergänzen und im Test abdecken. Das „Default“-Farbschema (M3-Standardfarben) bleibt unberührt.
**Restlücke geschlossen (v1.24.9):** `primaryContainer`/`tertiaryContainer` (Surface zu 28 % Richtung `primary`/`tertiary`),
`errorContainer` (Surface zu 28 % Richtung `StatusColors.Critical` — dunkles Rot im Ton der Palette; der Swipe-Hintergrund zum Löschen nutzt ihn)
samt `on…`-Rollen (= `onSurface`, ≥ 7:1) und `outlineVariant` (Surface zu 50 % Richtung `outline`: die ruhige 1-dp-Linie zwischen den Dashboard-Kacheln, ≥ 1,3:1 gegen die Kachel)
sind in den drei Cockpit-Schemata jetzt aus der Palette abgeleitet (`ColorSchemesTest`: Regel, Kontrast, Farbton). `error` bleibt bewusst M3-Standard. **Die Level-Chips der App-Logs**
(`logLevelChipColors`): INFO = `primaryContainer`, ERROR = `errorContainer`, **WARN = `StatusColors.container(Warning, surface)` (gelb), nicht `tertiaryContainer`** — im blauen und grünen
Schema liegt `tertiaryContainer` nur 1–2° im Farbton neben `primaryContainer`, INFO und WARN wären nicht zu unterscheiden.

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
  Kachel-Reihe ungenutzt. Der zusaetzliche Raum ist bewusster Atemraum, kein neuer Inhalt.
  **Korrektur (2026-09-27):** Frueher stand hier zusaetzlich „besonders bei ausgeblendetem
  Hauptmenue". Das stimmt nicht mehr: Seit der Ambient-Statuszeile (s. unten) gibt das Ausblenden
  keinen Platz mehr frei — die Zeile nimmt exakt die Hoehe des Hauptmenues ein (Trennlinie + 64 dp +
  System-Inset, auf dem Pixel 8 Pro pixelgenau gemessen). Die 200dp stuetzen sich allein auf die
  Querformat-Geometrie.
- Sparkline liegt **ueber** dem Wert, nicht daneben und nicht dahinter: neben dem Wert bleiben
  bei vierstelligen Zahlen nur wenige dp, dahinter schneidet die Linie durch die Ziffern.
- Form: `RectangleShape` (scharfe Kanten — Cockpit-Look). Falls später Rundungen gewünscht: über Modifier-Clip von außen.
- Elevation: 0 dp. **Keine Shadows.** Tile-Abgrenzung über `surface`/`background`-Kontrast und die 4 dp Status-Side-Bar.
- Padding innen: 12 dp
- Tap: optional via `onClick`/`onLongPress`; combinedClickable mit explizitem `ripple(color = accentColor)`
  (Stand 2026-09-29) — der Ripple ist damit in der Akzentfarbe der Kachel (Status-Side-Bar-Farbe) statt
  im generischen M3-Grau von `LocalIndication`, gleiche Semantik wie die Side-Bar selbst. Braucht ein
  eigenes `MutableInteractionSource` (`remember { MutableInteractionSource() }`), da `ripple()` als
  expliziter `indication`-Parameter kein `LocalIndication.current` mehr mitbekommt.

**Grid:**
- Portrait: 2 Spalten Standard, optional 1 Spalte für Premium-Slots (RPM dominant)
- Landscape: 3-4 Spalten (DMD2 erlaubt bis zu 9 Slots)
- **Ist-Stand (Korrektur 2026-10-05, B39):** Das Dashboard nutzt **kein** `LazyVerticalGrid`. Die vier Slots liegen in festen `Row`/`Column`-Reihen
  (`DashboardScreen`): Portrait 2×2, Querformat 4 in einer Reihe, mit **1-dp-Trennlinien** (`outlineVariant` als Hintergrund der Reihe, `spacedBy(1.dp)`)
  statt 12-dp-Abständen — der Cockpit-Look aus „scharfe Kanten, keine Karten“. Die 12-dp-Vorgabe gilt für andere Raster, die es bisher nicht gibt.

**Slot-Picker (Long-Press auf eine Kachel, seit 2026-10-04, Nutzerentscheidung):** `ModalBottomSheet` mit
`skipPartiallyExpanded = true` — öffnet immer in voller Höhe. Das halbhohe M3-Standardverhalten zeigte im
Querformat (~1008 px Fensterhöhe) anfangs nur ~3 Zeilen, im Hochformat ~8; der Preis der vollen Höhe ist, dass die
Kachel dahinter nicht mehr sichtbar bleibt. Nicht zurück auf den Default stellen, ohne diese Abwägung neu zu treffen.
Regressionstest: `DashboardScreenTest.dashboard_slotPicker_opensFullyExpanded_soLowerRowsAreVisibleInLandscape`.
**Reihenfolge (seit v1.24.12, UID-15):** nach dem **angezeigten, übersetzten** Namen (`sortByDisplayLabel`, `Collator` der Gerätesprache: „Ö“ bei „O“, Groß-/Kleinschreibung egal, stabil) —
nicht nach dem englischen `PidDefinition.name`, nach dem `DashboardViewModel.availablePids` weiter vorsortiert; die Namen werden in der Komposition aufgelöst (`stringResource`).

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
nicht bestimmbar — keine Verbindung, 4–8 km/h, keine Geschwindigkeit oder keine passende
Übersetzung — zeigt die Box „–“ in `onSurfaceVariant` statt einer Zahl. **Leerlauf (seit 2026-10-04):**
Steht das Fahrzeug (zusammengeführte Geschwindigkeit < 2 km/h, ein bereits angezeigtes „N“ bleibt bis
4 km/h) und läuft der Motor (≥ 500 rpm), zeigt die Box stattdessen ein grünes „N“ (s. „Dokumentierte
Ausnahme — Neutral-Anzeige“ oben). Semantik: „N“ = „steht, Motor läuft“ — Leerlauf **oder Kupplung
gezogen** (OBD2 hat keinen Leerlaufschalter, an der Ampel nicht unterscheidbar); Leerlauf im Rollen
wird nicht erkannt. Anders als der Gang nutzt „N“ die zusammengeführte Anzeige-Geschwindigkeit (ECU
zuerst, GPS als Fallback): die ECU meldet den Stillstand exakt und ohne GPS-Fix; fehlt jede
Geschwindigkeit, bleibt es bei „–“. „N“ schließt einen Gang aus (`DashboardUiState.isNeutral` ⇒
`gear == null`), ohne Verbindung zeigt die Box immer „–“ (`gearBoxContent()`). Die Berechnung selbst
(`determineGear`, Hysterese via `STAY_TOLERANCE`/`ENTER_TOLERANCE`; `isNeutral`, Hysterese 2/4 km/h)
liegt in `ui/dashboard/GearIndicator.kt`, domain-agnostisch und ohne Android-Bezug.
**Gegenprobe mit dem GPS (seit v1.24.12, UID-19):** Eine ECU, die konstant 0 km/h meldet, ließe „N“ die ganze Fahrt stehen. `standstillConflictSince` führt mit, seit wann
„ECU steht (< 4 km/h)“ und „GPS fährt (≥ 8 km/h, die Rauschgrenze von `determineGear`)“ **ununterbrochen** zusammen gelten; erst nach `NEUTRAL_VETO_AFTER_MS` = 5 s
hebt `gpsVetoesNeutral` das „N“ auf (`isNeutral(…, gpsVeto)`), und der Gang kommt wieder aus dem GPS. **Nie ein sofortiges Veto:** beim Anhalten meldet die ECU sofort 0,
das GPS hinkt ein bis zwei Sekunden nach — das ist der Normalfall und darf „N“ nicht verzögern (`neutral wins over a gear that GPS still reports`). Die Zeit kommt aus
`DashboardViewModel.nowMillis` (monoton), der Beginn wird mit der Verbindung zurückgesetzt (Idle, Verlust, Fehler, Stale).

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
- **Lücken-Limit 60 s** (`MAX_SAMPLE_GAP_MS`), fest, nicht an den konfigurierbaren OBD-Watchdog
  gekoppelt: Liegen zwischen zwei verarbeiteten Messwerten mehr als 60 s, beginnt die Grün-Zählung
  von vorn — der Timer wird in `applyIdle()` bewusst nicht zurückgesetzt, nur bei echter
  Lücken-Überschreitung. Ein schneller Reconnect bleibt meist darunter, ist aber nicht garantiert:
  bei mehreren abgefragten PIDs kann allein die Watchdog-Erkennung (ein Timeout pro PID, je 5 s)
  bereits 30 s+ dauern, dazu Backoff/Verbindungsaufbau/erste Zyklen — ein zweiter Reconnect-Versuch
  oder ein hoch eingestellter Watchdog-Timeout überschreiten die 60 s typischerweise. Kein Fehler,
  nur konservativ: die Grün-Zählung startet dann neu statt einen möglicherweise nicht mehr gültigen
  Warmlauf weiterzuzählen. Eine deutlich längere Trennung (App im Hintergrund, Motorrad geparkt)
  überschreitet die 60 s ohnehin sicher.
- **Entprellen des Resets (seit 2026-10-12, Nutzerwunsch nach Beobachtung auf der Fahrt):** Das Kühlmittel hängt in der Aufwärmphase bei 78–80 °C (Thermostat) und bricht bei Last immer wieder auf 76–78 °C ein; ein sofortiger Reset bei ≤ 78 °C setzte die Zählung dauernd zurück. `updateOilBarTimer` führt dafür `OilBarTimerState.belowResetSinceMillis` (Beginn des Einbruchs): ein Einbruch (≤ 78 °C bei laufendem Timer) setzt zurück, sobald er `RESET_DEBOUNCE_MS` = **120 s ununterbrochen** dauert; jeder Wert in der Haltezone (79–80 °C) oder darüber unterbricht ihn, die Zählung beginnt neu. **Auch ein bereits grüner Balken** bleibt bei kurzen Einbrüchen grün und fällt erst nach 120 s unter 78 °C auf Gelb. Bei ≤ 75 °C (Rot) wird sofort zurückgesetzt, ohne Entprellen; das Lücken-Limit (60 s) vergisst auch den Einbruch. Neuer `SavedStateHandle`-Schlüssel `oilBarBelowResetSinceMillis` (**Schlüsselnamen sind persistierter Zustand, nie umbenennen**; ein Zustand ohne ihn liest als null).
  **Herleitung der 120 s** aus zwei aufgezeichneten Aufwärmfahrten (2026-10-12, CSV, Start bei 6 bzw. 16 °C): längste Einbrüche ≤ 78 °C 82 s bzw. 74 s (≤ 77 °C: 35/42 s; ≤ 76 °C: 15/16 s; ≤ 75 °C: keine). Nachgespielt mit der echten Logik: Reset ≤ 78/77/76 sofort und Entprellen 60 s ließen in **keiner** Fahrt Grün erscheinen, Entprellen ab 90 s und 120 s ließen es genau 10 Minuten nach dem ersten Wert über 80 °C erscheinen (nach 18,1 bzw. 16,1 min Fahrt); 120 s lassen rund 36 s Abstand zum längsten Einbruch, 90 s nur 8 s. Verworfen: Reset ≤ 77 °C allein (kein Grün in beiden Fahrten) und Reset ≤ 75 °C (zu locker: ein Kühlmittel, das lange bei 76 °C steht, erreichte Grün). `OilBarRecordedDrivesTest` spielt beide Fahrten als Zonen-Laufkodierung (Zone, Sekunden) durch und verlangt Grün **genau** 10 Minuten nach dem ersten Wert über 80 °C. **Grenzen:** nur zwei Fahrten bei kühler Witterung; Einbrüche nach dem Grünwerden sind in den Daten nicht enthalten; ob das Öl nach 10 Minuten wirklich warm ist, belegen die Daten nicht (die Regel bildet nur Ihre Definition ab). **Praxisbestätigung (Angabe des Nutzers, 2026-10-14):** nach zwei Testfahrten mit 1.27.2 oder neuer wird der Balken grün, auch wenn die Temperatur zwischendurch kurz fällt; es liegt keine Aufzeichnung dazu im Repository, die Länge der Einbrüche in diesen Fahrten ist nicht bekannt, die 120 s sind damit nicht gegen längere Einbrüche geprüft.
- Zeitbasis von Timer und Lücken-Limit ist `SystemClock.elapsedRealtime()` (monoton, zählt Tiefschlaf
  mit), nicht die Wanduhr.
- Die Kühlmitteltemperatur wird dafür **immer** abgefragt, wie RPM — unabhängig von Slot-Belegung und
  dynamischer Schwelle (`ReadingsPipeline.selectPids()`).
- **Die angezeigte Farbe wird mit aufgezeichnet (seit 2026-10-14):** `OIL_BAR`, 0 = rot, 1 = gelb, 2 = grün, −1 = grau, je OBD-Zyklus — Einzelheiten unter „Was eine Fahrt aufzeichnet“.

### Ambient-Statuszeile (statt Hauptmenü während der Fahrt, seit 2026-09-27)

Ist `hideNavOnDrive` an und das Fahrzeug in Bewegung (`DriveStateViewModel.isNavHidden`; „in Bewegung“ = Hysterese 5/3 km/h, s. „Fahrbetrieb: fährt, frisch und veraltet“), tauscht
`BottomNav` die `NavigationBar` per `AnimatedContent` gegen `AmbientStatusRow`
(`ui/components/AmbientStatusRow.kt`) — auf **jedem** Screen, weil `BottomNav` in der einen
app-weiten `Scaffold` sitzt (User-bestätigt). Verbindlich:
- **Gleiche Geometrie und Fläche wie die Leiste:** Trennlinie, 64 dp `surface`,
  `navigationBarsPadding()` außerhalb der Fläche — kein Höhen- oder Farbsprung beim Wechsel (s.
  M3-Abweichung „Hintergrund der Navigationsleiste" oben).
- **Inhalt:** Datum/Uhrzeit links (`formatDashboardHeader`, tickt genau zum Minutenwechsel),
  Außentemperatur rechts (`formatOutsideTemperature`: `convertUnit` wie `SensorCard`, also °F bei
  imperialen Einheiten; NaN/∞ → weggelassen). Beides `LiveValueText` in `titleLarge.asLiveValue()`
  (~26sp effektiv), Farbe `onSurfaceVariant` — die Zeile ersetzt Chrome, sie ist kein primärer
  Live-Wert wie eine Sensor-Kachel (`ValueTileStyle`, ~53sp), bleibt aber klar größer als jedes
  Label. **Korrektur (2026-09-28, Feldbericht nach Release v1.17.0):** ursprünglich
  `labelSmall.asLiveValue()` (~13sp, kleinste Textrolle im ganzen Typografie-System) — auf der
  echten Fahrt unlesbar, unabhängig von der "sekundär, kein Live-Wert"-Einstufung. Design-Prinzip 1
  (Glanceability) gilt für alles Sichtbare während der Fahrt, nicht nur für Kachel-Werte.
- **Anzeige-Persistenz wie beim Gang:** Ein fehlgeschlagener Abruf leert die Temperatur nicht;
  `holdOutsideTemp()` (`ui/weather/OutsideTemperatureHold.kt`, reine Funktion) hält den letzten
  guten Wert 30 Minuten (`OUTSIDE_TEMP_HOLD`, User-Entscheidung), gemessen auf einer
  `kotlin.time.TimeSource` (Tests: `testTimeSource`). **Korrektur (2026-09-27):** Production nutzt
  dafür **nicht** `TimeSource.Monotonic` wie der OBD-Watchdog — eine frühere Version dieser Datei
  behauptete das fälschlich „für Konsistenz". `TimeSource.Monotonic` ist `System.nanoTime()`, das
  laut Androids `SystemClock`-Doku im Tiefschlaf pausiert; `WeatherViewModel` ist Activity-gebunden
  und überlebt genau solche Pausen (Handy gesperrt in der Tasche), sodass ein gehaltener Wert die
  30-Minuten-Grenze auf dieser Uhr weit überdauern könnte. Stattdessen
  `WeatherViewModel.elapsedRealtimeSource`, gestützt auf `SystemClock.elapsedRealtimeNanos()` —
  dieselbe Begründung wie der Öl-Balken-Timer oben ("elapsedRealtime zählt Tiefschlaf mit"), nicht
  die des OBD-Watchdogs (dessen `TimeSource.Monotonic` für seinen eigenen Anwendungsfall — Lücken
  nur während aktiven Pollings, nie über ein hintergrundgeschicktes Activity/App hinweg — weiterhin
  richtig ist).
- **Abruf nur bei sichtbarer Zeile:** `WeatherViewModel.outsideTempC` ist ein kalter
  `WhileSubscribed(5_000)`-Flow; `BottomNav` sammelt ihn ausschließlich im Zweig der Zeile, per
  `collectAsStateWithLifecycle`. Kein Abruf bei sichtbarem Hauptmenü oder im Hintergrund.
- **Standort:** FINE oder COARSE genügt; angefragt beim Einschalten von `hideNavOnDrive`, ohne den
  Schalter davon abhängig zu machen. **Seit 2026-09-27 zusätzlich per Catch-up:**
  `LaunchedEffect(hideNavOnDrive)` in `SettingsScreen.kt` fragt dieselbe Berechtigung auch bei
  jeder frischen Komposition der Settings an, wenn der Schalter schon aus einer früheren Version
  an ist (der Wert kommt asynchron aus dem DataStore, exakt dasselbe Muster wie `useGpsSpeed`/
  Hintergrund-Standort-Eskalation oben). Einziger Aufrufpunkt ist dieser Effect —
  `onCheckedChange` ruft die Berechtigung nicht mehr direkt an, sonst würde ein Toggle-auf-an sie
  zusammen mit dem Effect doppelt auslösen. **Einmal-Guard (Korrektur 2026-09-27, derselbe Tag):**
  Ohne persistierten Zustand hätte dieser Effect bei JEDEM Settings-Besuch/jeder Rotation erneut
  gefragt, solange der Schalter an und die Berechtigung noch verweigert war — er läuft ja bei
  jeder frischen Komposition mit `hideNavOnDrive == true` neu, nicht nur beim Toggle.
  `AppPreferences.weatherLocationPrompted` (neuer DataStore-Key, exakt dasselbe Schema wie
  `backgroundLocationPrompted`) wird deshalb vor dem `launch()` gesetzt und vorher geprüft
  (`shouldRequestLocationForWeather`s neuer `alreadyPrompted`-Parameter, gleiche Kurzschluss-Form
  wie `shouldEscalateToBackgroundLocation`) — der System-Dialog erscheint damit höchstens einmal
  pro Installation. Koordinaten verlassen das Gerät nur auf 2 Nachkommastellen gerundet
  (Open-Meteo). Namensnennung (CC BY 4.0) UND ein zweiter Link auf den Lizenztext selbst
  (`creativecommons.org/licenses/by/4.0/`) — beide als echte, separate Links in `AppInfoScreen`.
  **Korrektur (2026-09-28, Feldbericht nach Release v1.17.0):** `AndroidCurrentLocationProvider`
  rief urspruenglich nur `LocationManager.getLastKnownLocation()` auf — ein rein gecachter,
  NIEMALS aktiv angeforderter Wert. Ohne kuerzlich von irgendeiner anderen Stelle (z. B.
  "Geschwindigkeit per GPS", die einzige andere aktive Standortanfrage im Projekt, standardmaessig
  AUS) angefragten Fix blieb der Cache leer und die Aussentemperatur erschien nie, obwohl
  Berechtigung und API-Abruf funktionierten. Jetzt: Cache zuerst (unveraendert, schnell), bei
  leerem Cache je Provider (GPS dann Netzwerk) EIN aktiver `requestSingleUpdate()`-Fix mit
  15s-Timeout und `removeUpdates()`-Aufraeumung bei Abbruch.
- **Test-Falle:** Tests, die einen echten `WeatherViewModel` bauen und sammeln, müssen dessen
  `viewModelScope` in einem `finally` **innerhalb** des `runTest`-Blocks canceln, nicht in `@After`:
  `runTest` leert beim Aufräumen den (per `setMain` geteilten) Scheduler mit `advanceUntilIdleOr`,
  bevor `@After` läuft — ein dann noch aktiver Polling-Loop läuft in virtueller Zeit davon bis zum
  `OutOfMemoryError` (s. `WeatherViewModelTest.runVmTest`).

### Einstellungen: Struktur (seit 2026-10-04, v1.21.0)

Eine flache, gruppierte Seite (`SettingsScreen`) im Sinne der AOSP-Settings-Richtlinie (verwandtes unter einer
Überschrift, Trennlinien, keine gerundeten Karten — passt zu „scharfe Kanten“; Material3 1.4.0 hat ohnehin kein
`SegmentedListItem`): **Verbindung** (gekoppelte Geräte + „Alle gekoppelten Geräte“, Reconnect-Timeout, Basisverzögerung)
· **Dashboard & Anzeige** (Farbschema, Einheiten, Hauptmenü beim Fahren ausblenden, GPS-Geschwindigkeit,
Drehzahl-Blinkgrenze + dynamische Schwelle, Getriebe →) · **Aufzeichnung** (Nur Fahrten mit DTCs) · **Fahrzeug** (Fahrzeuginfo →) · **Info & Wartung** (App-Logs →, App-Info →).
- **Unterseiten** sind `SettingsLinkRow`s (Titel, Einzeiler-Zusammenfassung, Chevron), nie nackte `TextButton`s.
- **Jede Zeile ≥ 64 dp, jeder Schalter ist eine Ganz-Zeilen-Fläche** (`SettingsSwitchRow`: `toggleable` an der Zeile, der
  `Switch` zeigt nur den Zustand; ein `testTag` am Modifier sitzt damit am Knoten mit dem Toggle-Zustand).
- **Segmente ≥ 56 dp** (`ChoiceSetting` und `ThemePickerItem`; der M3-Default wäre 40 dp).
- **„Dashboard konfigurieren“ gibt es nicht mehr als eigene Seite:** die Drehzahl-Blinkgrenze (Slider) und die dynamische
  Schwellenskalierung sind `RpmThresholdSection` in „Dashboard & Anzeige“, gespeist von `DisplaySettingsViewModel`.
- **Eltern-Tab:** `parentTab(route)` (`ui/navigation/ParentTab.kt`, rein) ordnet jeder Unterseite ihren Tab zu; `BottomNav`
  markiert ihn, die Leiste steht auf Unterseiten also nie ohne Auswahl. Ein neuer `Screen` zwingt `ParentTabTest` (exhaustives
  `when`) zu einer Entscheidung. Die Zurück-Beschreibung der Pfeile ist überall `R.string.nav_back_desc`.
- **Erneuter Tipp auf den hervorgehobenen Reiter (seit v1.24.14):** `bottomNavAction(currentRoute, tappedTab)` (`ui/navigation/BottomNavAction.kt`, rein) entscheidet: auf der Startseite des Reiters `Ignore`, auf einer seiner Unterseiten `PopToTabRoot`
  (`popBackStack(route, inclusive = false)`, scheitert das, weil die Startseite nicht auf dem Stapel liegt, wie ein Wechsel), anderer Reiter `SwitchTab` (Zustand sichern/wiederherstellen wie bisher). Vorher tat der Tipp auf einer Unterseite nichts — `restoreState = true`
  stellte genau den Stapel wieder her, den der Tipp gerade gesichert hatte (am Pixel mit der App-Info nachgestellt). Nie wieder `navigate(…)` allein für einen Reiter-Tipp.

### Lösch-Vertrag für lokale Daten (seit 2026-10-04, v1.20.0; Verlauf ergänzt mit Phase 3)

Gilt für jede Liste, die Daten löschen lässt (App-Logs, Verlauf, Fehlerhistorie). **Gelöscht wird
ausschließlich lokal — die App schreibt nie ins Fahrzeug** (kein Fehlerspeicher-Löschen, kein Modus 04).
- **Papierkorb in der Top-Bar** (56 dp, ausgegraut ohne Einträge) → `ConfirmDeleteDialog` (Titel · Folge · Abbrechen/Löschen) →
  die Einträge verschwinden **sofort** → `UndoSnackbarEffect` mit „Rückgängig“ für 10 s (`UNDO_WINDOW_MS`, so lang wie
  `SnackbarDuration.Long`) → erst danach wird wirklich gelöscht.
- **Einen Takt, eine Wahrheit:** `UndoableDeletion<K>` (`ui/components/`, rein, mit virtueller Zeit getestet) besitzt die Uhr; die
  Snackbar zeigt nur seinen Zustand. Nur der **letzte** Löschvorgang ist rückgängig-bar (ein neuer committet den vorigen sofort).
  Die Schlüssel bleiben nach dem Commit noch `DELETE_SETTLE_MS` (300 ms) ausgeblendet, weil Room seinen Flow asynchron neu emittiert
  (sonst flackern gelöschte Zeilen kurz zurück). Verlässt der Nutzer die Seite, wird sofort committet (`onCleared` → `flush()`).
- **Löschen per „bis einschließlich Id“** (`AppLogDao.deleteUpTo(maxId)`), nicht „alles“: Zeilen, die im Fenster neu entstehen,
  überleben. Der Commit läuft im `@ApplicationScope`, damit er das ViewModel überlebt.
- **Einzelne Zeile (Verlauf): Papierkorb ODER Swipe, ohne Dialog, nur Undo.** Nur „Alle löschen“ (Top-Bar) fragt vorher. Das Swipe
  (`SwipeToDeleteRow`, `ui/components/`) ist **nur zusätzlich** zum Button (Prinzip 4): eine Richtung (zum Zeilenende), ab
  50 % der Zeilenbreite — oder mit dem Material3-Schwung beim Loslassen (≥ 125 dp/s, nicht konfigurierbar), Hintergrund `errorContainer` mit Papierkorb nur während des Ziehens. `onDelete` feuert genau **einmal**
  über `SwipeToDismissBox(onDismiss = …)` — **nie** aus `confirmValueChange` (in Material3 1.4.0 deprecated; fragt pro Geste mehrfach ab, gemessen 4×;
  jedes weitere `schedule()` committet den vorigen Löschvorgang sofort und hebelt das Undo-Fenster aus).
- **Verlauf: die laufende Aufzeichnung ist unlöschbar.** `SessionRepository.activeSessionId` (gesetzt von `startSession`, geleert von
  `endSession`/`discardSession`) — `endTime == null` markiert sie nicht verlässlich. Die Zeile zeigt ein Badge „Aufzeichnung läuft“
  (Theme-`tertiary`) statt Papierkorb, ist nicht wischbar, und `deleteSessions` überspringt sie auch dann, wenn ein Aufrufer sie übergibt.
  „Alle löschen“ nimmt beim Bestätigen einen **Schnappschuss der abgeschlossenen Fahrten** (Id-Menge, minus bereits ausstehende) und löscht
  genau diese: eine Fahrt, die im Undo-Fenster endet, wird nicht mitgerissen. Gelöscht wird in Blöcken à 50 (SQLite erlaubt auf API 26
  nur 999 Variablen; zwischen den Blöcken kommen die Schreibzugriffe des Recorders durch).
- **Beenden einer Fahrt ist abbruchsicher** (`withContext(Dispatchers.IO + NonCancellable)`): `ReadingsPipeline` beendet die Fahrt im
  `finally`, und eine Fahrt endet normalerweise DURCH Abbruch (Trennen, Slot-Wechsel, App-Ende); `withContext` wirft in einer bereits
  abgebrochenen Coroutine sofort — vor v1.22.0 bekamen solche Fahrten nie ein `endTime`, und „Nur Fahrten mit DTCs speichern“ verwarf
  sie nicht. Die aktive Id wird vor dem Datenbankzugriff geleert, damit ein Fehler dort keine beendete Fahrt geschützt zurücklässt.

### Automatische Aufbewahrung (seit v1.24.0)

`DataRetention` (`data/`, `@Inject`-Klasse ohne Scope) läuft **einmal je App-Start** aus `Obd2Application.onCreate` im
`@ApplicationScope`: Fahrten älter als 90 Tage (`SessionRepository.deleteOldSessions`: Ids per `SessionDao.idsOlderThan`, in
50er-Blöcken, die laufende Fahrt wird übersprungen), App-Log-Zeilen älter als 30 Tage und das Log auf die neuesten
`AppLogRepository.MAX_ROWS` = 1000 Zeilen (`deleteBeyondNewest`). Jeder Schritt ist „best effort“ (Fehler → WARN im App-Log, die
übrigen Schritte laufen weiter). Früher lief das nur beim Öffnen von Verlauf bzw. App-Logs (`init` der ViewModels — entfernt); wer
die Seiten nie öffnete, hatte unbegrenztes Wachstum. Kein Undo, kein Dialog: es ist Aufbewahrung, kein Nutzer-Löschen (README
nennt die Fristen). Bekannte Grenze: der Schnitt hängt an der Wanduhr — ein stark vorgestellter Geräte-Zeitstempel löscht Fahrten.

### Aufzeichnen ist Beiwerk der Live-Anzeige (seit v1.24.0)

Ein Datenbankfehler beim Anlegen der Fahrt oder beim Schreiben der Messwerte beendet die Anzeige **nicht**: `ReadingsPipeline`
fängt ihn, meldet ihn **einmal je Sitzung** (App-Log WARN „Recording failed …“ + Crash-Reporter) und misst weiter.
`AppLogRepository.log()` wirft nie (nur die Abbruch-Ausnahme des Aufrufers). `SessionRepository.startSession` ist abbruchsicher: ein
bereits abgebrochener Aufrufer legt keine Fahrt an, und wird er während des Inserts abgebrochen, wird die leere Fahrt wieder
verworfen (sonst bliebe eine „aktive“ Geisterfahrt). „Nur Fahrten mit DTCs“ legt bei DTC-freiem Fahrzeug **gar keine Fahrt** mehr an
(statt aufzuzeichnen und am Ende zu verwerfen).

**Was eine Fahrt aufzeichnet (Nutzerentscheidung 2026-10-14, Protokoll Punkt 26):** `SessionRecorder` schreibt **alles, was die Pipeline übergibt**, ohne weiteren Filter: die abgefragten Werte — die vier Kacheln (gefiltert gegen die vom Steuergerät gemeldeten PIDs),
immer Drehzahl und Kühlmittel, bei dynamischer Schwelle die Öltemperatur — und die **Geschwindigkeit**. Die Geschwindigkeit gehört zu jeder Fahrt: meldet das Steuergerät `010D`, fragt `selectPids` sie auch **ohne Kachel** ab; meldet es sie nicht (bei Motorrädern oft: ABS-Steuergerät, nicht auf dem
OBD2-Bus), ergänzt `mergeGpsSpeed` die GPS-Geschwindigkeit (Herkunft `GPS`, Einstellung „Geschwindigkeit per GPS“) — das geschieht unabhängig von den Kacheln. Ohne Discovery-Ergebnis fragt `selectPids` die Geschwindigkeit nicht von sich aus ab (ein Fahrzeug ohne `010D` kostete sonst jeden Zyklus einen vergeblichen Befehl, bei einem Zeitlimit 5 s); dann gilt wie bisher nur die Kachel.
**Der angezeigte Gang steht auch in der Fahrt (Nutzerwunsch 2026-10-14, Protokoll Punkt 27):** je OBD-Zyklus eine Zeile `pid = GEAR`, Wert **1–6 = Gang, 0 = „N“, −1 = nicht bestimmbar („–“)** (`gearRecordValue`, `ui/dashboard/GearRecording.kt` — **die Zahlen nie ändern**, die Auswertung liest sie), `source = DERIVED` (`ValueSource`), Name `Gear` (`DerivedPids.GEAR`, bewusst **nicht** in der `PidRegistry`: sonst Kachelauswahl, Discovery, Abfrage).
Es ist die **Anzeige** der Gang-Box (Hysterese, Anzeige-Halten, GPS-Gegenprobe), keine zweite Berechnung: der Gang entsteht im `DashboardViewModel` (`GearTracker`), das nach jedem OBD-Zyklus `ReadingsPipeline.recordDerived(…)` (die Zeilen des Zyklus mit dessen Zeitstempel) ruft — **nicht** bei den GPS-Zwischenupdates (`SessionEvent.Values.isObdCycle = false`). Das ViewModel schreibt aus einer eigenen Coroutine: die Gang-Zeile steht nicht zwingend direkt hinter ihrem Zyklus (Zeitstempel zählt), und ein Gang, der erst nach dem Sitzungsende ankommt, wird verworfen (`SessionRecorder.end()` schließt die Fahrt). „−1“ ist eine Zeile wie jede andere (Wahl des Nutzers): so ist „Gang unbekannt“ von „kein Zyklus“ zu unterscheiden. Grenzen: ältere Fahrten haben keinen Gang; Radumfang und Übersetzungen der Fahrtzeit werden nicht mitgespeichert; „N“ heißt „steht, Motor läuft“ (auch bei gezogener Kupplung).
**Die angezeigte Farbe des Ölbalkens steht ebenfalls in der Fahrt (Nutzerwunsch 2026-10-14, Protokoll Punkt 28):** je OBD-Zyklus eine Zeile `pid = OIL_BAR`, Wert **0 = rot, 1 = gelb, 2 = grün, −1 = grau** (`oilBarRecordValue`, `ui/dashboard/OilBarRecording.kt` — **die Zahlen nie ändern**), `source = DERIVED`, Name `Oil bar` (`DerivedPids.OIL_BAR`, nicht in der `PidRegistry`). Wie beim Gang ist es die **Anzeige** (`OilBarTracker`: Grün-Hysterese, Entprellen von 120 s, Halten bei fehlendem Wert), keine zweite Berechnung — und sie bleibt auswertbar, wenn sich die Schwellen später ändern. „−1“ (grau) heißt: noch kein Kühlmittelwert in dieser Sitzung; fehlt er in einem späteren Zyklus, gilt die gehaltene Farbe. Die Zeilen von Gang und Balken eines Zyklus gehen in **einem** Aufruf an die Pipeline (`recordDerived`, ein Schreibvorgang; Reihenfolge Gang, Balken); der Timerstand (wie lange schon über 80 °C) wird nicht mitgeschrieben — er folgt aus der Kühlmittelreihe. Grenzen: ältere Fahrten haben keine Farbe; eine Zeile mehr je Zyklus; am Fahrzeug nur durch die Angabe des Nutzers bestätigt, dass der Balken grün wird, nicht durch eine Aufzeichnung.
**Test-Falle:** eine strenge `ReadingsPipeline`-Attrappe (`mockk<ReadingsPipeline>`) muss `recordDerived` annehmen (`DashboardViewModelTest.strictPipeline`; sie sammelt die übergebenen Zeilen in `Driven.recorded`), sonst scheitert die Sammlung der Ereignisse am ersten Zyklus.
**Die Kachelauswahl bietet nur vom Steuergerät gemeldete Werte** (`DashboardViewModel.availablePids`; die Geschwindigkeit bleibt bei eingeschaltetem GPS wählbar; ohne Verbindung oder ohne Liste stehen alle zur Wahl). Mehr Werte in einer Fahrt heißt: den PID als Kachel belegen. Ein pauschales Mitlesen aller unterstützten Werte lehnte der Nutzer ab
(Protokoll Punkt 25: jeder PID kostet rund 0,15–0,28 s je Zyklus, der Zyklus würde von 1,1–1,6 s auf geschätzt 3–6 s wachsen, Drehzahlbalken und Ganganzeige würden träge).
**Entfernt (1.28.0):** der Schalter „Nur vom Fahrzeug gemeldete Werte speichern“ (er nahm Fahrzeugen ohne Geschwindigkeit die GPS-Geschwindigkeit aus der Fahrt und war sonst fast wirkungslos, weil nicht gemeldete Kachel-PIDs bei erfolgreicher Discovery gar nicht erst abgefragt werden) und die Liste „Aufzeichnung filtern“ samt Bildschirm und Route
(ein Haken bei einem nicht abgefragten Wert bewirkte nichts; alle 19 Zeilen standen angehakt da). Die DataStore-Schlüssel `log_only_discovered_pids`, `unlogged_pid_ids` und `logged_pid_ids` stehen bei Nutzern noch im Speicher und werden nicht mehr gelesen — **nicht mit anderer Bedeutung wiederverwenden.** Wer früher Werte abgewählt hatte, sieht sie jetzt wieder
aufgezeichnet, sofern sie abgefragt werden.

### Fehlerzustände ehrlich anzeigen (seit v1.24.1)

- **Ein Fehler gilt nur, solange seine Ursache besteht (seit v1.24.14).** `SettingsViewModel.error` hat zwei Ursachen, die sich nicht gegenseitig aufräumen: ein Fehler beim Laden der Geräteliste (fehlende Bluetooth-Berechtigung; `errorFromDeviceList`)
  endet mit dem nächsten **erfolgreichen** `loadDevices()`; ein Verbindungsfehler endet, sobald eine Verbindung steht — **auf jedem Weg**, auch bei der Wiederverbindung des Dashboards, die `connect()` dieser Seite gar nicht durchläuft (vorher blieb „Verbindung
  fehlgeschlagen“ neben einer stehenden Verbindung stehen). Ein neuer Verbindungsversuch ersetzt beides. Neue Fehlerzustände fragen sich, wer sie wieder löscht.
- **Ein Fehler hat immer einen sichtbaren Text.** Fehlerzustände in ViewModels sind `UiText?` und kommen aus
  `ObdErrorMapper.toUiText(e)` — **nie** aus `e.message`: Eine Ausnahme ohne Meldung (`null`) sah die Oberfläche als „kein Fehler“,
  die DTC-Seite zeigte dann das grüne „Keine Fehlercodes“. Der Mapper lokalisiert bekannte Ursachen (`ObdException`,
  `BluetoothException`, `SecurityException`) und fällt sonst auf Meldung bzw. Klassenname zurück, nie auf leer. Neue Ausnahmetypen
  dort ergänzen (+ Test, + beide `values*/strings.xml`).
- **„Nicht verbunden“ ≠ „nicht verfügbar“.** Seiten, die etwas vom Fahrzeug lesen (Fehlercodes, Freeze-Frame, FIN), prüfen
  `isConnectedFlow` zuerst: ohne Verbindung wird **nichts gelesen** und `R.string.not_connected` gezeigt — ein „keine Daten“ würde
  eine Antwort behaupten, die das Fahrzeug nie gegeben hat. Sie laden per `LaunchedEffect(isConnected)` bei jedem Verbindungswechsel
  neu; weil dabei ein älterer Lesevorgang noch laufen kann, verwirft das ViewModel späte Ergebnisse per Generationszähler
  (`FreezeFrameViewModel`, `VehicleInfoViewModel`).
- **Der Grund steht im Vorschau-Band.** `PreviewBanner(reason)` des Dashboards schreibt den Fehlertext des Zustands aus
  (`previewBannerReason`: nur ohne Verbindung und **nicht** während eines Reconnects — sonst widerspräche das Band dem pulsierenden
  Punkt, weil `startReconnecting()` den alten Fehler bewusst stehen lässt). Farbe `colorScheme.error` (themesicher), Größe `labelLarge`.
  Jeder gescheiterte Reconnect-Versuch steht zusätzlich mit Ursache im App-Log.
  **Seit v1.24.12 nennt das Band den Versuch:** `previewBannerLabel(isReconnecting, attempt, total)` (reine Funktion, `PreviewBannerLabel.kt`) → „Vorschau · Verbindungsversuch 2/5“
  bei laufender Wiederverbindung, „Vorschau · verbinde …“ beim Auto-Connect beim Start (und in dem kurzen Moment zwischen Verlust und erstem Versuch), sonst „Vorschau · nicht verbunden“.
  `DashboardUiState.reconnectAttempt` wurde schon vorher gesetzt, aber von keiner Oberfläche gelesen. Der Koordinator meldet einen Versuch, **bevor** seine Wartezeit beginnt: „1/5“ steht
  also auch während der ersten 3 s (wie die Zeile im App-Log). Bewusst nicht gebaut: „nächster Versuch in X s“ (bräuchte ein neues Ereignis mit der Wartezeit).
- **Beschädigte Einstellungen führen nicht zum Absturz.** `Context.dataStore` hat einen `corruptionHandler`
  (`appPreferencesCorruptionHandler()`: leere Einstellungen = Standardwerte), und `AppPreferences` liest über einen Flow, der
  `IOException` abfängt. Alle Lesezugriffe gehen über `AppPreferences.data`, nie direkt über `dataStore.data`.

### Verbindungs-Entscheidungen gehören unter den Lock (seit v1.24.2)

- **„Schon verbunden?“ wird im Repository unter `connectMutex` entschieden, nie vorher.** `ObdRepository.reconnect()` und
  `connectIfDisconnected(device)` tun nichts, wenn bei Erhalt des Locks eine Verbindung steht; sonst schlösse `connectLocked()` (es
  schließt als Erstes jede bestehende Verbindung) eine frisch hergestellte manuelle Verbindung wieder. Die Pfade des
  `ConnectionCoordinator` (Auto-Connect, Reconnect-Schleife) nutzen nur diese beiden; das manuelle `connect()` aus den Einstellungen
  bleibt unbedingt. Ein Test, der „reconnect baut eine neue Verbindung“ prüft, muss vorher `disconnect()` rufen (so wie es
  Link-Verlust und Watchdog tun, bevor sie melden).
- **Ein Lesepfad trennt nur die Verbindung, auf der er las (PRO-11, seit v1.25.1).** `disconnectIfCurrent(controller)` (privat, `ObdRepository`) tut nichts, wenn `elm` inzwischen ein anderer Controller ist; die Lesepfade (`livePids`: Link-Verlust, 5× BusInit, Watchdog; `DiagnosticReads`: Zeitlimit und Link-Verlust bei Fehlercodes und Freeze-Frame)
  rufen es mit dem Controller, über den sie lasen. Grund: „Verbinden“ zu einem anderen Adapter schließt die alte Verbindung (`connectLocked`) — das blockierte Lesen auf ihr scheitert, will trennen und steht hinter dem Lock; bis es ihn bekam, stand die neue Verbindung, und ein unbedingtes `disconnect()` hätte sie
  wieder eingerissen (Verlust-Meldung, Reconnect). Deshalb setzt `connectLocked` beim Ersetzen **den ganzen Zustand** zurück (`resetLocked()`: Verbindungsstatus, Fahrterkennung, unterstützte PIDs, letzte Werte), denn der alte Leser räumt nicht mehr hinterher auf. Das WUNSCH-Trennen (`disconnect()`, `disconnectByUser()`) bleibt bedingungslos.
  Test-Rahmen: `ObdRepositoryTest.readOnReplacedConnection` baut die Lage nach (Leser wartet auf A, B hält den Lock, A „schließt“, der Leser scheitert, B wird fertig).
- **Nutzer-Trennen ≠ Verbindungsverlust.** `disconnectByUser()` (Benachrichtigung „Trennen“, Einstellungen, „Beenden“) erhöht
  `userDisconnectCount` VOR dem Warten auf den Lock und trennt dann; das interne `disconnect()` (Link-Verlust, Watchdog, Lesefehler)
  zählt nicht. Wer wiederverbindet oder einen Verlust meldet, merkt sich den Zählerstand und vergleicht: Der Reconnect-Trigger trägt den
  Stand zum Zeitpunkt der Aufforderung, `ReadingsPipeline.runSession` den vom Sitzungsbeginn. Ein geänderter Stand heißt „gewollt
  getrennt“ → kein Reconnect, `ConnectionEvent.Aborted` (Dashboard: sauber getrennt, kein Fehler). Neue Stellen, die den Adapter auf
  Wunsch des Nutzers trennen, müssen `disconnectByUser()` rufen — sonst kommt die Verbindung von selbst zurück.
- **Best-effort heißt nicht „alles schlucken“.** `catch (e: Exception)` um Nebensächliches (PID-Discovery beim Verbinden) muss
  `CancellationException` und `ObdException.ConnectionLost` weiterreichen: das erste macht aus einem abgebrochenen Aufrufer einen
  „verbundenen“, das zweite aus einem toten Link.

### Fahrbetrieb: „fährt“, „frisch“ und „veraltet“ (seit v1.24.3)

- **Fahrterkennung = Hysterese** (`ui/dashboard/DriveDetection.kt`, `isMoving`, rein): „fährt“ ab `MOVING_ENTER_KMH` = 5 km/h, „steht“
  erst unter `MOVING_LEAVE_KMH` = 3 km/h; null/NaN/unendlich/negativ = „steht“. Der Zustand lebt als `moving` in `ReadingsPipeline`
  (zu Sitzungsbeginn „steht“); `ObdRepository.setMoving` bekommt nur das Ergebnis. Nie wieder `speed > 0` — GPS-Rauschen im Stand
  schaltete Hauptmenü, Statuszeile und Wetter-Abruf um.
- **Eine Sitzung beginnt leer.** `ReadingsPipeline.events()` ruft beim Start jeder verbundenen Sitzung `resetSession()` (per `onStart`
  auf dem gemergten Fluss): `lastObdReadings`, Zykluszähler, „veraltet“-Marke, Fahrterkennung. Sonst führte der erste GPS-Zwischenwert
  nach einem Reconnect die Werte der vorigen Verbindung neu zusammen.
- **„Veraltet“ (`SessionEvent.Stale`, `DashboardUiState.isStale`).** Kommt `OBD_STALE_AFTER_MS` = 10 s lang kein Zyklus mit Messwert
  (Zähler `obdCycles`, nicht der Vergleich der Listen: ein StateFlow fasst gleiche Werte zusammen), meldet die Pipeline Stale — nur
  solange die Quelle läuft (`sessionRunning`; eine beendete meldet sich auf ihrem eigenen Weg). Wirkung: Fahrterkennung „steht“,
  `gpsRefresh` führt nichts mehr neu zusammen (`obdStale`), das ViewModel leert Readings/Drehzahl/Geschwindigkeit/Gang und setzt
  `isStale`. **Wichtig:** die Marke ist ein eigener Zustand, nicht `lastObdReadings = null` — ein Rückschreiben in den StateFlow, auf dem
  `flatMapLatest` sitzt, bräche die eigene Coroutine ab (dieselbe Reentrancy-Falle wie bei `connectionLostSignal`).
- **Dashboard-Phasen (seit v1.24.4, `ui/dashboard/DashboardPhase.kt`).** `dashboardPhase(isConnected, awaitingFirstValues, isStale)` →
  **Offline** (kein Link: Vorschau-Belegung, Band „Vorschau · nicht verbunden“ ggf. mit Grund) · **Waiting** (Link steht, noch kein Wert:
  Band „Verbunden · warte auf Werte“) · **Live** (Werte fließen — auch wenn keiner zu einem Slot gehört) · **Stale** (Band „Keine aktuellen
  Werte“). Nur Live zeigt Zahlen; sonst `placeholderReadings` mit „--“, `RpmBar(isLive = false)` (kein Alarmrot/Blinken). **Es gibt keinen
  Vollbild-Spinner** (B23): in Live ohne Slot-Wert zeigt jede Kachel ihren Hinweis (`slotPlaceholderRes`) und bleibt per Long-Press bedienbar.
  Der Verbindungs-Punkt hängt am Verbindungsstatus. **„Link steht“ kommt aus `obdRepository.isConnectedFlow`** (`DashboardViewModel.onLinkUp`,
  setzt `isConnected` + `awaitingFirstValues`), nicht erst aus dem ersten `Values`-Ereignis (B24); die Stale-Frist startet mit der Sitzung.
- **Test-Falle:** Tests, die einen Fluss mit `flowOf(...)` als `livePids` nutzen und `advanceUntilIdle()` rufen, sind unproblematisch (eine
  beendete Quelle meldet kein Stale); für „Quelle schweigt“ muss der Fluss offen bleiben (`awaitCancellation()`).

### Berechtigungen sichtbar machen (seit v1.24.4)

- **Eine Berechtigung kann sich außerhalb der App ändern.** `rememberLocationPermissionState()` (`ui/permission/`) liest FINE/COARSE bei jedem
  `ON_RESUME` neu; Aufrufer, die selbst einen Dialog starten, rufen `refresh()` mit dem Ergebnis. Nie `LocationPermissions.hasFineLocation(context)`
  einmal beim Komponieren lesen und weiterverwenden.
- **Fehlt eine Berechtigung, sagt die Oberfläche es** — an der Stelle, die darunter leidet: `PermissionHintRow` (Text in `colorScheme.error`,
  Knopf ≥ 56 dp „In den App-Einstellungen erteilen“, öffnet `appSettingsIntent`) unter dem GPS-Schalter und unter „Navigationsleiste ausblenden“;
  die GPS-Geschwindigkeits-Kachel sagt „Standortberechtigung fehlt“ statt „Warte auf GPS-Signal“. Die Entscheidung „Hinweis ja/nein“ liegt in den
  reinen Funktionen `gpsSpeedPermissionHint` / `weatherPermissionHint` (`ui/settings/LocationHints.kt`, JVM-getestet). Der Schalter selbst wird NICHT
  still umgelegt.
- **Der Knopf öffnet die Systemeinstellungen, nicht den Dialog:** nach zweimaligem Ablehnen zeigt Android ihn nicht mehr.
- **Nachträglich erteilt wirkt sofort:** `GpsSpeedSource` prüft eine fehlende Berechtigung alle `PERMISSION_RECHECK_MS` = 2 s neu (Tests, die ohne
  Berechtigung `advanceUntilIdle()` rufen, laufen sonst endlos — `advanceTimeBy(1); runCurrent()` verwenden).
- **Listen mit Pull-to-Refresh:** Fehler- und Leerzustand gehören in einen scrollbaren Container (`PullableBox` in `DtcScreen`), sonst greift der Zug
  nicht; der Fehlerzustand hat immer einen „Erneut versuchen“-Knopf.

### Verbindungsaufbau: begrenzt und abbrechbar (seit v1.24.6)

- **`ObdRepository.connectLocked` läuft unter einer Obergrenze** (`connectTimeoutMs`, 60 s, `internal var` als Test-Naht): `withConnectDeadline` führt Verbinden, `initialize()`
  und die Discovery aus und lässt einen Wächter die Verbindung **schließen** — bei Fristablauf (Fehler wird `ObdException.Timeout("connect")`), bei Abbruch des Aufrufers
  (sonst bliebe ein blockierendes `connect()` hängen) und über `disconnectByUser()` (`connecting?.close()` VOR dem Warten auf den Lock). `close()` ist der einzige Weg, einen
  blockierenden Socket-Aufruf zu beenden; **`ObdConnection.close()` muss deshalb auch während `connect()` wirken und darf nie werfen.** `ClassicBtConnection` merkt den
  Socket VOR `connect()`, schließt ihn bei Fehlschlag/Abbruch und schluckt `IOException` beim Schließen.
- **Nicht angefasst:** der Lese-Hänger im laufenden Betrieb auf Classic. Ein Schließen nach dem 5-s-Lesezeitlimit würde eine langsame K-Line-Protokollsuche abbrechen, die heute nur
  funktioniert, weil das Lesen dort nicht unterbrechbar ist — erst nach einem Test mit Adapter.
- **Test-Falle:** `runTest` + echte Threads (`FakeObdConnection`, `Dispatchers.IO`) + Frist: Die virtuelle Zeit springt weiter, sobald der Test nur wartet — die 60-s-Frist liefe sofort ab.
  Tests, die NICHT die Frist meinen, setzen `repo.connectTimeoutMs = Long.MAX_VALUE`; Tests mit hängenden Verbindungen nehmen `runTest(timeout = 10.seconds)` und geben ihren Latch
  im Test-Body frei (sonst blockiert ein roter Test sich selbst).
- **Fehlercodes: nur eine positive Antwort ist eine Aussage** (`DtcParser`, A9 Teil 1): `43 00` = „keine Codes“; NO DATA → `ObdException.NoData` (DtcViewModel: „Keine Antwort vom Steuergerät“),
  Busfehler → `BusInit`, alles andere → `UnknownResponse`; Banner („SEARCHING...“, „BUS INIT: OK“) werden ignoriert. Modus 07 ist optional: NO DATA dort wird in `ObdRepository.readDtcs`
  toleriert, ein Busfehler nicht. Der ECU-Simulator antwortet wie ein gesundes Steuergerät (`03` → `43 00`, `07` → `47 00`). Offen bleibt Teil 2 (CAN-Anzahl-Byte, Mehrframe) — braucht ein Roh-Log.
- **Adapter-Init:** `ATS1` (Leerzeichen AN), nicht `ATS0` — alle Parser splitten an Leerzeichen.

### Anzeige-Details (seit v1.24.6)

- **Sparkline:** Ein Punkt je PID nur für einen NEUEN Messwert (`shouldAppendSparklineSample`: neuerer Zeitstempel, mind. `SPARKLINE_MIN_INTERVAL_MS` = 800 ms; ein Zeitstempel vor dem letzten gilt als neu).
  Die GPS-Zwischenupdates wiederholen die letzten OBD-Werte — ohne diese Regel entstanden Duplikate (Treppenkurve, Fenster ~12 s).
- **Dynamische Drehzahl-Schwelle:** `engineTempC` hält die letzte Temperatur, wenn ein Zyklus keine liefert (`rpmThresholdTempScale(null)` fiele auf den Kaltwert); `applyStale()` setzt sie auf null. Die Bänder (`< 50 °C` → 0,50 · `< 70` → 0,75 · `< 85` → 0,90 · sonst 1,00) sind seit v1.25.0 **lückenlos** (`<`-Grenzen statt Float-Bereichen `..49.9f`/`50f..69.9f`, deren Zwischenwerte 49,95/69,95/84,95 in den 1,00-Zweig fielen); NaN gilt als „kein plausibler Wert“ (0,50). Die Blink-Schleife der `RpmBar` läuft nur noch, **solange geblinkt wird** (vorher weckte ein `delay(50)` sie dauerhaft mit 20 Hz).
- **Getriebe-Eingaben:** Grenzen in `GearLimits` (Radumfang 1000–4000 mm, Primär 0,5–10, Sekundär 0,5–15, Gang 0,2–8); `parseGearValue` lehnt „2.096“ bei einem Radumfang ab statt zu raten.
  **Gang-Übersetzung `0` = Gang aus** (`determineGear` und `closeGearPairs` überspringen jeden Wert ≤ 0 — auch einen negativen Altwert aus einer Version ohne Eingabegrenzen, dessen Abweichung `|a − t| / t` sonst negativ und damit „die kleinste“ wäre). Das `GearSettingsViewModel` verwirft unplausible Werte zusätzlich (App-Log WARN).
- **Kachelwert:** `LiveValueText(minFontSize)` schrumpft den Text per `TextAutoSize.StepBased`, bis er passt (Kachel: bis `MIN_VALUE_FONT_SIZE` = 20 sp); die Einheit wird zuerst gemessen
  (`weight(1f, fill = false)` am Wert) und behält ihren Platz. Nie wieder ein still abgeschnittener Wert.

### Sitzung und Vordergrunddienst (seit v1.24.7)

- **Eine Stelle für den Dienst: `ObdServiceController`** (`de.adv990de.obd2`, `@Inject`, ohne Scope). `start()` fordert den Vordergrunddienst an (inkl. Dienst-Typ-Entscheidung
  `shouldIncludeLocationServiceType`), `stop()` beendet ihn, `endSession()` trennt als **Nutzer-Trennung** und stoppt den Dienst (die Trennung läuft in `NonCancellable`
  zu Ende, der Dienst wird auch bei Fehlschlag gestoppt), `endSessionInBackground()` ist dasselbe für Aufrufer ohne eigene Coroutine (im `@ApplicationScope`, wirft nie → App-Log).
  **Niemand sonst** ruft `startForegroundService`/`stopService` oder paart `disconnectByUser()` mit einem Dienst-Stopp — vorher standen Start zweimal (Koordinator,
  `SettingsViewModel`) und Stopp dreimal nebeneinander. Neue Stellen nehmen den Controller.
- **Ein abgelehnter Dienst-Start ist kein Verbindungsfehler (B4).** `catchingForegroundStart { … }` fängt genau `IllegalStateException` (ab API 31 die Oberklasse von
  `ForegroundServiceStartNotAllowedException`, ohne die API-31-Klasse zu referenzieren) und `SecurityException` (dem Typ fehlt eine Berechtigung) — alles andere läuft durch, das
  wäre ein Programmfehler. Vorher lag der Aufruf im `try` des Verbindungsaufbaus: eine STEHENDE Verbindung wurde als „Reconnect-Versuch fehlgeschlagen“ geloggt, `Connected` blieb aus.
  Jetzt steht die Verbindung, das App-Log sagt „Foreground service could not be started: …“, die App läuft weiter (nur ohne Schutz vor dem Beenden im Hintergrund). Dasselbe
  Fangnetz sitzt um `startForeground()` **im** Dienst (`onStartCommand`): eine `SecurityException` dort beendete vorher den Prozess; jetzt → `onServiceStartFailed`, `stopSelf()`.
  **Auf dem Gerät nicht ausgelöst** (die Ablehnung lässt sich nicht erzwingen, ohne die Berechtigung zu entziehen, was den Testprozess beendet): abgesichert nur über JVM-Tests der Entscheidung.
- **Vertrag: die Sitzung (Verbindung + Dienst + Benachrichtigung) lebt genau so lange, wie etwas die Messwerte verbraucht (B1).** Polling und Aufzeichnung leben im
  `viewModelScope` des Dashboards; der Dienst hält nur den Prozess am Leben und besitzt keine Arbeit. Endet die Activity wirklich — `MainActivity.onDestroy` mit
  `shouldEndSessionOnDestroy(isChangingConfigurations)` (= `!isChangingConfigurations`, exakt die Bedingung, unter der `ComponentActivity` den `ViewModelStore` leert: Wegwischen,
  „Aktivitäten nicht behalten“, Zurück am Root auf API ≤ 30) — oder wird die Aufgabe weggewischt (`ObdForegroundService.onTaskRemoved`), beendet das die Sitzung, statt
  eine Verbindung und die Benachrichtigung „Fahrzeugdaten werden aufgezeichnet“ stehen zu lassen, obwohl nichts mehr aufzeichnet. Beides ist idempotent (doppeltes Trennen/Stoppen
  schadet nicht). **Zurück am Root ab API 31 beendet NICHT:** die Aufgabe geht in den Hintergrund, die Activity lebt, der Dienst trägt den Prozess — Aufzeichnung läuft weiter (gewollt).
  Das Antippen der Benachrichtigung öffnet die App (`openAppIntent`: `NEW_TASK | CLEAR_TOP | SINGLE_TOP` → die laufende `MainActivity` kommt nach vorn, keine zweite Instanz mit leerem ViewModel).
  **Bewusst nicht gebaut:** Polling/Aufzeichnung in den Dienst zu verlegen, damit sie das Wegwischen überleben — das wäre ein großer Umbau und eine Produktentscheidung („Wegwischen heißt fertig“ ist die jetzige).
- **Der Dienst-Typ folgt der Einstellung auch bei stehender Verbindung (seit v1.25.0).** Der Typ (`connectedDevice`, mit GPS-Geschwindigkeit + „Immer“ zusätzlich `location`) wurde nur beim Start gewählt; schaltete der Nutzer GPS-Geschwindigkeit ein oder erteilte „Immer“, während
  die Verbindung stand, behielt der Dienst den alten Typ bis zur nächsten Verbindung. `ObdServiceController.refreshServiceType()` ruft `start()` erneut, wenn `shouldRefreshServiceType(laufender Typ, gewünschter Typ, Modus)` es verlangt: nur ein **laufender** Dienst (`ObdForegroundService.runningIncludesLocation != null` — gesetzt nach
  angenommenem `startForeground()`, geleert in `onDestroy`), nur ab API 34 (davor gilt der Manifest-Typ), nie von selbst für einen nicht laufenden. Auslöser: die Einstellung (`keepServiceTypeCurrent`, aus `Obd2Application`, nur bei geändertem Wert) und `MainActivity.onResume` (die Berechtigung hat keinen Flow und ändert sich ausserhalb der App).
  **Eine abgelehnte Aufwertung beendet einen laufenden Dienst nicht** (er läuft mit dem alten Typ weiter, WARN im App-Log); nur eine abgelehnte erste Anforderung ruft `stopSelf()`. **Am Fahrzeug nicht geprüft** — der Wechsel im laufenden Betrieb ist per JVM-Tests der Entscheidung und Geräte-Tests des gemerkten Typs abgesichert.
- **Test-Falle:** Arbeit im `backgroundScope` eines `runTest` läuft mit `runCurrent()`, **nicht** mit `advanceUntilIdle()` — das hört auf, sobald nur noch Hintergrundaufgaben übrig sind, und führt sie nicht aus.

### Touch-Ziele und Barrierefreiheit (seit v1.24.8)

- **Die 56-dp-Regel steht im Theme, nicht an jeder Stelle:** `AppTheme(colorScheme, typography)` (`ui/theme/AppTheme.kt`, genutzt von `MainActivity`) setzt zwei Werte auf `MIN_TOUCH_TARGET` = 56 dp:
  `LocalViewConfiguration.minimumTouchTargetSize` (Standard 48 dp) ist die **Trefferfläche** — das, was der Finger sieht und `SemanticsNode.touchBoundsInRoot` misst — und
  `LocalMinimumInteractiveComponentSize` (Material, Standard 48 dp) reserviert den **Platz** im Layout. **Nur den zweiten anzuheben ändert die Trefferfläche nicht** (gemessen: weiter 48 dp). Gemessene Ausgangswerte vor v1.24.8:
  Touch-Fläche 48 dp (sichtbar 40 dp) bei Button, TextButton, IconButton. Wer ein Touch-Ziel ohne Material-Komponente baut (`clickable`), setzt `heightIn(min = MIN_TOUCH_TARGET)` selbst.
- **Fenster-Komponenten erben die Regel NICHT:** `AlertDialog`, `ModalBottomSheet`, Menüs und `Popup` haben ein eigenes Compose-Root mit der `ViewConfiguration` des Systems (der Exit-Dialog maß dort weiter 48 dp). Wer sie
  benutzt, ruft **`ProvideMinTouchTarget { … }` INNERHALB ihrer Slots** auf. `UiRulesSourceTest` erzwingt das (jede Datei mit `AlertDialog(`/`ModalBottomSheet(`/`DropdownMenu(`/`Popup(`/`Dialog(` nennt `ProvideMinTouchTarget`) und
  prüft, dass `MainActivity` `AppTheme(` statt eines nackten `MaterialTheme(` nutzt.
- **Messen:** `assertHeightIsAtLeast` sieht nur die sichtbare Fläche (ein IconButton meldet 40 dp). Touch-Tests lesen `fetchSemanticsNode().touchBoundsInRoot` (`TouchTargetTest.assertTouchTarget`).
- **Grenze der Barrierefreiheit (Entscheidung des Nutzers 2026-10-12, Protokoll Punkt 24):** belegt sind nur die hier genannten Punkte (Long-Press-Bezeichnung, Kontrollkästchen mit Zustand, Slider, Drehzahlbalken, Warn-Gelb-Kontrast), per Test, **nicht mit TalkBack am Gerät**. Ob der QS-Befund SCR-18 weitere Unterpunkte hatte, ist nicht mehr feststellbar (Prüferbericht nicht erhalten); das ist kein Backlog-Punkt. Ein vollständiges TalkBack-Audit gab es nie.
- **Das Long-Press einer Dashboard-Kachel braucht eine Bezeichnung** (`LiveValueTile(onLongPressLabel)`, `R.string.dashboard_slot_change_action` — gefüllte Kachel UND leerer Slot): es ist die einzige Geste, die einen Slot belegt, TalkBack sagt sonst nur
  „Doppeltippen und halten“. Auswahlzeilen sind `toggleable(role = Role.Checkbox)` mit `Checkbox(onCheckedChange = null)` (TalkBack liest den Zustand; ein nacktes `clickable` meldet ihn nicht). Ein `Slider` trägt `contentDescription`
  (wofür) und `stateDescription` (der angezeigte Wert, „Deaktiviert“ bei 0) — sonst sagt TalkBack nur „60 Prozent“. Der Drehzahlbalken (Canvas) hat eine Beschreibung `Drehzahl N rpm`, auf 100 gerundet (`rpmForSpeech`), damit die 20-Hz-Demobewegung
  TalkBack nicht zum Dauersprechen bringt; ohne Verbindung „Drehzahl nicht verfügbar“.
- **Eine Stelle für Messwert-Zahlen:** `formatSensorNumber(value, rawUnit)` (`obd/UnitConverter.kt`): Volt mit einer Nachkommastelle, sonst ganze Zahl, Gerätelocale; **ein negativer Wert, der auf 0 rundet, zeigt kein Vorzeichen** (`"%.0f".format(-0.4f)` ergibt „-0“).
  Kachel, Slot-Auswahl und Aufzeichnungs-Liste nutzen sie; die Prüfung ist numerisch (|Wert| < halbe letzte Stelle), nicht textuell.
- **Test-Fallen:** (1) Der Inhalt von `AppInfoScreen` scrollt (mit der Zeile „Absturzberichte“ passt er im Querformat nicht mehr auf den Schirm) — Tests scrollen mit `performScrollTo()` zur Zeile. (2) `SettingsScreenDeviceTest.scrollToClearView` schiebt eine Zeile am **Listenende** per
  Rückwärts-Scroll wieder aus dem Sichtbereich — dort genügt `performScrollToNode` (`waitUntilBottomSwitchIs`); eine nicht komponierte LazyColumn-Zeile gibt es im Baum nicht.

### CSV-Export (seit v1.24.8)

`SessionRepository.exportToCsv` (reine Teile in `data/CsvExport.kt`): **Spalten `timestamp,pid,value,name,source`** — die ersten drei unverändert (Positions-Parser laufen weiter), `name` = stabiler englischer PID-Name (`PidRegistry`, leer bei unbekanntem PID), angehängt, weil Excel aus `0105`
beim Doppelklick-Öffnen 105 macht (Anführungszeichen verhindern das nicht; bekannte Grenze, README). **`ORDER BY id`** (`EXPORT_QUERY_SQL`) statt `ORDER BY timestamp`: der Index auf `sessionId` liefert die Einfügereihenfolge ohne Sortierlauf (konstanter Speicher, wie die KDoc behauptete;
`SessionRepositoryDeviceTest` prüft `EXPLAIN QUERY PLAN` auf „kein TEMP B-TREE“); es ist zugleich die Aufzeichnungsreihenfolge. **Jeder Export eine eigene Datei** `session_<id>_<UTC-Zeit auf die Millisekunde>.csv` (`createExportFile`, atomar via `createNewFile`, bei Kollision `-1`, `-2` …) —
vorher löschte ein zweiter Export derselben Fahrt die Datei unter einer noch laufenden Weitergabe. **Aufräumen:** Exporte älter als 24 h (`EXPORT_MAX_AGE_MS`, nur Dateien `session_*.csv`) löscht jeder Export vorab und `DataRetention` einmal je App-Start (`purgeStaleExports`). Ein fehlgeschlagener Export hinterlässt keine halbe Datei.
Felder gehen durch `csvField` (RFC 4180, nur bei Komma/Anführungszeichen/Zeilenumbruch gequotet). `source` (seit v1.25.0) s. „Herkunft eines Messwerts“.

### Absturzberichte (Sentry) zur Laufzeit abschaltbar (seit v1.24.8, B33)

- **Ohne DSN im Build ist Sentry nicht aktiv — und es gibt keinen Schalter** (so sind die ausgelieferten Releases gebaut): `SENTRY_DSN` (Gradle-Property/Umgebung) fließt als Manifest-Placeholder `sentryDsn` ein. Mit DSN: `io.sentry.auto-init` ist **false**; `Obd2Application` startet
  `CrashReportingController.run()` im `@ApplicationScope`, das die Preference `crashReportsEnabled` (Standard **an**, Verhalten für DSN-Builds unverändert) liest und danach `SentryAndroid.init(context)` bzw. `Sentry.close()` aufruft. Der Controller hängt an einer Naht (`SentrySdk`), damit er ohne Android testbar ist.
  **Begründete Grenze:** Ein Absturz in den ersten Millisekunden nach dem Prozessstart, bevor die Einstellung gelesen ist, wird nicht gemeldet — der Preis dafür, dass Sentry **nie** gegen die Einstellung des Nutzers startet (mit Auto-Init könnte genau das passieren).
- **UI:** „Absturzberichte senden“ (Info & Wartung, `SettingsSwitchRow`, nur wenn `CrashReportingSettingsViewModel.available` = DSN eingebaut) und eine Statuszeile in der App-Info (`CrashReportingStatus`: NotIncluded / Off / On).
- **Abhängigkeit:** `sentry-android-core` statt `sentry-android` — das Sammelpaket zog den Native-Handler (`libsentry.so`, `libsentry-android.so`, ~0,5–0,7 MB je ABI) und Replay nach, die die App nicht braucht; das Release-APK wurde dadurch von 5,73 auf 2,92 MB kleiner.
  `CrashReportingConfigTest` hält `auto-init=false`, die Abhängigkeit und die README-Aussage fest. Wer später eine Sentry-Integration (NDK, Replay) braucht, ergänzt sie bewusst — nicht über das Sammelpaket.
- Schriftlizenzen: `docs/licenses/` hält je Schrift den OFL-Text (`LicenseFilesTest`); die App zeigt sie auf der Lizenzseite (s. „Open-Source-Lizenzen in der App“, seit v1.24.9).

### Release-Signatur und Geheimnisse (seit v1.24.7, B31)

- **Der Keystore liegt außerhalb des Projektbaums** (`~/.android-keys/<name>.jks`, Ordner 700, Datei 600); `KEYSTORE_FILE` (Property oder Umgebung, `~/` wird aufgelöst) sagt dem Build, wo.
  Ohne Angabe gilt wie bisher `keystore.jks` im Projekt. **Ein konfigurierter, aber fehlender Pfad bricht den Release-Build ab** (`signingConfig` wird gesetzt, AGPs
  `validateSigningRelease` meldet „Keystore file … not found“) statt still ein unsigniertes APK zu bauen; Debug-Builds sind davon unberührt.
- **`scripts/release.sh` verweigert** den Release, wenn Keystore oder `~/.gradle/gradle.properties` für Gruppe/Andere zugänglich sind (`is_private_file`, vorher Modus 644), und prüft, dass das APK mit
  dem Zertifikat aus `scripts/release-cert.sha256` signiert ist (öffentlicher SHA-256-Fingerabdruck; ein falscher Schlüssel nach einem Umzug ergäbe ein Update, das sich nicht einspielen lässt).
  `apksigner` fehlt → kein Release (vorher stillschweigend „nicht geprüft“). Die Prüfungen stehen in `scripts/release-lib.sh` und werden von `ReleaseScriptLibTest` mit echtem `bash` ausgeführt.
- **Freigabeliste der Claude-Sitzung (`.claude/settings.local.json`, git-ignoriert) — verschärft am 2026-10-12 (Entscheidung des Nutzers):** Die Dateirechte schützen nicht vor der Sitzung (gleicher Benutzer), deshalb sind `python3`, `curl`, `cat`, `keytool`,
  `apksigner sign`, `xargs`, `kill`, `pkill` und der Eintrag für `~/.gradle/gradle.properties` aus den Freigaben entfernt (116 → 106 Einträge), `git push` ist auf `origin main` und `origin v*` verengt, und eine Deny-Liste sperrt `Read`/`Edit` auf `~/.android-keys/**` und
  `~/.gradle/gradle.properties` sowie jeden Bash-Aufruf, dessen Text `.android-keys` oder `/.gradle/gradle.properties` enthält (am Gerät geprüft: `ls -ld ~/.android-keys` wird abgelehnt). **Force-Push gesperrt (seit 2026-10-12, Auftrag des Nutzers):** fünf weitere Deny-Einträge — `git push --force*`, `git push * --force*`, `git push -f*`, `git push * -f*` und `git push * +*` (Refspec mit führendem `+`); `--force-with-lease` fällt unter `--force*`, die Deny-Liste hat damit 11 Einträge. Am Gerät mit `--dry-run` geprüft: alle fünf Formen werden abgelehnt, ein normaler `git push origin main` bleibt erlaubt. **Grenze:** gebündelte Kurzschalter wie `-uf` erfasst das Muster nicht; ein Force-Push bleibt Handarbeit des Nutzers im eigenen Terminal. **Folgen:** `python3` (`scripts/smoke.py`), `kill`/`pkill` und `curl` fragen einzeln nach; Schlüsselnamen von
  `gradle.properties` lassen sich nicht mehr per Befehl anzeigen (`scripts/release.sh` läuft weiter, sein Aufruf enthält diese Pfade nicht). **Grenze:** Deny-Regeln prüfen den Befehlstext, nicht, was ein Skript intern liest — kein Ersatz für Vorsicht. Wer die Liste wieder lockert, schreibt hier den Grund auf.
  **Keystore-Zweitkopie:** legt der Nutzer selbst an (verschlüsselt, Passwortmanager oder Backup); **Stand 2026-10-13: angelegt** — gesichert am 13.10.2026 12:55, Passwörter mitgesichert (Angabe des Nutzers, Entscheidungsprotokoll `docs/qa/2026-10-12-decisions.md`, Punkt 2; der Fingerabdruck der Kopie stimmt laut Nutzer mit `scripts/release-cert.sha256` überein; vorher, am 2026-10-12, gab es keine Zweitkopie). Gesichert werden müssen die Datei, der Alias und beide Passwörter (ohne sie ist die Datei wertlos); ohne Zweitkopie ginge bei Verlust nur Deinstallieren und Neuinstallieren mit neuem Schlüssel (lokale Daten weg), weil Android ein Update nur bei gleichem Schlüssel einspielt. **Prüfen, dass die Kopie echt ist** (im Terminal des Nutzers, Claude fasst den Schlüssel nicht an): `keytool -list -v -keystore <Kopie> | grep 'SHA256:' | tr -d ': \t' | sed 's/SHA256//' | tr 'A-F' 'a-f'` muss dieselben 64 Zeichen liefern wie `cat scripts/release-cert.sha256`.

### Bluetooth: nur klassisch, nur gekoppelt (seit v1.24.9)

- **BLE gibt es nicht.** Die Geräteliste (`BluetoothDeviceScanner.bondedDevices`) bot nie BLE-Adapter an: `scanBle()` hatte keinen Aufrufer, `BleConnection` (286 Zeilen), der BLE-Zweig der `ConnectionFactory` und `BtType.BLE` waren toter Code
  (~370 Zeilen) und wurden entfernt; die README warb trotzdem mit BLE-Adaptern. `BtType` = `CLASSIC | SIMULATOR`. Ein früher gespeichertes Gerät mit dem Typ „BLE“ wird von `AppPreferences.lastDevice` als unbekannt gelesen (kein Auto-Connect).
- **Berechtigungen sind das, was Verbinden braucht:** ab API 31 nur `BLUETOOTH_CONNECT`, bis API 30 nur `BLUETOOTH` (`PermissionGate.requiredPermissions`). `BLUETOOTH_SCAN`, `BLUETOOTH_ADMIN` und der Pflicht-Standort bis API 30
  gehörten nur zum BLE-Scan — wer auf Android ≤ 11 Standort ablehnte, war grundlos ausgesperrt. `BluetoothScopeSourceTest` hält BLE-Code, diese Berechtigungen im Manifest und das README-Versprechen draußen.
  **Auf API ≤ 30 nicht am Gerät geprüft** (das Testgerät ist API 37; ein Test auf älteren Android-Versionen ist nach Entscheidung des Nutzers vom 2026-10-12 nicht notwendig und steht nicht im Backlog).

### Start ohne Blitz (seit v1.24.9)

Theme und Kachel-Belegung kommen asynchron aus dem DataStore, und die Einstellung wird erst während der ersten Komposition abonniert: das erste Bild zeigte kurz das Standard-Theme bzw. leere Kacheln. **`seedBlocking(default, timeoutMs, source)`**
(`data/StartupSeed.kt`) liest den ersten Wert einmal synchron (am Pixel gemessen: ~3 ms für den ersten Zugriff beim Kaltstart; Obergrenze 250 ms, bei leerer/werfender/hängender Flow gilt der Standard) — als **Startwert**, nicht als Quelle der Wahrheit.
Genutzt in `MainActivity.onCreate` (Theme → `collectAsState(initial = …)`) und im `DashboardViewModel` (Slot-Belegung → Startwert von `slotPids` und `placeholderReadings`; vorher `emptyList()` bzw. Standard). Eine neue Einstellung, die das erste Bild bestimmt, wird genauso geseedet. Das Fenster VOR dem ersten Frame (System-Splash) regelt seit v1.24.10 der nächste Abschnitt.

### Statusleiste und System-Splash (seit v1.24.10)

- **Die Symbole der Statusleiste folgen dem Farbschema der App, nicht dem Systemmodus.** `AppTheme` ruft `StatusBarIconsEffect(colorScheme)` (`ui/theme/SystemBars.kt`): `useDarkStatusBarIcons` = Leuchtdichte des Schema-Hintergrunds > 0,5, gesetzt über
  `WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars` (`true` heißt dunkle Symbole). Vorher kam die Farbe vom `DayNight`-Theme: helles System + Cockpit-Schema (fast schwarz) ergab dunkle Symbole auf Schwarz, dunkles System + helles
  Standard-Schema weiße auf Hell (am Pixel mit „Standard + Hell“ bei dunklem System nachgestellt und behoben). **Nur ab API 35** (`controlsStatusBarIcons`, `EDGE_TO_EDGE_ENFORCED_SDK`): erst dort ist die Leiste durchsichtig und die App zeichnet dahinter (targetSdk 36 erzwingt es);
  davor hat sie eine eigene, undurchsichtige Theme-Farbe, und erzwungene Symbole könnten dort dunkel auf Schwarz werden — **auf API ≤ 34 nicht geprüft** (kein Emulator unter API 37 installiert; ein Test auf älteren Android-Versionen ist nach Entscheidung des Nutzers vom 2026-10-12 nicht notwendig). Die Navigationsleiste fasst die App nicht an (ihr Griff passt sich ab Android 10 selbst an).
- **Der System-Splash (Android 12+) zeigt die Hintergrundfarbe der App statt des Theme-Graus.** Er entsteht vor jedem App-Code und liest nur Ressourcen; gemessen war er `#303030` (das neutrale `DayNight`-Grau). Zwei Ebenen: (1) **statisch** — `res/values/splash_colors.xml` (+ `values-night/`),
  `Theme.OBD2Monitor` setzt `android:windowBackground` auf `@color/splash_background_system` (hell am Tag `#FEF7FF`, dunkel `#141218` = Hintergrund des Default-Schemas; der Splash nimmt `windowBackground`, wenn es eine einzelne Farbe ist; vor Android 12 ist es das, was vor dem ersten Frame steht);
  (2) **je Farbschema** — `Theme.OBD2Monitor.Splash.{Light,Dark,Blue,Amber,Green}` malen die Hintergrundfarbe des jeweiligen Schemas, `MainActivity` setzt das passende per `Activity.splashScreen.setSplashScreenTheme(splashThemeRes(theme))` (`ui/theme/SplashTheme.kt`, ab API 31) bei jedem Start und jeder Änderung des Farbschemas
  — **für den NÄCHSTEN Start** (das System persistiert es app-weit); „wie das System“ und Unbekanntes setzen es zurück (`Resources.ID_NULL`), dann gilt das Manifest-Theme mit den `-night`-Ressourcen. **Die Namen der Splash-Themes persistiert das System — nie umbenennen** (Javadoc: sonst werden sie nach einem Update nicht gefunden); `SplashThemeTest` hält die Namen fest
  und dass jede Splash-Farbe die `background`-Farbe des echten Schemas ist (Kotlin-Schema und XML können nicht auseinanderlaufen).
- **Messen des Splash am Gerät:** Der Splash steht nur wenige Millisekunden (Release: 153–170 ms bis zur Anzeige, gemessen). Ein Bild direkt nach `am start` zeigt meist die Startanimation über dem Homescreen oder — **liegt der Task noch in den zuletzt benutzten Apps — dessen letzten Schnappschuss** statt des Splash (Android nimmt dann
  das Schnappschuss-Fenster). Brauchbar: Task aus der Übersicht wischen, dann `am start -D …` (hält den Start vor dem ersten Frame an, der Splash bleibt stehen; der Systemdialog „Waiting For Debugger“ dunkelt ihn gleichmäßig ab, der Faktor ≈ 0,35 ist aus dem Standard-Schema kalibriert) und die Hintergrundfarbe samplen.
  Dasselbe Schnappschuss-Fenster zeigt Nutzern mit einer noch in der Übersicht liegenden App deren letztes Bild — dann spielt die Splash-Farbe keine Rolle.

### Standort-Cache mit Altersprüfung (seit v1.24.9)

`AndroidCurrentLocationProvider.lastKnownLocation()` nimmt den **jüngsten** gecachten Fix (GPS oder Netzwerk), sofern er höchstens `CACHED_FIX_MAX_AGE_MS` = 10 min alt ist (`freshestFix`, reine Funktion in `location/LocationFreshness.kt`; Alter über `elapsedRealtimeNanos`,
die monotone Uhr; negatives Alter = ungültig). Sonst ein aktiver Fix (GPS, dann Netzwerk, je 15 s); **scheitert auch der, gibt es keinen Standort** — lieber keine Außentemperatur als eine für die falsche Gegend (vorher zählte jeder gecachte Fix, der GPS-Cache schlug einen
frischen Netzwerk-Fix, und der Cache friert ein, solange keine App einen Standort anfordert). Das Halten des letzten guten Werts (30 min, `holdOutsideTemp`) bleibt davon unberührt.

### Wetter-Client und Repository-Hygiene (seit v1.24.11)

- **`OpenMeteoApi` ist gegenüber dem Dritten begrenzt (SEC-9):** `User-Agent` = `weatherUserAgent(BuildConfig.VERSION_NAME)` („OBD2Monitor/1.24.11“; ohne Version „OBD2Monitor“) und `Accept: application/json` — der JVM-Standard „Dalvik/…“ nennt keinen Ansprechpartner. Die Antwort
  wird mit `readBounded` höchstens `MAX_RESPONSE_BYTES` = 16 KiB weit gelesen (Bytes **nach** dem Entpacken, nicht Zeichen; die echte Antwort hat rund 400 Byte); ein größeres `Content-Length` wird gar nicht erst geöffnet, mehr als die Grenze ergibt `null` wie jeder andere Fehler.
  **Nie eine Schleife mit Lesen der Länge 0** (`read(buf, 0, 0)` liefert 0, nie −1): `readBounded` liest höchstens `maxBytes + 1 − gelesen` ≥ 1 Byte.
- **Nichts Persönliches im Git (SEC-11):** `.idea/` ist ganz ignoriert (`RepositoryHygieneTest`: Eintrag in der `.gitignore`, keine getrackte IDE-Datei, keine `serial=…` in getrackten Dateien). Ignorieren wirkt nur auf Dateien, die nicht im Index stehen — eine
  neu getrackte IDE-Datei bricht den Test. Die Seriennummer des Testgeräts steht noch in der Historie (Commits ab Februar 2026); sie zu tilgen hieße `git filter-repo` mit neuen SHAs und Tags, bewusst nicht getan (privates Repository). **Entscheidung 2026-10-12:** erst tilgen, wenn das Repository öffentlich wird — das ist die **Vorbedingung jeder Veröffentlichung** (Force-Push, Verweise auf alte SHAs in Doku/Memory neu setzen). Stand 2026-10-12: keine Veröffentlichung absehbar (Nutzer); Messung und Empfehlung (bereinigte Kopie in ein neues Repo statt Umschalten) in `docs/qa/2026-10-12-decisions.md`, Punkt 3.
- **`uses-feature android.hardware.bluetooth` (`required="true"`, DAT-17):** ohne Bluetooth liefert `getSystemService(BLUETOOTH_SERVICE)` null, und `AppModule.provideBluetoothAdapter` wirft beim Aufbau des Hilt-Graphen. Die Deklaration hält solche Geräte aus Stores und Installern heraus
  (`BluetoothScopeSourceTest`). **Bekannte Grenze:** ein von Hand (adb) installiertes Exemplar auf einem Gerät ohne Bluetooth stürzt weiter beim Start ab — die App ist dort ohnehin nutzlos; ein nullbarer Adapter durch drei Verbraucher wäre mehr Code als Nutzen.

### Herkunft eines Messwerts (seit v1.25.0)

- **Die GPS-Geschwindigkeit trägt dieselbe PID-ID wie die des Fahrzeugs** (`GPS_SPEED` = `PidRegistry.SPEED` mit eigenem Label, damit sie in den Geschwindigkeits-Slot fällt). Ohne weitere Angabe war in Aufzeichnung und CSV nicht mehr zu erkennen, welche Quelle ein Wert hatte — für die
  Auswertung einer Testfahrt (Gang-Toleranzen, Vergleich ECU gegen GPS) entscheidend. `SensorValue.source: ValueSource` (`ECU` als Standard, `GPS`); `mergeGpsSpeed` markiert, was es ergänzt.
- **Datenbank v4:** `sensor_readings.source` (`TEXT`, **nullable, ohne Vorgabewert**), `MIGRATION_3_4` = `ALTER TABLE … ADD COLUMN source TEXT` (von Hand, in `ALL_MIGRATIONS`; `AppDatabaseMigrationTest` prüft Form, Erhalt der Altdaten und das Schreiben über das DAO). Zeilen aus der Zeit vor 1.25.0 bleiben
  `NULL` = **unbekannt**: ihre Herkunft kennt niemand, `ECU` zu raten wäre eine Behauptung. Der CSV-Export schreibt für sie ein leeres Feld. Ein früher exportiertes, nie ausgeliefertes `4.json` (Kopie von 3.json, Commit 84e35f6) wurde vom echten Schema ersetzt.
- **Die Namen `ECU`/`GPS`/`DERIVED` stehen als Text in Datenbank und CSV — nie umbenennen** (`ValueSource.name`; `DERIVED` seit 2026-10-14 für berechnete Werte, heute der Gang und die Ölbalken-Farbe, s. „Was eine Fahrt aufzeichnet“). Keine Migration nötig: `pidId` und `source` sind Text. Das CSV hat jetzt fünf Spalten `timestamp,pid,value,name,source`; die ersten vier sind unverändert, ein Parser nach Position läuft weiter.
- **Rückweg auf ältere APKs:** wie schon seit v1.23.0 (Datenbank v3) gibt es keinen Downgrade-Pfad — ein APK ≤ 1.24.x kann eine v4-Datenbank nicht öffnen; nur mit gelöschten App-Daten.

### Zeitlimit-Richtlinie und Altbytes (seit v1.25.0, PRO-13)

- **Ein Zeitlimit lässt den Datenstrom in unbekanntem Zustand:** die Antwort kann später eintreffen und würde beim nächsten Befehl für dessen Antwort gehalten (der ELM327 kennt keine Antwort-Kennung), jede weitere wäre um eins verschoben. Die Richtlinie je Lesepfad steht **einmal**, als KDoc an
  `ObdRepository`: Live-Abfrage überspringt den PID (Watchdog), **Fehlercodes und Freeze-Frame** brechen beim ersten Zeitlimit ab, **trennen** und reichen es weiter, die **FIN** trennt bewusst nicht (die Pipeline fragt sie bei jedem Verbinden — ein Motorrad, das auf Modus 09 schweigt, geriete in
  eine Schleife aus Verbinden, Zeitlimit, Trennen). Vorher lief die Freeze-Frame-Schleife mit allen ~40 PIDs weiter (bei einem stillen Adapter 40 × 5 s) und meldete „keine Daten“. Ein neuer Lesepfad entscheidet sich für eine der drei Formen und schreibt sie in diese KDoc.
- **`ElmController.sendCommand` verwirft vor jedem Befehl Altbytes** (`discardStaleInput`): den Rest des eigenen Lesepuffers hinter dem letzten Prompt und alles, was `available()` meldet — der ELM327 antwortet nur auf Befehle, was schon da ist, gehört zu einem früheren. Ein Fehler beim Fragen nach `available()` wird dort
  nicht gemeldet (der folgende Schreibzugriff tut es als `ConnectionLost`). **Grenze:** Bytes, die erst NACH dem Absenden eintreffen, erwischt das nicht (Protokollgrenze); dort fängt die Auswertung das Echo des PID-Bytes ab. **Am Fahrzeug nicht geprüft** — nur per Test (Mutation: ohne das Verwerfen scheitern zwei Tests).
- **Test-Falle:** Ein Adapter-Fake, der alle Antworten schon VOR dem ersten Befehl im Eingang liegen hat (`ByteArrayInputStream` mit allem), verträgt das Verwerfen nicht. `ElmControllerTest.ScriptedAdapter` gibt eine Antwort erst frei, wenn ein Befehl geschrieben und geflusht wurde — wie der echte Adapter;
  `stale =` legt die verspätete Antwort eines früheren Befehls vorab in den Eingang.
- **Ende des Datenstroms vor dem Prompt ist ein Verbindungsverlust (seit v1.27.0, targetSdk 37):** `ElmController.readResponse` wirft `ConnectionLost`, sobald `read()` -1 liefert — **auch wenn schon ein Teil der Antwort da war**. Ab Android 17 und `targetSdk` 37 liefert `read()` auf einem RFCOMM-Socket beim Abriss der Verbindung -1 statt einer
  `IOException` (BluetoothSocket-Änderung für Ziel 37). Bis 1.26.0 wurde -1 ohne ein einziges Byte schon als Verlust gemeldet, eine **halbe Antwort** (Bytes da, Prompt fehlt) aber als Ergebnis zurückgegeben — der Abriss fiel erst beim nächsten Befehl auf. Eine Antwort endet mit `>`; das Ende des Datenstroms davor ist nie eine Antwort.
  Tests: `ElmControllerTest` (halbe Antwort → `ConnectionLost`; eine vollständige Antwort bleibt eine Antwort, auch wenn der Strom danach endet). **Am echten Adapter nicht geprüft** (das Fake bildet -1 nach). Ein vorab gefüllter `ByteArrayInputStream` taugt für so einen Test nicht (das Verwerfen der Altbytes frisst ihn) — `ScriptedAdapter`.

### Zustand sammeln, Drehung und Vorschauen (seit v1.25.0)

- **Die Oberfläche sammelt mit `collectAsStateWithLifecycle`, nie mit `collectAsState`** (`CodeHygieneSourceTest`): sie sammelt nur zwischen STARTED und STOPPED. Das ist zulässig, **weil kein ViewModel dieser App für seine Arbeit auf einen Sammler der Oberfläche angewiesen ist** — Polling und Aufzeichnung laufen im
  `viewModelScope` bzw. im `@ApplicationScope`, die `stateIn(…, WhileSubscribed(5_000), …)`-Flüsse speisen nur Anzeige, und das Wetter-Polling war schon lifecycle-bewusst (`BottomNav`). Wer Arbeit an `WhileSubscribed` hängt, hängt sie an einen Sammler: dort gehört `Eagerly` bzw. ein eigener Scope hin, nicht ein Rückfall auf
  `collectAsState`. Wirkung: im Hintergrund keine Neukomposition mehr durch Zustandsänderungen.
- **Eingaben und Dialoge überleben eine Drehung (UID-23):** `rememberSaveable` für `pickerSlot` (Slot-Auswahl), `showExitConfirmation` (Beenden-Dialog) und beide Zustände von `GearRatioField` (`text`, `hasBeenEdited` — nur zusammen, sonst überschriebe das `LaunchedEffect` den wiederhergestellten Text). Bewusst **`remember`** bleibt
  `PermissionGate.allGranted`: es ist eine Momentaufnahme des Systems, bei jedem `ON_RESUME` neu gelesen; ein gespeicherter Wert zeigte nach einer Drehung eine inzwischen entzogene Berechtigung als erteilt. Geprüft per `StateRestorationTester` (nicht per echter Rotation): `GearRatioFieldUiTest`, `BottomNavTest`, `DashboardScreenTest`.
  Die Rotation selbst baut das Gerät nicht von Hand: der Nutzer dreht physisch.
- **Datum und Uhrzeit in Listen (SCR):** `ui/DateTimeFormats.kt` — `formatDate` (`12.10.2026`), `formatDateTime` (ohne Sekunden, Verlauf), `formatDateTimeSeconds` (App-Logs), nach der Sprache des Geräts (`FormatStyle`), Sprache und Zeitzone als Parameter. Vorher standen drei feste `dd.MM.yyyy`-Muster nebeneinander, eines einmal beim
  Laden der Klasse gebaut (eine Sprachänderung erreichte es nie). Die Kopfzeile des Dashboards (`formatDashboardHeader`, „Mo 12.10. · 14:30“) ist ein eigenes, kompaktes Format. Geräte-Tests vergleichen mit denselben Funktionen, die Funktionen selbst haben JVM-Tests mit festen Erwartungen.
- **`@Preview` (CLAUDE.md „Bei UI-Änderungen IMMER“) ist jetzt prüfbar:** `CodeHygieneSourceTest` verlangt für jede Datei mit Composables eine `@Preview` **oder** einen Eintrag mit Begründung in `previewExempt` (ViewModel-gebundene Screens, reine Effekte, Fenster); ein Eintrag, der inzwischen eine Vorschau hat, muss wieder raus. Rahmen:
  `PreviewTheme(dark)` (`ui/theme/PreviewSupport.kt`: helles Standard-Schema und dunkles Cockpit-Blau). Vorschauen für ViewModel-Screens gibt es nicht — ihre zustandslosen Bausteine tragen sie (`DtcRow`, `SessionRow`, `DeviceRow`, …).
- **Die Vorschauen werden auf dem Gerät gerendert (seit v1.25.2):** `PreviewsRenderDeviceTest` komponiert jede `@Preview`-Funktion in einer eigenen Test-Umgebung (`runAndroidComposeUiTest`, Uhr angehalten wegen endloser Animationen) und
  verlangt, dass sie weder wirft noch nichts zeichnet (Fenster-Vorschauen = Dialoge: eine zweite Wurzel mit Inhalt). **Gefunden werden sie nach Form**, nicht nach der Annotation (BINARY, zur Laufzeit nicht lesbar): statische Methode
  `Preview…(Composer, int)` in einer `…Kt`-Klasse des APK (`DexFile`, `getDeclaredComposableMethod`). `CodeHygieneSourceTest` erzwingt die Form (**`private fun Preview…()` ohne Parameter**; umgekehrt heißt eine parameterlose Funktion, die keine
  Vorschau ist, **nicht** `Preview…` — Hilfs-Composables `…PreviewContent`) und dass `PREVIEW_COUNT` im Geräte-Test (63) die Zahl der Quelltexte ist: wer eine Vorschau ergänzt oder entfernt, zieht die Zahl dort nach.
  Mit dem Instrumentation-Argument `previewShots=true` schreibt der Test je Vorschau ein Bild nach `files/preview_shots/` der Debug-App (nicht für Dialoge). **Falle:** `connectedDebugAndroidTest` deinstalliert die Debug-App danach, die Bilder sind
  weg — direkt laufen lassen: beide APKs per `adb install -r -t` (`app-debug.apk`, `app-debug-androidTest.apk`), dann `adb shell am instrument -w -e class de.adv990de.obd2.ui.PreviewsRenderDeviceTest -e previewShots true de.adv990de.obd2.debug.test/de.adv990de.obd2.HiltTestRunner`,
  Bilder per `adb exec-out run-as de.adv990de.obd2.debug cat files/preview_shots/<Name>.png`. Das Rendern fand zwei falsche Vorschauen (Kachel ohne Höhe füllte das Fenster und verdrängte die zweite Kachel und das Band; Einstellungszeilen ohne Seitenrand) — beide behoben.
- **Wo was liegt (große Dateien aufgeteilt, v1.25.0, reine Verschiebungen im selben Paket):** `RpmBarLogic.kt` (Blinkperiode, Segmentfarbe, Temperaturbänder, Öl-Balken- und Gangfarbe), `DashboardComponents.kt` (`RpmBar`, `OilTemperatureBarStrip`), `SlotComponents.kt` (Kachel, Vorschau-Band, Slot-Auswahl),
  `DashboardUiState.kt`, `SessionEvent.kt` (+ `OBD_STALE_AFTER_MS`), `SettingsScreenSupport.kt` (Test-Tags, reine Entscheidungen), `SettingsSections.kt` (Gruppen Aufzeichnung/Fahrzeug/Info als `LazyListScope`-Erweiterungen), `DeviceRow.kt`, `ThemePickerItem.kt`, `data/PidDiscovery.kt`.
  **Die drei letzten Dateien über 400 Zeilen sind danach aufgeteilt worden** (v1.25.1, s. „Aufteilung der großen Klassen“): keine Datei ist mehr länger als 400 Zeilen.
- **Aufteilung der großen Klassen (v1.25.1, keine Datei > 400 Zeilen):** `ObdRepository` (391) hält Verbindung, Zustand und die laufende Abfrage; die einmaligen Lesepfade (Fehlercodes, Freeze-Frame, FIN) stehen in `data/DiagnosticReads.kt` (`internal`, bekommt den `ElmController` je Aufruf und meldet
  Fehler an `onLinkBroken`), das Repository reicht sie durch — die öffentliche Signatur ist unverändert. `ReadingsPipeline` (378): die Aufzeichnung einer Sitzung ist `SessionRecorder` (Fahrt anlegen, schreiben, beenden; ein Datenbankfehler wird einmal je Sitzung gemeldet), die PID-Auswahl die reine Funktion `selectPids` (`PidSelection.kt`).
  `DashboardViewModel` (367): `SparklineBuffer` (Fenster, Mindestabstand, Leeren je Verbindung), `OilBarTracker` (Öl-Balken samt Timer-Zustand im `SavedStateHandle` — **die Schlüsselnamen sind persistierter Zustand, nie umbenennen**) und `GearTracker` (Gang, Anzeige-Persistenz, „N“, 5-s-Gegenprobe mit dem GPS; `GearReading`).
  Die Begründungen, die vorher als Kommentare im ViewModel standen, wanderten mit. Alle haben eigene Tests (`SessionRecorderTest`, `PidSelectionTest`, `SparklineBufferTest`, `OilBarTrackerTest`, `GearTrackerTest`); die bestehenden ViewModel-/Pipeline-/Repository-Tests liefen unverändert grün.
- **Test-Klassen:** Was ein Gerät braucht, endet auf `DeviceTest`; kein Klassenname kommt in `test` und `androidTest` doppelt vor (`CodeHygieneSourceTest`). Die Pläne und Spezifikationen umgesetzter Funktionen liegen in `docs/superpowers/archive/`.

### Anzeige-Fixes (seit v1.24.12)

- **Sparkline gehört zu einer Verbindung (UID-16):** `DashboardViewModel.clearSparklines()` leert Puffer **und** Zeitmarken bei Idle, Verbindungsverlust (`startReconnecting`) und Sitzungsfehler — vorher hängte der erste Punkt nach einem Reconnect
  an die Kurve von vorher. Die Zeitmarke geht mit, sonst verschluckt der 800-ms-Mindestabstand den ersten neuen Punkt. Bei „veraltet“ (Stale) bleibt die Kurve (die Verbindung steht).
- **„Beenden“ läuft immer zu Ende (UID-20):** `ObdServiceController.endSessionSafely()` fängt jeden Fehler beim Trennen (App-Log WARN), reicht nur die Abbruch-Ausnahme weiter und stoppt den Dienst auch bei Fehlschlag; `ExitAppViewModel.exitApp()` und
  `endSessionInBackground()` nutzen sie. Vorher beendete eine Ausnahme aus dem Trennen die Komposition-Coroutine (Prozess-Absturz) und `finishAffinity()` lief nie.
- **`useGpsSpeed` mit `distinctUntilChanged` (DAT-13):** `ReadingsPipeline` startet den Standort-Listener per `flatMapLatest` auf diesem Flow neu — jeder DataStore-Schreibvorgang (u. a. `persistDevice` bei jedem manuellen Verbinden) tat das vorher mit unverändertem Wert.
  Die übrigen Einstellungs-Flows, die nur Anzeige speisen, sind `StateFlow`s (fassen gleiche Werte selbst zusammen) und blieben unverändert.
- **Standort-Hardware ist optional:** `uses-feature android.hardware.location` und `….gps` mit `required="false"` — die Standort-Berechtigungen deklarieren sie sonst implizit als Pflicht (`aapt2 dump badging`: `uses-implied-feature`).
- **Smoke-Test im Repo:** `scripts/smoke.py <versionName> <versionCode>` (uiautomator, Gerät entsperrt; `SmokeScriptTest` hält ihn vorhanden, übersetzbar und im Release-Ablauf genannt). Er lag bis 1.24.11 nur im Scratchpad einer Sitzung und ging verloren. **`--simulator` (seit 2026-10-12):** prüft zusätzlich den Datenfluss gegen „OBD Sim“ des Debug-Builds (Paket `de.adv990de.obd2.debug`, `BLUETOOTH_CONNECT`/`POST_NOTIFICATIONS` erteilt): Verbinden in der Simulator-Zeile der Einstellungen, Dashboard mit Verbindungspunkt „verbunden“ + Drehzahl als Zahl und ohne Band (Vorschau/warte/veraltet), „Trennen“, danach 10 s lang kein Reconnect. Die Entscheidungen sind reine Funktionen in `smoke.py` mit eigenen Python-Tests (`scripts/test_smoke.py`, vom JVM-Test `SmokeScriptTest` ausgeführt); `row_button` nimmt nie die Schaltfläche einer Nachbarzeile. Gegen den Simulator, **nie gegen ein Fahrzeug**; im Release-Build gibt es keinen Simulator (`--simulator` verweigert dort).
- **Entschieden am 2026-10-12 (`docs/qa/2026-10-12-decisions.md`):** UID-21 (RPM-Skala fest 10 000 — die Blinkgrenze lässt sich nur bis 10 000 einstellen) **bleibt**, keine neue Einstellung; der Tacho-Sweep (`DashboardScreen`, `shouldStartRpmDemoSweep`) läuft
  **nur einmal je App-Start** (umgesetzt in v1.24.13, s. „Tacho-Sweep“ unten); Polling und Aufzeichnung **bleiben im ViewModel** („Wegwischen heißt fertig“, s. „Sitzung und Vordergrunddienst“).
- **Produktentscheidungen, zweite Runde (2026-10-12, `docs/qa/2026-10-12-decisions.md`, Punkte 9–13):** die Beschreibungen der Fehlercodes werden übersetzt (s. „Fehlercode-Texte“); „imperial“ bleibt bei km/h → mph und °C → °F; die Ganganzeige bleibt bei **einem** Satz Getriebedaten;
  die Toleranzen 4 % / 6 % und die Eingabeart der Übersetzungen blieben ohne Präferenz und sind **unverändert** (Toleranzen: drei Fahrten am 2026-10-13 ausgewertet, `docs/qa/2026-10-13-gear-tolerances.md` — die Daten tragen keine Änderung von 4 % / 6 %, aber eine Verschiebung der Ist-Übersetzung um +2,5…5,6 % (Radumfang ~2008 statt 2096 mm; der Nutzer hat 2060 mm gemessen, damit bleiben +1,1…2,9 % Rest) und das Paar 5./6. Gang ist unbelegt; Entscheidung steht aus; **Eingabeart: Bruch im Feld wie `42/16` nicht umsetzen, niedrigste Priorität** — Nutzer, 2026-10-12).
- **PRO-11 ist umgesetzt** (v1.25.1, s. „Verbindungs-Entscheidungen gehören unter den Lock“): Lesepfade trennen nur die Verbindung, auf der sie lasen. Das bewusste „ein Zeitlimit bei Fehlercodes/Freeze-Frame trennt“ bleibt.

### Tacho-Sweep: einmal je App-Start (seit v1.24.13)

- **Die Demo-Animation 0 → 10 000 → 0 rpm ist eine Start-Animation** (Entscheidung 2026-10-12). `DashboardViewModel.consumeDemoSweepOffer()` liefert `true` genau beim ersten Aufruf, danach `false`; `DashboardScreen` ruft es nach der 400-ms-Anlaufzeit und
  spielt nur ab, wenn zusätzlich `shouldStartRpmDemoSweep(rpm)` gilt (Drehzahl noch 0). Die beiden Fragen sind getrennt und einzeln getestet: „schon gezeigt?“ (ViewModel) und „Drehzahl schon da?“ (reine Funktion).
- **„App-Start“ = Lebensdauer des `DashboardViewModel`:** Tabwechsel und Drehung spielen nichts neu ab (der Dashboard-Eintrag bleibt wegen `popUpTo(start) { saveState = true }` auf dem Back-Stack; vorher lief der Sweep bei jedem Betreten und jeder Drehung, weil er an der Komposition hing).
  Wegwischen und Neustart spielen ihn ab; ein Systemkill mit Wiederherstellung zählt als neuer Start — das Feld liegt bewusst **nicht** im `SavedStateHandle`. Verbindungswechsel (Idle, Verlust, Fehler) setzen es nicht zurück (Test).
- **Verbraucht ist das Angebot mit der ersten Prüfung, nicht erst mit dem Abspielen:** lag beim Start schon eine echte Drehzahl an, wird der Sweep nicht später mitten in der Sitzung nachgeholt (Motor aus, Zündung an, Rückkehr zum Dashboard).
  Verlässt der Nutzer das Dashboard innerhalb der 400 ms, ist noch nichts geprüft; dann läuft er beim nächsten Betreten.
- **Folge:** Ohne gesetzte Drehzahl-Blinkgrenze (0) zeigt der Sweep die Schaltblitz-Vorschau über 7 000 rpm — die sieht man jetzt nur noch beim Start. Nicht am Gerät geprüft: die Drehung (das Testgerät wird vom Nutzer physisch gedreht).

### Fehlerhistorie: Randfälle (seit v1.24.9)

- **Eine Live-Liste gehört zu EINER Verbindung:** `DtcViewModel` setzt `liveCodes` zurück, sobald `isConnected` falsch wird (vorher galt nach Trennen + Wiederverbinden ohne offene Seite die alte Liste wieder als „Aktiv“).
- **Überlappende Auslesevorgänge:** ein Generationszähler (wie `FreezeFrameViewModel`) — nur das jüngste Lesen schreibt Liste, Zustand und Fehler; ein spätes altes Ergebnis überschreibt nichts. Ein überholtes Lesen wird **trotzdem aufgezeichnet** (es war eine echte Beobachtung).
- **Bewusst so gelassen:** (1) Geht die Geräteuhr zurück, bewegt sich `lastSeen` nicht mehr (Schutz `lastSeen < now`, der auch das Flag vor unsortierten Schreibzugriffen schützt; Alternativen würden diesen Schutz aufgeben). (2) Obergrenze 200 Codes: ein Adapter mit Phantomcodes verdrängt die ältesten.
  (3) Ein Code, der im Undo-Fenster erneut gelesen wird, wird beim Commit mitgelöscht (s. „Lokale Fehlerhistorie“).

### Open-Source-Lizenzen in der App (seit v1.24.9)

`ui/licenses/`: Seite „Open-Source-Lizenzen“ (Route `Screen.Licenses`, Eltern-Tab Einstellungen, Einstieg über eine Zeile der App-Info). `LICENSED_COMPONENTS` ist von Hand gepflegt nach dem, was im Release-APK landet (R8-Mapping geprüft: AndroidX, Kotlin/Coroutines,
Okio, Dagger/Hilt, Sentry; dazu die zwei Schriften). **`LicenseCatalogTest` zwingt jede neue direkte Abhängigkeit zu einem Eintrag** (Gruppen aus `app/build.gradle.kts` + `libs.versions.toml`). Die Lizenztexte liegen in `docs/licenses/` (Apache 2.0 von apache.org,
MIT von Sentry **mit beiden Copyright-Zeilen des Originals** — Sentry und ANRWatchDog —, beide OFL) und sind per `sourceSets["main"].assets.directories.add(rootProject.file("docs/licenses").path)` (seit v1.27.1; vorher `assets.srcDir(…)`, das AGP 9.4 als veraltet meldet — `LicenseCatalogTest` hält den Wortlaut fest) zugleich die Assets der App — eine Quelle für Repo und APK; `LicensesScreenTest` liest sie aus dem echten APK.
Eine neue Lizenzart: Datei in `docs/licenses/`, `LicenseKind`, Komponente.

### Build-Integrität: Prüfsummen (seit v1.24.9)

- **Gradle-Distribution:** `distributionSha256Sum` in `gradle/wrapper/gradle-wrapper.properties` (Gradle 9.6.1-bin: `9c0f7fae…c9e14`, von downloads.gradle.org und gradle.org/release-checksums bestätigt, beim Laden vom Wrapper geprüft — das Archiv bleibt danach nicht liegen). Wird die Version angehoben, Summe und
  `BuildIntegrityTest` gemeinsam ändern.
- **Abhängigkeiten und Plugins:** `gradle/verification-metadata.xml` (SHA-256, 998 Komponenten). Gradle bricht ab, wenn ein Artefakt nicht passt oder **fehlt**. **Neue oder aktualisierte Abhängigkeit → Metadaten neu schreiben, und zwar MIT `--refresh-dependencies`:**
  `./gradlew --refresh-dependencies --write-verification-metadata sha256 --continue :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebugAndroidTest :app:lintAnalyzeDebugAndroidTest help`.
  Ohne `--refresh-dependencies` fehlen Einträge, die nur beim frischen Auflösen gebraucht werden (gemessen: `guava-parent 33.4.0-jre`); das zeigt erst ein späterer frischer Build. Geprüft mit einem Build + `--refresh-dependencies` (alle Downloads frisch gegen die Hashes). `BuildIntegrityTest` hält
  Summe, Verifikation an und dass jede direkte Abhängigkeit verifizierte Artefakte hat. **Das JDK lädt Gradle nie von selbst** (seit v1.25.0: `org.gradle.java.installations.auto-download=false` in `gradle.properties`): für Toolchain-Downloads (Daemon-JVM-Kriterien, foojay-Resolver) gibt es keine Prüfsumme, ein unbemerkt nachgeladenes JDK wäre ein ungeprüftes Programm im Build. Fehlt das JDK 21 (JetBrains), bricht der Build mit Gradles Meldung ab; die JBR von Android Studio und `~/.gradle/jdks` werden gefunden. Bewusst nachladen: `./gradlew -Porg.gradle.java.installations.auto-download=true help`. **Nicht am Gerät der Entwicklung nachgestellt, dass ein fehlendes JDK wirklich abbricht** (ein Gegenversuch hätte einen Download ausgelöst); belegt ist, dass der Build mit der Einstellung läuft, und die Einstellung steht in Gradles Dokumentation.

- **Bibliotheks-Updates (v1.25.2, Freigabe des Nutzers 2026-10-12: nur Bibliotheken, ohne AGP/Kotlin/Gradle/BOM):** navigation-compose 2.8.5 → 2.9.8, lifecycle 2.8.7 → 2.10.0, datastore 1.1.1 → 1.2.1, appcompat 1.7.0 → 1.8.0, paging 3.3.4 → 3.5.2, core-ktx 1.15.0 → 1.18.0,
  activity-compose 1.9.3 → 1.13.0, hilt-navigation-compose 1.2.0 → 1.3.0 (`hiltViewModel` liegt jetzt in `androidx.hilt.lifecycle.viewmodel.compose`; der alte Import ist deprecated). **Nicht möglich unter compileSdk 36** (der Build meldet es in `checkDebugAarMetadata`):
  navigation-compose 2.10.x, lifecycle 2.11.0, core-ktx 1.19.x, hilt-navigation-compose 1.4.0 — sie warten mit dem BOM auf das Toolchain-Release (compileSdk 37, AGP 9.4, Gradle 9.6). Die übrigen Bibliotheken folgten in v1.25.3 (s. u.). **Falle:** `lintAnalyzeDebugAndroidTest` und `lintAnalyzeDebugUnitTest` scheitern gelegentlich im großen Sammellauf der Prüfsummen-Neuberechnung mit einem internen Lint-Fehler („Error while resolving … FirRegularClassImpl“) —
  eine Verdichtung paralleler Tasks, kein Fehler der Bibliotheken: allein bzw. mit `--no-parallel` laufen sie durch. Die Prüfsummen-Neuberechnung braucht deshalb die Lint-Tasks nicht (separat laufen lassen).
- **Bibliotheks-Updates, zweite Runde (v1.25.3, Auftrag des Nutzers 2026-10-12: die Bibliotheken aus Abschnitt 3 des Backlogs):** Sentry 8.29.0 → 8.60.0, kotlinx-coroutines 1.9.0 → 1.11.0, kotlinx-serialization 1.8.1 → 1.11.0, MockK 1.13.12 → 1.14.11, androidx.test runner 1.6.2 → 1.7.0 /
  ext-junit 1.2.1 → 1.3.0 / rules 1.6.1 → 1.7.0 und **Kotlin 2.4.10 → 2.4.21** (Compose-Compiler-Plugin und `kotlin-test` folgen `kotlin` im Catalog). KSP (2.3.11), Hilt, Room, AGP und Gradle blieben. Gebaut und getestet wurde mit `--refresh-dependencies` (855 Komponenten); Lint: 0 Fehler, 10 Warnungen — genau die Toolchain-Punkte (BOM, compileSdk, die
  vier Bibliotheken, AGP, Ziel-API); die 16-KB-Meldung zu MockK aus dem Bericht vom 2026-10-04 taucht nicht mehr auf. **Kotlin hat zwei Stellen:** `kotlin` im Catalog (Compose-Compiler-Plugin, `kotlin-test`) und der `kotlin-gradle-plugin`-Pin im `buildscript`-Block des Root-`build.gradle.kts` — der `buildscript`-Block kann den Catalog nicht lesen.
  Wer nur eine anhebt, merkt nichts (Gradle nimmt die höhere), aber niemand weiß mehr, welche Version übersetzt; `BuildIntegrityTest` hält beide gleich. Der Pin stand beim ersten Versuch noch auf 2.4.10 und wurde erst durch diesen Test aufgedeckt. **Eine Aussage aus dem Backlog war falsch:** „Kotlin geht nicht losgelöst“ stimmt nicht —
  AGP 9.3 verträgt Kotlin 2.4.21 mit KSP 2.3.11 und Hilt 2.60.1 ohne weitere Änderung. Wie bei allen Bibliotheken: nur auf dem Pixel 8 Pro (API 37) geprüft. Ohne eingebauten DSN ist Sentry in den Releases weiter nicht aktiv; die neue Version steckt trotzdem im APK.
- **Toolchain-Release (v1.26.0, Auftrag des Nutzers 2026-10-12, Downloads freigegeben):** Gradle 9.5.0 → **9.6.1**, AGP 9.3.0 → **9.4.1**, compileSdk 36 → **37** (das Paket `platforms;android-37.0` lud AGP selbst nach; `targetSdk` bleibt **36** — der Wechsel bringt Verhaltensänderungen und ist eine eigene Entscheidung, die Lint-Warnung `OldTargetApi` bleibt),
  Compose-BOM 2026.05.00 → **2026.09.00** (Compose 1.12.1; **Material3 bleibt bei 1.4.0**, die BOM hebt es nicht), navigation-compose 2.9.8 → 2.10.2, lifecycle 2.10.0 → 2.11.0, core-ktx 1.18.0 → 1.19.1, hilt-navigation-compose 1.3.0 → 1.4.0. **Gradle 9.6.1 und nicht neuer:** AGP 9.4 verlangt mindestens 9.6.0, Kotlin 2.4.21 ist offiziell nur bis Gradle 9.7.0 freigegeben
  (kotlinlang.org, Abfrage 2026-10-12; Lint meldet 9.8.1 als neuer — gewollt ignoriert). **AGP 9.4 liegt über dem, was Kotlin 2.4.21 listet** (AGP 8.5.2–9.3.1): es lief ohne Warnung im Build und ohne Fehler in allen Tests, die Freigabe kommt voraussichtlich mit dem nächsten Kotlin. Gradle-Prüfsumme: zwei Quellen stimmen überein
  (downloads.gradle.org/.sha256, gradle.org/release-checksums) und der Wrapper hat die Distribution damit geprüft; `services.gradle.org/.sha256` leitet auf `downloads.gradle.org` um. **Neu mit AGP 9.4:** das Protokoll der Geräte-Tests schreibt einen Annahme-Test (`StatusBarIconsTest.beforeAndroid15…`, läuft nur vor API 35) als `<failure>` mit `AssumptionViolatedException`
  statt als `<skipped>` (Gradle zählt ihn weiter als bestanden, Exit-Code 0) — wer das XML auswertet, rechnet ihn heraus (**seit 2026-10-12 gelöst:** die API-Stufe schränkt `@SdkSuppress` ein, nicht `assumeTrue` — der Filter nimmt den Test vor dem Lauf heraus, er steht nicht mehr im Protokoll; `CodeHygieneSourceTest` verbietet `assumeTrue(… SDK_INT …)` in `androidTest`; auf dem Pixel laufen seitdem 331 statt 332 Testfälle); die laufende Ausgabe zeigt keine Test-für-Test-Zeilen mehr. Vorschau-Bilder (59) und der Rendertest liefen unverändert (ein Bild, `PreviewSlotComponentsLight`, ist Byte für Byte gleich dem vom Release 1.25.2). 0 Lint-Fehler, 2 Warnungen (Ziel-API, neuere Gradle-Version).
  **Nur auf dem Pixel 8 Pro (API 37) geprüft**, nicht auf älteren Android-Versionen und nicht am Fahrzeug.
- **targetSdk 37 (v1.27.0, Auftrag des Nutzers 2026-10-12):** `targetSdk` 36 → **37** (Android 17; `compileSdk` war seit 1.26.0 schon 37). `ReleaseDocsConsistencyTest` hält README und CLAUDE.md gleich `app/build.gradle.kts` (`targetSdk`/`compileSdk`). Grundlage: die Änderungen für Apps mit Ziel 37 (developer.android.com/about/versions/17/behavior-changes-17 und
  …/release-notes, Abfrage 2026-10-12) gegen den Quelltext der App geprüft. **Betrifft die App:** Bluetooth-RFCOMM-`read()` liefert -1 statt `IOException` — Lücke in `ElmController.readResponse` geschlossen (s. „Zeitlimit-Richtlinie und Altbytes“). **Betrifft sie nicht** (Quelltext-Suche): Hintergrund-Activity-Starts (nur die vom Nutzer angetippte `PendingIntent` der Benachrichtigung),
  `ACCESS_LOCAL_NETWORK` (kein LAN-Verkehr), Hintergrund-Audio, `RemoteViews`, `System.load`, Reflexion auf `MessageQueue`/`static final`, Content Capture, Keystore-Grenzen, Kontakte/SMS, Klartext-Verkehr (nur HTTPS, kein `usesCleartextTraffic`), `screenOrientation`/`resizeableActivity`/Seitenverhältnis (nicht im Manifest). **Zu „Optimized configuration changes“:**
  Tastatur, Navigation, Touchscreen, Farbmodus und der Wechsel des UI-Modus vom/zum Schreibtisch-Modus starten die Activity nicht mehr neu (Hell/Dunkel gehört nicht dazu; ob die Änderung am Ziel hängt, sagt die Seite nicht) — die App liest die Konfiguration über Compose, ein `AndroidView` mit Zustand gibt es nicht. **Certificate Transparency ist ab Ziel 37 standardmäßig an:** der Wetter-Abruf
  (`api.open-meteo.com`) ist betroffen, das Zertifikat (Let's Encrypt YR1) trägt eingebettete SCTs mehrerer Logs (Abfrage 2026-10-12 vom Entwicklungsrechner). **Auf dem Gerät geprüft (2026-10-12, Pixel 8 Pro, WLAN mit Internet):** Das frühere Testnetz hatte keinen ausgehenden Internetzugang (TCP-Timeout schon beim Verbinden, auch zu 8.8.8.8). Mit einem Netz, das das System als validiert führt, rief ein Wegwerf-Test (nicht im Repo) im Prozess der Debug-App (`targetSdkVersion` 37, im Test per Assertion bestätigt) erst eine Rohanfrage und dann den echten `OpenMeteoApi` auf: **HTTP 200 und 9,9 °C** für Beispielkoordinaten in Hamburg — das Zertifikat wird unter Ziel 37 akzeptiert, die Außentemperatur lädt. **Nicht bewiesen:** dass Certificate Transparency auf dem Gerät tatsächlich erzwungen wird (ein Erfolg zeigt nur, dass sie die Verbindung nicht verhindert); und der **Release-Build** (R8) wurde nicht getrennt geprüft, nur der Debug-Build mit demselben Client-Code. **Falle für künftige Netztests:** `OpenMeteoApi.currentTemperatureC` fängt jede Ausnahme und liefert `null` — ein Test braucht eine Rohanfrage davor, sonst fehlt der Grund eines Fehlschlags.
  Die Android-17-Änderungen „für alle Apps“ (Speichergrenzen, autonomes Re-Pairing verlorener Bluetooth-Kopplungen u. a.) gelten auf dem Pixel (API 37) unabhängig vom Ziel. Geprüft: 1475 JVM, 332 Instrumentation (der Annahme-Test `StatusBarIconsTest` läuft nur vor API 35), Lint 0 Fehler / 1 Warnung (Gradle 9.8.1, gewollt; `OldTargetApi` ist weg).

### Test-Infrastruktur: Produktion statt Nachbau (seit v1.24.5)

- **Datenbank:** Version, Migrationen und Builder stehen einmal — `APP_DATABASE_VERSION`, `ALL_MIGRATIONS`, `appDatabaseBuilder(context, name)` (`data/db/AppDatabase.kt`).
  `AppModule` und die Instrumentation-Tests nutzen sie, nie eine handgeschriebene Liste. Eine neue Datenbankversion braucht ihre Migration **in `ALL_MIGRATIONS`**
  (`AppDatabaseMigrationsTest` prüft die lückenlose Kette auf dem JVM). Foreign Keys schaltet Rooms generierter `onOpen` ein — **kein** eigener PRAGMA-Callback
  (`AppDatabaseBootstrapTest` weist es am Gerät nach); Tests bauen keinen Callback nach.
- **Instrumentation-DataStore:** `TestAppModule` gibt jedem Test einen **eigenen** DataStore (Temp-Datei, produktiver Korruptions-Handler). Tests starten bei den
  Standardwerten und hinterlassen nichts; die Einstellungen der Debug-App auf dem Gerät bleiben unberührt. Nie wieder `ctx.dataStore` in einem Test-Modul.
- **Attrappen stehen einmal (TST-17, seit v1.25.2):** JVM: `fakeAppPreferences(…)` (`app/src/test/java/de/adv990de/obd2/FakeAppPreferences.kt`) — jeder Lese-Flow mit den Standardwerten der echten Klasse, ein Test nennt nur, was für ihn zählt (Parameter heißen wie die Properties);
  `FakeAppPreferencesTest` vergleicht jeden Flow der echten `AppPreferences` (per Reflexion) mit der Attrappe, ein neuer Flow macht ihn rot, bis die Attrappe ihn kennt. `fakeObdRepository { … }` (`FakeObdRepository.kt`) — streng, mit den Stubs jeder Sitzung
  (nicht verbunden, keine PIDs, keine FIN, keine Codes); `configure` überschreibt (ein späteres `every` gewinnt), darin heißt der Bezug `this@fakeObdRepository`. Gerät: `idleObdRepository { … }` (`androidTest/…/IdleObdRepository.kt`, relaxed) ersetzt den Inhalt des
  `@BindValue`-Blocks. **`@BindValue` selbst bleibt in jeder Klasse** — Hilt erlaubt ein Überschreiben nur dort, kein Basistyp und kein Modul kann es übernehmen. Neue Tests nehmen diese Fabriken, keine eigene `makePrefs`/`mockk<ObdRepository> { … }`-Kopie.
  **Test-Falle bei automatischen Umbauten:** ein „unbenutzte Imports entfernen“-Skript darf `androidx.compose.runtime.getValue`/`setValue` nicht löschen (`by`-Delegate, kein Name im Text) — erst der Compiler zeigt es.
- **Benachrichtigungskanal:** `createObdNotificationChannel(context)` (`ObdForegroundService.kt`) — die Application und der Service-Test benutzen dieselbe Funktion.
- **Projektverzeichnis in Tests:** `projectRoot` (`app/src/test/java/de/adv990de/obd2/ProjectRoot.kt`, `internal`, lazy) — eine Stelle für alle Tests, die Quelltext, Doku oder Skripte lesen (`import de.adv990de.obd2.projectRoot`). Zehn Kopien der Suche nach `settings.gradle.kts`
  trugen je eine Compilerwarnung (`System.getProperty` ist nullable); die JVM-Tests laufen jetzt ohne Kompilerwarnung. **Test-Falle:** `docs/*` in einem KDoc beginnt einen verschachtelten Kommentar (`/*`) — „Unclosed comment“; im Kommentar umschreiben.
- **Simulator-Uhr:** `SimulatedEcuDispatcher(clockMs)` nimmt die Uhr als Parameter (Standard: Wanduhr). Tests prüfen die Kurven an festen Zeitpunkten (`FakeClock` in `SimulatedEcuDispatcherTest`, Beginn bei einem Vielfachen der 20-s-Periode) — nie wieder mit `Thread.sleep` auf „irgendeine“ Änderung warten.
  **Test-Falle (Mutationsproben):** Eine von Hand zurückgesetzte (`mv`/`cp`) Quelldatei sieht der inkrementelle Kotlin-Compiler nicht als geändert — die mutierte Klasse bleibt kompiliert, bis die Datei angefasst wird (`touch`). Nach jeder Mutation die Datei anfassen, und vor dem Schlusslauf einmal sauber bauen.
- **Wetter-Uhr:** `timeSourceFrom(readNanos)` (`WeatherViewModel.kt`) baut die `TimeSource` auf einer vorgegebenen Uhr; `TimeSourceFromTest` hält fest, dass nur diese Uhr gelesen wird (eine eingefrorene Uhr lässt keine Zeit vergehen, auch wenn die JVM-Zeit läuft) und dass ein Sprung von
  Stunden ankommt. Die echte Verdrahtung `elapsedRealtimeSource` (`SystemClock.elapsedRealtimeNanos()`, zählt Tiefschlaf mit) prüft `ElapsedRealtimeSourceDeviceTest` auf dem Gerät. Ein echter Tiefschlaf lässt sich im Test nicht herbeiführen.
- **Locale:** Die JVM-Tests laufen unter `de_DE` (`app/build.gradle.kts`). Eine Anzeigeformatierung wird mit `withDefaultLocale(Locale.US)`/`(Locale.GERMANY)` getestet,
  beide Schreibweisen, exakt statt `contains`.
- **`ElmController`:** Das Lese-Zeitlimit ist ein Konstruktor-Parameter (`readTimeoutMs`, Standard 5 s) — Timeout-Tests nehmen 100 ms (echte Zeit; `withTimeout` in
  `Dispatchers.IO` kennt die virtuelle Uhr nicht). Ein IO-Fehler **nach** dem Abbruch ist der eigene Interrupt und wird zu Timeout bzw. Abbruch, nie zu `ConnectionLost`
  (Kotlin stuft eine echte Ausnahme über die Abbruchursache — sonst überstimmt `InterruptedIOException` das Zeitlimit). Die Discovery sucht `41 <Bereich>` irgendwo in der
  Antwort (`rangeDataBytes`, `data/PidDiscovery.kt`), weil der ELM327 „SEARCHING...“ / „BUS INIT: OK“ davorstellt.

### Fehlercode-Texte (seit v1.25.1, Entscheidung 2026-10-12)

- **Die Beschreibung eines Fehlercodes ist eine String-Ressource**, kein Text im Code: `dtcDescriptionRes(code)` (`obd/DtcDescriptions.kt`, rein) liefert `R.string.dtc_pXXXX` für die 37 bekannten SAE-J2012-Codes, sonst `R.string.dtc_unknown`. `DtcEntry` und `DtcHistoryEntry` tragen **nur den Code**; die Oberfläche löst
  den Text bei der Anzeige auf (`stringResource(dtcDescriptionRes(code))`). Das App-Log nennt die Codes, nicht die Texte.
- **Die deutschen Texte sind eine eigene Übersetzung** der SAE-Namen, kein amtlicher Wortlaut; der Code steht immer daneben. **Vom Nutzer gegengelesen am 2026-10-12, keine Korrekturen** (`docs/qa/2026-10-12-dtc-texte-gegenlesen.md`); die englischen Namen sind dabei nicht gegen den Normtext SAE J2012 geprüft. Ein neuer Code: Eintrag in der `when`-Liste **und** je ein String in `values/` und `values-de/` — `DtcDescriptionTest` hält Liste, Ressourcen und beide Sprachen zusammen (gleiche Namen, deutscher Text nicht leer und nicht der englische).
- **Bewusst nicht gebaut:** mehr als diese Auswahl an Codes (es gibt tausende; alles andere zeigt „Unbekannter Fehlercode“ plus den Code).

### Lokale Fehlerhistorie (seit 2026-10-04, v1.23.0)

Die App merkt sich, welche Fehlercodes (DTCs) das Fahrzeug gemeldet hat — auch nachdem es sie nicht mehr meldet und offline.
**Rein lokal; das Fahrzeug bleibt rein lesend:** Quelle sind die bestehenden Modus-03/07-Lesezugriffe, „Historie löschen“ löscht nur die
lokale Aufzeichnung, nie den Fehlerspeicher des Fahrzeugs (kein Modus 04).
- **Datenmodell:** Room-Tabelle `dtc_history` (eingeführt mit Datenbank **v3**; seit v1.25.0 ist die Datenbank **v4**, s. „Herkunft eines Messwerts“), eine Zeile je Code: `code` (PK), `firstSeen` (nie überschrieben), `lastSeen`
  (nur vorwärts), `lastWasPending`. Die Beschreibung wird zur Anzeige per `dtcDescriptionRes(code)` (öffentlich, rein, eine String-Ressource)
  aufgelöst, nicht gespeichert — `DtcEntry` und `DtcHistoryEntry` tragen nur den Code. `MIGRATION_2_3` ist von Hand geschrieben, `app/schemas/…/3.json` liegt im Repo, `AppDatabaseMigrationTest` prüft
  2→3 und 1→3 gegen das exportierte Schema und dass Altdaten (Fahrten, Messwerte, Logs) erhalten bleiben. Es ist **kein destruktiver Fallback**
  konfiguriert: ein falsches SQL würde jeden Update-Nutzer beim ersten Öffnen crashen. Ein Rückweg auf ältere APKs ist voraussichtlich nur mit
  gelöschten App-Daten möglich (kein Downgrade-Pfad, nicht ausprobiert).
- **Kein UPSERT:** SQLite auf API 26 ist 3.18, `ON CONFLICT DO UPDATE` gibt es erst ab 3.24 → `DtcHistoryDao.recordSeen` = `INSERT OR IGNORE` +
  `UPDATE … WHERE lastSeen < :now` in einer `@Transaction` (deshalb eine abstrakte DAO-Klasse). Ein Code, der in einem Auslesen als bestätigt UND als
  ausstehend vorkommt, zählt als bestätigt.
- **Aufzeichnung ist „best effort“:** `DtcHistoryRepository.record()` fängt Datenbankfehler (App-Log-WARN) und wirft sie nie (nur
  `CancellationException`); Obergrenze 200 Codes (das älteste `lastSeen` fliegt raus — schützt vor Phantomcodes eines defekten Adapters). Zwei
  Stellen, beide nur nach erfolgreichem Lesen: `ReadingsPipeline.runSession` (passiv bei jedem Verbinden, zusätzlich per try/catch abgesichert, weil
  eine Ausnahme dort die ganze Sitzung kosten würde) und `DtcViewModel.refresh()` (Seite öffnen, Pull-to-Refresh). Bewusst **nicht** in
  `ObdRepository.readDtcs()`: Persistenz gehört nicht in die Protokollschicht.
- **DTC-Seite:** Umschalter „Aktuell | Historie“ (Segmente ≥ 56 dp). „Aktuell“ = Live-Liste; **ohne Verbindung wird nichts gelesen** und die Seite sagt
  „Nicht verbunden“ — `readDtcs()` liefert dann `emptyList()`, was als „Keine Fehlercodes vorhanden“ ein falscher Entwarnungs-Hinweis wäre. „Historie“ =
  Code · Beschreibung · „Erstmals … · Zuletzt …“; Badge „Aktiv“ nur, wenn der Code im letzten erfolgreichen Auslesen stand UND die Verbindung steht
  (nach Abbruch oder fehlgeschlagenem Lesen nie), „Ausstehend“, wenn er zuletzt nur als pending gesehen wurde. Löschen nach dem Lösch-Vertrag
  (`UndoableDeletion<String>`, Schlüssel = Code): Papierkorb oder Swipe je Zeile ohne Dialog; „Historie löschen“ (Top-Bar, nur in der Historie-Ansicht)
  mit Dialog. Ein Code, der im Undo-Fenster erneut gelesen wird, wird beim Commit mitgelöscht und kommt beim nächsten Auslesen zurück — gewollt.
- **Hilt/Tests:** `DtcHistoryDao` hat einen Provider in `AppModule` **und** in `TestAppModule` (dieses ersetzt jenes in den androidTests; fehlt er dort,
  bricht der Hilt-Graph aller UI-Tests).
- **Nicht in v1:** Zähler/Episoden, Fahrzeugtrennung (VIN), Export, Verknüpfung mit Fahrten.

### Animation-Disziplin

- **Werte-Updates:** `AnimatedContent` mit `slideInVertically + fadeIn` über 180 ms, `EaseOutCubic`. **Nie Spring/Bouncy** — das ist Toy-Aesthetic.
- **Connection-Status:** Pulsierender Dot via `infiniteRepeatable` `tween(600)` für „connecting"; statisch für „connected"/„disconnected".
- **Screen-Transitions (entschieden 2026-10-12, UID-10):** `AppNavHost` setzt keine eigenen Übergänge — es gilt der Standard von `NavHost` (Überblendung, 700 ms — in `DefaultNavTransitions` von navigation-compose 2.10.2 am Bytecode nachgelesen; am Gerät nicht gemessen).
  Das ist die **Vorgabe**, kein offener Mangel: die frühere Forderung „`slideIntoContainer` 220 ms“ entfällt. Wer eigene Übergänge ergänzen will, braucht eine neue Entscheidung des Nutzers
  (Kandidat war der 220-ms-Slide; **nicht** `fadeIn` allein — zu unbestimmt) und trägt sie hier ein.
- **Verboten:** parallax scroll, decorative particle effects, anything that draws attention away from data.

### Iconography

- **Material Symbols** (outlined Variante als Default, filled für aktive States).
- Konsistente Größen: 20 dp inline, 24 dp Toolbar, 32 dp in Tiles, 48 dp in Empty-States.
- **Keine** Drittanbieter-Icon-Packs vermischen.

### App-Icon / Launcher (Logo-Redesign 2026-09-29, User-Wunsch)

Ersetzt das vorherige generische Material-Symbols-Tacho-Icon auf Teal. Adaptive Icon
(`res/drawable/ic_launcher_foreground.xml` + `ic_launcher_background.xml`,
`res/mipmap-anydpi/ic_launcher.xml` unveraendert):

- **Hintergrund Release:** Navy `#0B1B2E` (vorher Teal `#006874`).
- **Hintergrund Debug:** Amber `#B45309` (unveraendert, s. `app/src/debug/res/drawable/`) — bewusst
  nicht an die neue Palette angepasst, war schon vorher klar vom Release-Hintergrund
  unterscheidbar, kein Grund fuer eine neue Farbe nur wegen des Redesigns.
- **Vordergrund (geteilt, auch fuer `monochrome`):** Drehzahl-Bogen — 270°-Ring mit 90°-Luecke
  unten, Nadel, Naben-Punkt. Greift bewusst die Segment-Bar-Optik des echten RPM-Balkens im
  Dashboard auf (s. Abschnitt „RPM-Balken" oben), statt ein generisches Tacho-Symbol zu sein.
  Bogen in zwei Pfade geteilt (`#38BDF8` Blau 135°–315°, `#F97316` Orange 315°–45°, beide im
  Uhrzeigersinn) — **kein Farbverlauf**: entspricht der echten Schwellwert-Logik des RPM-Balkens
  (harter Farbwechsel am Schaltblitz-Schwellwert) und ist in einem Vector-Drawable zuverlaesiger
  als ein Gradient-Fill. Nadel + Naben-Punkt in Weiß. Alle Formen innerhalb `r=29,5dp` um die
  Mitte (54,54) bei 108×108dp-Viewport — klar innerhalb des 33dp-Sicherheitsradius fuer adaptive
  Icons (66dp-Sicherheitszone), damit nichts von einer OEM-Kreis-/Squircle-Maske abgeschnitten
  wird.
- Themed Icons (Android 13+): `monochrome` referenziert weiterhin denselben Vordergrund wie
  `foreground` (unveraendert seit vorher) — das OS ersetzt Fuellfarben beim Rendern ohnehin durch
  einen einzigen Systemton, Blau/Orange-Unterscheidung ist dort naturgemaess nicht sichtbar.

### Komponent-Vokabular (verbindliche Namen)

Wenn du neue Composables schreibst, halte dich an dieses Vokabular:

| Composable | Verwendung |
|---|---|
| `AppTheme(colorScheme, typography)` / `ProvideMinTouchTarget { }` | Theme der App inkl. 56-dp-Regel für Touch-Ziele (`ui/theme/AppTheme.kt`); `ProvideMinTouchTarget` setzt sie INNERHALB von Dialog-/Sheet-Slots erneut (eigenes Fenster, s. „Touch-Ziele und Barrierefreiheit“) |
| `LiveValueText(value, style, color, animated)` | Inline-Wert in Plex Mono + `tnum`; `animated=true` für langsam wechselnde Werte (Snapshots/VIN) |
| `LiveValueTile(label, value, unit, status, sparkline)` | Zentrales Tile-Pattern (s.o.) — Side-Bar-Akzent via `TileStatus` |
| `StatusDot(state, size, color?)` | 8-dp Kreis, theme-`tertiary` ok / `StatusColors.Warning` / `StatusColors.Critical` / gepulst auf primary für „connecting"; optionaler `color`-Override für Ausnahmefälle (s. Dashboard-Verbindungs-Dot unten) |
| `SectionLabel(text, textAlign?)` | ALL-CAPS labelMedium für Section-Header (0.12.em letter-spacing) |
| `ChoiceSetting(headline, supportingText, options, selected, onSelect, optionDescriptionPlural, additionalSupportingText?)` | Einstellung aus wenigen festen Ganzzahlen (`ui/settings/SettingsComponents.kt`): `ListItem` + `SingleChoiceSegmentedButtonRow`, Segmente ≥ 56 dp hoch, nur die Zahl im Segment — die Einheit steht im Hilfetext, Vorlesetext je Segment über Plurals ("20 Sekunden"). Ersetzt freie Zahleneingabe; Altwerte außerhalb der Liste setzt `AppPreferences` beim Lesen per `snapToOption` auf die nächste Option (Gleichstand → größere), ohne Migration. Genutzt für Watchdog-Timeout (10/20/30/60/120 s) und Reconnect-Basisverzögerung (3/10/30 s) |
| `SettingsGroupHeader(text)` | Gruppenüberschrift der Einstellungsseite: `SectionLabel` in `primary` mit Luft oben/unten und `heading()`-Semantik |
| `SettingsLinkRow(title, summary, onClick)` | Zeile zu einer Unterseite: Titel + Einzeiler-Zusammenfassung + Chevron, ≥ 64 dp, ganze Zeile klickbar (`ui/settings/SettingsComponents.kt`) |
| `SettingsSwitchRow(title, summary, checked, onCheckedChange)` | Ein/Aus-Einstellung als Ganz-Zeilen-Toggle (`Modifier.toggleable`, ≥ 64 dp), `Switch` nur als Zustandsanzeige |
| `ConfirmDeleteDialog(title, message, confirmLabel, dismissLabel, onConfirm, onDismiss)` | Bestätigung vor „Alles löschen“ (`ui/components/`): M3-`AlertDialog` (bewusst keine Bottom Sheet), Löschen-Button in `colorScheme.error`, beide Buttons ≥ 56 dp |
| `UndoSnackbarEffect(batchId, message, undoLabel, snackbarHostState, onUndo)` | „… gelöscht · Rückgängig“-Snackbar als reine ANSICHT von `UndoableDeletion.openBatchId`: ohne eigenen Timeout (`Indefinite`), verschwindet, wenn die Id null wird; braucht einen `SnackbarHost` im `Scaffold` |
| `SwipeToDeleteRow(onDelete, modifier, enabled, content)` | Zeile mit Swipe-zum-Löschen (`ui/components/`, Material3 `SwipeToDismissBox`): nur Richtung Zeilenende, ab 50 % der Breite, `enabled = false` für nicht löschbare Zeilen; **nur zusätzlich** zum Papierkorb-Button. `content` braucht einen deckenden Hintergrund (ein `ListItem` hat ihn) |
| `EmptyState(icon, text, modifier?)` | Leerer Zustand einer Liste: 48-dp-Icon + eine Textzeile, beides in `onSurfaceVariant`; füllt den gegebenen Platz |
| `Sparkline(points, color)` | Trend-Linie (immer primary, unabhängig von TileStatus): 4 dp, runde Enden, darunter ein auslaufender Verlauf (32 % → transparent), Punkt auf dem jüngsten Wert, Band 48 dp — bei Tageslicht auf dem Motorrad war 2 dp ohne Fläche unlesbar. Bei nahezu flachem Werteverlauf spreizt `effectiveSparklineRange` (Stand 2026-09-29) die Skala symmetrisch um mind. 5 % der Werte-Magnitude (Fallback ±0,05 bei exakt 0), damit die Linie nicht als einzelner Strich am Kachel-Rand klebt — reine Funktion, unabhängig von `sparklinePointOffset` |

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
- **Coroutines:** `viewModelScope` für ViewModel-internes, `@ApplicationScope CoroutineScope` (`di/ApplicationScope.kt`, bereitgestellt in `AppModule` UND in `TestAppModule`, das `AppModule` in den Hilt-androidTests ersetzt) für Lifecycle-überspannendes, z. B. ein Löschen, das noch im Rückgängig-Fenster ist, wenn der Nutzer die Seite verlässt. **Korrektur (2026-10-04):** Diese Zeile behauptete früher, ein `applicationScope` sei bereits in `AppModule` definiert — er existierte nicht (nur `ObdForegroundService.scope`); er wurde mit dem Lösch-Vertrag (v1.20.0) tatsächlich angelegt.
- **Hilt:** `@Singleton` nur wo unverzichtbar. Repositories ja, ViewModels nie.
- **Logging:** `AppLogRepository` für persistente App-Logs (Settings-Screen sichtbar). `Log.d/w/e` nur in Tests oder für Debug-Build-Output.
- **Compose-UI-Tests (`androidTest`):** `createAndroidComposeRule` aus `androidx.compose.ui.test.junit4.v2` importieren — die v1-Variante ist deprecated (die v2-Rule nutzt `StandardTestDispatcher` statt `UnconfinedTestDispatcher`; alle bestehenden Tests laufen damit unverändert).

## Release

Der Ablauf steht in `scripts/release.sh` (Beschreibung in der README, Abschnitt „Release-Ablauf“; Signatur-Prüfungen s. „Release-Signatur und Geheimnisse“ oben). Die eine Regel dahinter: **erst die Version bumpen und
committen, dann bauen** — sonst trägt das APK eine Revision, die nicht der des Tags entspricht (B32). README („Aktuelle Version“), oberster
`RELEASE_NOTES.md`-Eintrag und `app/build.gradle.kts` müssen dieselbe Version nennen (`ReleaseDocsConsistencyTest`). Die JVM-Tests laufen unter `de_DE`
(`app/build.gradle.kts`); wer eine Anzeigeformatierung testet, pinnt die Locale mit `withDefaultLocale` und prüft beide Schreibweisen.

## Build & Test (lokal)

```bash
./gradlew :app:testDebugUnitTest          # 1504 JVM-Unit-Tests
./gradlew :app:connectedDebugAndroidTest  # 327 Instrumentation-Tests (Gerät nötig)
./gradlew assembleDebug                    # APK
./gradlew assembleRelease                  # signiert via env-vars (s. README)
```

## Bei UI-Änderungen IMMER

1. Vorher: bestehende Composables im selben Bereich gelesen — Pattern reused, nicht dupliziert
2. Während: `Modifier`-Chain in der Standardreihenfolge: `fillMaxWidth/Height → padding → background → clip → border → clickable`
3. Nachher: `@Preview` für jeden neuen Composable in Light- UND Dark-Surface (auch wenn wir Light nicht ausliefern — hilft beim Kontrast-Check)
4. Falls Live-Werte beteiligt: explizit `fontFeatureSettings = "tnum"` setzen oder Mono-Font wählen
