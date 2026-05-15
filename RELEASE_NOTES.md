# Release Notes — OBD2 Monitor

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

### Bekannte Issues

- `connectedDebugAndroidTest` schlägt auf Android 16 (API 36) weiter fehl — strukturelles Hilt + ComposeTestRule + neue Espresso-Compose-Integration. Unit-Tests bleiben grün; Compose-Layer wird über `@Preview`s und Smoke-Tests auf Gerät verifiziert. Tieferer Test-Infra-Deep-Dive ausstehend.

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
