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
4. **Glove-Friendly Tap-Targets.** Minimum 56 dp × 56 dp für jeden Touch. Bei Settings-Listen mind. 64 dp Zeilenhöhe. Keine Swipe-Only-Gesten an kritischen Stellen (DMD2-Regel: „avoid swipes while wearing gloves"); Swipe-to-Delete gibt es nur **zusätzlich** zum sichtbaren Papierkorb-Button (s. „Lösch-Vertrag").
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
- `StatusColors.Warning` (`#EAB308`, Tailwind Yellow 500 — **Korrektur 2026-09-28**, User-Wunsch nach kräftigerem Gelb, vorher `#F59E0B`/Amber 500) — non-blocking Caution (Coolant zu heiß, Voltage niedrig, BLE schwach)
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
(`DashboardComponents.kt`, reine Funktion, JVM-getestet) ordnet ihm die Farbe zu:
- `Red` (≤75 °C) → `StatusColors.Critical` (Rot)
- `Yellow` (>75 °C, unter dem Grün-Schwellwert) → `StatusColors.Warning` (Gelb)
- `Green` (>80 °C **ununterbrochen für 10 Minuten**, mit Hysterese-Zone 78–80 °C) → `StatusColors.TrafficLightGreen` (`#22C55E`, dasselbe Grün wie der Bluetooth-Dot oben)

**Korrektur (2026-09-27, finales Review):** Grün war ursprünglich `MaterialTheme.colorScheme.tertiary`
— das ist aber nur im grünen Cockpit-Schema tatsächlich grün (`#86EFAC`); im blauen ist es
hellblau (`#7DD3FC`), im amber-farbenen amber (`#FCD34D`, kaum vom Gelb-Zustand — Stand 2026-09-28
`#EAB308`, davor `#F59E0B` — zu unterscheiden) und im Default-Schema mauve (`#7D5260`, hell) bzw. rosa (`#EFB8C8`, dunkel —
M3-1.4.0-Standardwerte). Deshalb jetzt das feste Ampel-Grün, auf Nutzerwunsch exakt die Farbe des
Bluetooth-Dots.

Die invertierte Semantik ist inhaltlich korrekt (ein kalter Motor braucht längere Aufwärm-Zeit,
nicht als Alarm sondern als Hilfe-Info) und vom User bestätigt. Alle drei Farben sind benannte
`StatusColors`-Tokens; keine Hardcoded-Werte im Composable. Hysterese-Details: `GREEN_ENTER_THRESHOLD_C = 80 °C`,
`GREEN_RESET_THRESHOLD_C = 78 °C`, `GREEN_HOLD_MS = 10 min` — die Zone 78–80 °C hält den
Timer unverändert (verhindert Thermostat-Pendeln).

**Dokumentierte Ausnahme — Neutral-Anzeige „N“ in der Ganganzeige (Stand 2026-10-04, Nutzerwunsch):**
Steht das Fahrzeug und läuft der Motor, zeigt die Gang-Box neben dem RPM-Balken (s. „RPM-Balken“) ein
**grünes „N“** statt des „–“. Das Grün ist in jedem Farbschema `StatusColors.TrafficLightGreen`
(`#22C55E`, dasselbe wie Bluetooth-Dot und Öl-Balken) — wie die grüne Leerlauf-Kontrollleuchte am
Motorrad; Theme-`tertiary` scheidet aus, weil es nur im grünen Cockpit-Schema grün ist. Die
Farbzuordnung steckt in `gearBoxColor()` (`ui/dashboard/DashboardComponents.kt`, reine Funktion,
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
**Bekannte Restlücke (Stand 2026-10-04, im Orange-Schema per Screenshot bestätigt):** `primaryContainer`/
`tertiaryContainer`/`errorContainer` samt `on…`-Rollen — die Log-Level-Chips in `AppLogsScreen` (INFO violett,
WARN mauve) — und `outlineVariant` (die 1-dp-Linien zwischen den Dashboard-Kacheln, `DashboardScreen.kt`) sind in
den Cockpit-Schemata noch M3-Standard. `error` bleibt bewusst M3-Standard (s. „Statusfarben“ oben). Offen als
Folgearbeit: Rollen aus der Palette ableiten oder die Chips auf `StatusColors` abbilden.

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
- `LazyVerticalGrid` mit `GridCells.Fixed`, `verticalArrangement = spacedBy(12.dp)`, `horizontalArrangement = spacedBy(12.dp)`

**Slot-Picker (Long-Press auf eine Kachel, seit 2026-10-04, Nutzerentscheidung):** `ModalBottomSheet` mit
`skipPartiallyExpanded = true` — öffnet immer in voller Höhe. Das halbhohe M3-Standardverhalten zeigte im
Querformat (~1008 px Fensterhöhe) anfangs nur ~3 Zeilen, im Hochformat ~8; der Preis der vollen Höhe ist, dass die
Kachel dahinter nicht mehr sichtbar bleibt. Nicht zurück auf den Default stellen, ohne diese Abwägung neu zu treffen.
Regressionstest: `DashboardScreenTest.dashboard_slotPicker_opensFullyExpanded_soLowerRowsAreVisibleInLandscape`.

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
- Zeitbasis von Timer und Lücken-Limit ist `SystemClock.elapsedRealtime()` (monoton, zählt Tiefschlaf
  mit), nicht die Wanduhr.
- Die Kühlmitteltemperatur wird dafür **immer** abgefragt, wie RPM — unabhängig von Slot-Belegung und
  dynamischer Schwelle (`ReadingsPipeline.selectPids()`).

### Ambient-Statuszeile (statt Hauptmenü während der Fahrt, seit 2026-09-27)

Ist `hideNavOnDrive` an und das Fahrzeug in Bewegung (`DriveStateViewModel.isNavHidden`), tauscht
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
Drehzahl-Blinkgrenze + dynamische Schwelle, Getriebe →) · **Aufzeichnung** (Nur Fahrten mit DTCs, Nur ECU-bestätigte
PIDs, Aufgezeichnete PIDs →) · **Fahrzeug** (Fahrzeuginfo →) · **Info & Wartung** (App-Logs →, App-Info →).
- **Unterseiten** sind `SettingsLinkRow`s (Titel, Einzeiler-Zusammenfassung, Chevron), nie nackte `TextButton`s.
- **Jede Zeile ≥ 64 dp, jeder Schalter ist eine Ganz-Zeilen-Fläche** (`SettingsSwitchRow`: `toggleable` an der Zeile, der
  `Switch` zeigt nur den Zustand; ein `testTag` am Modifier sitzt damit am Knoten mit dem Toggle-Zustand).
