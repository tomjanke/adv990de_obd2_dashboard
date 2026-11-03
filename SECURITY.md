# Security Policy / Sicherheitshinweise

English first, deutsch darunter.

## English

### Reporting a vulnerability

Please **do not open a public issue** for a security problem. Use GitHub's private reporting instead:
**Security** tab of this repository → **Report a vulnerability**.

Please include the app version (Settings → App info), your Android version, what you did, what you expected and what happened, and what an attacker could do with it.
**Do not send personal data**: no VIN, no Bluetooth address of your adapter, no locations, no files of your drives or logbook.

This is a one-person project. I answer as soon as I can, but I cannot promise a deadline, and there is no bug bounty.

### Supported versions

Only the latest release (see "Current version" in the [README](docs/README.en.md)) and the current state of `main`. There are no backports.

### What counts, and what does not

In scope: the Android app, the build and release scripts in this repository, and how the app handles its own data and its signing key.

Out of scope: weaknesses of the OBD2 protocol or of ELM327-compatible adapters (they have no authentication), anyone who already has unlocked physical access to the phone, the firmware of vehicles or adapters, and bugs in third-party libraries that the app does not make exploitable (report those to the library).

### What the app does, so you can judge a report

- It **never writes to the vehicle**: only the OBD2 read services (modes 01, 02, 03, 07, 09), no clearing of fault codes (no mode 04), no coding.
- The only network connection is the optional outside temperature (rounded location to Open-Meteo, HTTPS only). There are no accounts and no advertising. Crash reports (Sentry) exist only in a build that was given a key at build time; the published builds have none.
- Drives, logbook and settings stay on the device. The app does **not encrypt its database itself**; it relies on the encryption of the device. Android backup of app data is switched off (`allowBackup="false"`).
- Only the launcher activity is exported; the service and the file provider are not.
- Exports (CSV of drives, logbook, app log) are written where you choose or handed to the app you choose. They can contain the name of your adapter and error messages; check them before you share them.
- Release builds are signed with a key that is kept outside the repository. The SHA-256 fingerprint of its certificate is public: `scripts/release-cert.sha256`.

## Deutsch

### Eine Sicherheitslücke melden

Bitte **kein öffentliches Issue** für ein Sicherheitsproblem. Nutzen Sie stattdessen die private Meldung von GitHub:
Reiter **Security** dieses Repositorys → **Report a vulnerability**.

Nennen Sie bitte die App-Version (Einstellungen → App-Info), Ihre Android-Version, was Sie getan haben, was Sie erwartet haben und was passiert ist, und was ein Angreifer damit erreichen könnte.
**Senden Sie keine persönlichen Daten**: keine FIN, keine Bluetooth-Adresse Ihres Adapters, keine Standorte, keine Dateien Ihrer Fahrten oder Ihres Logbuchs.

Das ist ein Ein-Personen-Projekt. Ich antworte, sobald ich kann, kann aber keine Frist zusagen, und es gibt keine Prämie für Funde.

### Unterstützte Versionen

Nur die neueste Version ([README](docs/README.de.md), „Aktuelle Version“) und der aktuelle Stand von `main`. Es gibt keine Korrekturen für ältere Versionen.

### Was zählt und was nicht

Zuständig bin ich für: die Android-App, die Build- und Release-Skripte in diesem Repository und den Umgang der App mit ihren eigenen Daten und ihrem Signaturschlüssel.

Nicht zuständig: Schwächen des OBD2-Protokolls oder ELM327-kompatibler Adapter (sie haben keine Anmeldung), wer schon entsperrten körperlichen Zugriff auf das Telefon hat, die Firmware von Fahrzeugen oder Adaptern und Fehler in fremden Bibliotheken, die die App nicht ausnutzbar macht (die gehören an die Bibliothek).

### Was die App tut, damit Sie eine Meldung einordnen können

- Sie **schreibt nie ins Fahrzeug**: nur die lesenden OBD2-Dienste (Modi 01, 02, 03, 07, 09), kein Löschen von Fehlercodes (kein Modus 04), keine Codierung.
- Die einzige Netzwerkverbindung ist die optionale Außentemperatur (gerundeter Standort an Open-Meteo, nur HTTPS). Es gibt keine Konten und keine Werbung. Absturzberichte (Sentry) gibt es nur in einem Build, der beim Bauen einen Schlüssel bekommen hat; die veröffentlichten Builds haben keinen.
- Fahrten, Logbuch und Einstellungen bleiben auf dem Gerät. Die App **verschlüsselt ihre Datenbank nicht selbst**, sie verlässt sich auf die Verschlüsselung des Geräts. Die Android-Datensicherung der App-Daten ist abgeschaltet (`allowBackup="false"`).
- Exportiert ist nur die Start-Activity, der Dienst und der FileProvider sind es nicht.
- Exporte (CSV der Fahrten, des Logbuchs, des App-Logs) landen dort, wo Sie es wählen, oder bei der App, an die Sie sie weitergeben. Sie können den Namen Ihres Adapters und Fehlermeldungen enthalten; prüfen Sie sie, bevor Sie sie teilen.
- Release-Builds sind mit einem Schlüssel signiert, der außerhalb des Repositorys liegt. Der SHA-256-Fingerabdruck seines Zertifikats ist öffentlich: `scripts/release-cert.sha256`.
