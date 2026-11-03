<!--
Pull requests only after we agreed on the change in an issue (CONTRIBUTING.md).
Pull Requests nur nach Absprache der Änderung in einem Issue (CONTRIBUTING.md).
-->

## What and why / Was und wozu

Agreed in issue / Abgesprochen in Issue: #

## Checklist / Checkliste

- [ ] We agreed on this change in an issue first. / Die Änderung ist vorher in einem Issue abgesprochen.
- [ ] One focused change, small enough to review. / Eine zusammenhängende Änderung, klein genug zum Prüfen.
- [ ] Tests added or changed; the whole suite passes (`./gradlew :app:testDebugUnitTest`). / Tests ergänzt oder angepasst; die ganze Suite ist grün (`./gradlew :app:testDebugUnitTest`).
- [ ] Every text in `values/`, `values-de/` and `values-es/`; no hard-coded texts or colours. / Jeder Text in `values/`, `values-de/` und `values-es/`; keine festen Texte oder Farben.
- [ ] README and `CLAUDE.md` changed where this change makes a statement wrong (version, test counts, target SDK). / README und `CLAUDE.md` angepasst, wo diese Änderung eine Aussage falsch macht (Version, Testzahlen, Ziel-SDK).
- [ ] A new dependency has its checksums in `gradle/verification-metadata.xml` and an entry in the license catalogue. / Eine neue Abhängigkeit hat ihre Prüfsummen in `gradle/verification-metadata.xml` und einen Eintrag im Lizenzkatalog.
- [ ] No personal data in code, tests, screenshots or commit messages. / Keine persönlichen Daten in Code, Tests, Screenshots oder Commit-Nachrichten.
- [ ] If this touches screens, the database or resources: I say so here (the device tests need a phone, the owner runs them). / Wenn das Bildschirme, Datenbank oder Ressourcen berührt: Ich schreibe es hier (die Geräte-Tests brauchen ein Telefon, der Eigentümer lässt sie laufen).