- **Segmente ≥ 56 dp** (`ChoiceSetting` und `ThemePickerItem`; der M3-Default wäre 40 dp).
- **„Dashboard konfigurieren“ gibt es nicht mehr als eigene Seite:** die Drehzahl-Blinkgrenze (Slider) und die dynamische
  Schwellenskalierung sind `RpmThresholdSection` in „Dashboard & Anzeige“, gespeist von `DisplaySettingsViewModel`.
- **Eltern-Tab:** `parentTab(route)` (`ui/navigation/ParentTab.kt`, rein) ordnet jeder Unterseite ihren Tab zu; `BottomNav`
  markiert ihn, die Leiste steht auf Unterseiten also nie ohne Auswahl. Ein neuer `Screen` zwingt `ParentTabTest` (exhaustives
  `when`) zu einer Entscheidung. Die Zurück-Beschreibung der Pfeile ist überall `R.string.nav_back_desc`.

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

### Lokale Fehlerhistorie (seit 2026-10-04, v1.23.0)

Die App merkt sich, welche Fehlercodes (DTCs) das Fahrzeug gemeldet hat — auch nachdem es sie nicht mehr meldet und offline.
**Rein lokal; das Fahrzeug bleibt rein lesend:** Quelle sind die bestehenden Modus-03/07-Lesezugriffe, „Historie löschen“ löscht nur die
lokale Aufzeichnung, nie den Fehlerspeicher des Fahrzeugs (kein Modus 04).
- **Datenmodell:** Room-Tabelle `dtc_history` (Datenbank **v3**), eine Zeile je Code: `code` (PK), `firstSeen` (nie überschrieben), `lastSeen`
  (nur vorwärts), `lastWasPending`. Die Beschreibung wird zur Anzeige per `dtcDescription(code)` (öffentlich, rein, aus `DtcParser` extrahiert)
  aufgelöst, nicht gespeichert. `MIGRATION_2_3` ist von Hand geschrieben, `app/schemas/…/3.json` liegt im Repo, `AppDatabaseMigrationTest` prüft
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
- **Screen-Transitions:** `slideIntoContainer` 220 ms, **nicht** `fadeIn` allein (zu unbestimmt).
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

## Build & Test (lokal)

```bash
./gradlew :app:testDebugUnitTest          # 936 JVM-Unit-Tests
./gradlew :app:connectedDebugAndroidTest  # 244 Instrumentation-Tests (Gerät nötig)
./gradlew assembleDebug                    # APK
./gradlew assembleRelease                  # signiert via env-vars (s. README)
```

## Bei UI-Änderungen IMMER

1. Vorher: bestehende Composables im selben Bereich gelesen — Pattern reused, nicht dupliziert
2. Während: `Modifier`-Chain in der Standardreihenfolge: `fillMaxWidth/Height → padding → background → clip → border → clickable`
3. Nachher: `@Preview` für jeden neuen Composable in Light- UND Dark-Surface (auch wenn wir Light nicht ausliefern — hilft beim Kontrast-Check)
4. Falls Live-Werte beteiligt: explizit `fontFeatureSettings = "tnum"` setzen oder Mono-Font wählen
