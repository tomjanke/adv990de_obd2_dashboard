# Contributing / Mitwirken

English first, deutsch darunter.

## English

This is a one-person project with strict conventions. Bug reports and ideas are welcome. **Pull requests only after we agreed on the change in an issue.**
A pull request that nobody asked for may stay unanswered or be declined, however good it is; please talk to me first.

### Bug reports

Open an issue and tell me: the app version (Settings → App info), your Android version and device, the type of your adapter (a name is optional), the steps, what you expected and what happened.
The app log can help (App logs → export symbol). **Remove personal data before you attach anything**: VIN, Bluetooth address and name of your adapter, locations, files of your drives or logbook.

A security problem does **not** belong in an issue: see [`SECURITY.md`](SECURITY.md).

### Ideas

Open an issue and describe what you want to do and why. The app is read-only towards the vehicle (it never writes, no mode 04) and is built to be read at a glance on a motorcycle; ideas that need a write access or a lot of operation while riding will be declined.

### Pull requests (after agreement)

- **One focused change** per pull request, small enough to review.
- **Tests are required**: a new view model, parser or repository method comes with JVM tests (`./gradlew :app:testDebugUnitTest`). The whole suite must pass; it also checks rules across files (documentation, resources, licenses, the build files).
- **All three languages**: every text in `values/` (English), `values-de/` and `values-es/` (`StringResourcesParityTest` checks it). No hard-coded texts or colours: texts through `stringResource`, colours through `MaterialTheme.colorScheme`.
- **Documentation moves with the code**: README and `CLAUDE.md` state facts that tests compare with the build (version, test counts, target SDK). Change what your change makes wrong.
- **A new dependency** needs its checksums in `gradle/verification-metadata.xml` (command: `CLAUDE.md`, "Build-Integrität") and an entry in the license catalogue.
- **No personal data** in code, tests, screenshots or commit messages: use invented values.
- Commit messages in English, imperative, with a prefix as in the history (`feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:`, `build:`).
- I run the device tests (they need a phone) myself before I merge a change that touches the screens, the database or resources.

The conventions are written down in [`CLAUDE.md`](CLAUDE.md) (German). It is long; the sections "Code-Konventionen" and "Teststufen" are the ones that matter for a pull request.

### License of contributions

By submitting a contribution you agree that it is licensed under the Apache License 2.0 (section 5 of the license; see [`LICENSE`](LICENSE)). There is no separate contributor agreement.

## Deutsch

Das ist ein Ein-Personen-Projekt mit strengen Konventionen. Fehlerberichte und Ideen sind willkommen. **Pull Requests nur, nachdem wir die Änderung in einem Issue abgesprochen haben.**
Ein Pull Request, den niemand erbeten hat, kann unbeantwortet bleiben oder abgelehnt werden, so gut er ist; sprechen Sie mich bitte vorher an.

### Fehlerberichte

Eröffnen Sie ein Issue und nennen Sie: die App-Version (Einstellungen → App-Info), Ihre Android-Version und das Gerät, die Art Ihres Adapters (der Name ist freiwillig), die Schritte, was Sie erwartet haben und was passiert ist.
Das App-Log kann helfen (App-Logs → Export-Symbol). **Entfernen Sie persönliche Daten, bevor Sie etwas anhängen**: FIN, Bluetooth-Adresse und Name Ihres Adapters, Standorte, Dateien Ihrer Fahrten oder Ihres Logbuchs.

Ein Sicherheitsproblem gehört **nicht** in ein Issue: siehe [`SECURITY.md`](SECURITY.md).

### Ideen

Eröffnen Sie ein Issue und beschreiben Sie, was Sie vorhaben und wozu. Die App ist gegenüber dem Fahrzeug rein lesend (sie schreibt nie, kein Modus 04) und darauf gebaut, am Motorrad auf einen Blick gelesen zu werden; Ideen, die einen Schreibzugriff oder viel Bedienung während der Fahrt brauchen, lehne ich ab.

### Pull Requests (nach Absprache)

- **Eine zusammenhängende Änderung** je Pull Request, klein genug zum Prüfen.
- **Tests sind Pflicht**: ein neues ViewModel, ein neuer Parser oder eine neue Repository-Methode kommt mit JVM-Tests (`./gradlew :app:testDebugUnitTest`). Die ganze Suite muss grün sein; sie prüft auch Regeln über Dateien hinweg (Dokumentation, Ressourcen, Lizenzen, die Build-Dateien).
- **Alle drei Sprachen**: jeder Text in `values/` (Englisch), `values-de/` und `values-es/` (`StringResourcesParityTest` prüft das). Keine festen Texte oder Farben: Texte über `stringResource`, Farben über `MaterialTheme.colorScheme`.
- **Die Dokumentation zieht mit**: README und `CLAUDE.md` nennen Tatsachen, die Tests mit dem Build vergleichen (Version, Testzahlen, Ziel-SDK). Ändern Sie, was Ihre Änderung falsch macht.
- **Eine neue Abhängigkeit** braucht ihre Prüfsummen in `gradle/verification-metadata.xml` (Befehl: `CLAUDE.md`, „Build-Integrität“) und einen Eintrag im Lizenzkatalog.
- **Keine persönlichen Daten** in Code, Tests, Screenshots oder Commit-Nachrichten: erfundene Werte nehmen.
- Commit-Nachrichten englisch, im Imperativ, mit Präfix wie in der Historie (`feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:`, `build:`).
- Die Geräte-Tests (sie brauchen ein Telefon) lasse ich selbst laufen, bevor ich eine Änderung übernehme, die Bildschirme, Datenbank oder Ressourcen berührt.

Die Konventionen stehen in [`CLAUDE.md`](CLAUDE.md) (deutsch). Sie ist lang; für einen Pull Request zählen die Abschnitte „Code-Konventionen“ und „Teststufen“.

### Lizenz der Beiträge

Mit dem Einreichen eines Beitrags erklären Sie sich einverstanden, dass er unter der Apache License 2.0 steht (Abschnitt 5 der Lizenz; siehe [`LICENSE`](LICENSE)). Eine eigene Beitragsvereinbarung gibt es nicht.
