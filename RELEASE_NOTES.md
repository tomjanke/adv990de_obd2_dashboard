# Release Notes — OBD2 Monitor

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
