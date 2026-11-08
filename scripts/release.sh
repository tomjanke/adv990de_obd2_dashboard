#!/usr/bin/env bash
#
# Release-Ablauf fuer das OBD Dashboard.
#
# Haelt fest, was bei v1.23.0 schiefging (QS-Bericht B32): Das APK wurde aus einem NOCH NICHT COMMITTETEN Versions-Bump
# gebaut — die eingebettete Revision (239b14c) war nicht die des Tags (9e72377), und `mapping.txt` wurde nirgends
# aufgehoben. Die Reihenfolge ist deshalb fest: erst Version bumpen und COMMITTEN, dann bauen.
#
# Aufruf:
#   scripts/release.sh --check      nur die Vorpruefung (keine Tests, kein Build)
#   scripts/release.sh              Vorpruefung, Unit-Tests, assembleRelease, Pruefung des APK, mapping.txt archivieren
#   scripts/release.sh --install    zusaetzlich: auf das angeschlossene Geraet installieren und die Version pruefen
#   scripts/release.sh --publish    NACH Tag und Push: die APK als GitHub-Release veroeffentlichen (OEFFENTLICH, nicht rueckholbar; baut nichts,
#                                   nimmt die APK des letzten Laufs). Verlangt RELEASE_PUBLISH_CONFIRM=<Version> oder die getippte Version.
#   scripts/release.sh --publish --dry-run   dieselben Pruefungen, zeigt Dateien, Text und den gh-Befehl, legt nichts an, verlangt keine Bestaetigung
#
# Taggen und Pushen bleiben bewusst Handarbeit (`git tag -a vX.Y.Z -m "Release X.Y.Z (versionCode N)" && git push origin main vX.Y.Z`), nach dem Smoke-Test
# (`python3 -u scripts/smoke.py <versionName> <versionCode>`, Geraet entsperrt).
#
# Umgebung:
#   RELEASE_ARCHIVE_DIR   Ablage fuer mapping.txt und APK-Pruefsumme (Standard: $HOME/obd2-release-archive). Ausserhalb des
#                         Repos: die mapping.txt ist ~45 MB je Release.
#   Die Signierung kommt aus den Gradle-Properties/Umgebungsvariablen aus dem README ("assembleRelease signiert via env-vars", docs/README.de.md).
#   KEYSTORE_FILE         Ort des Keystores (Standard: keystore.jks im Projekt). Empfohlen: ausserhalb des Projektbaums (B31).
#
# Zusaetzlich (B31): Keystore und ~/.gradle/gradle.properties duerfen nur der Eigentuemer lesen, und das APK muss mit dem
# in scripts/release-cert.sha256 festgehaltenen Zertifikat signiert sein (oeffentlicher Fingerabdruck, kein Geheimnis).

set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$(pwd)"
# shellcheck source=scripts/release-lib.sh
source "$ROOT/scripts/release-lib.sh"
MODE_CHECK=0
MODE_INSTALL=0
MODE_PUBLISH=0
DRY_RUN=0
for arg in "$@"; do
    case "$arg" in
        --check)   MODE_CHECK=1 ;;
        --install) MODE_INSTALL=1 ;;
        --publish) MODE_PUBLISH=1 ;;
        --dry-run) DRY_RUN=1 ;;
        -h|--help) sed -n '2,24p' "$0"; exit 0 ;;
        *) echo "Unbekannte Option: $arg (siehe --help)" >&2; exit 2 ;;
    esac
done
[ "$DRY_RUN" -eq 0 ] || [ "$MODE_PUBLISH" -eq 1 ] || { echo "--dry-run gibt es nur zusammen mit --publish" >&2; exit 2; }
[ "$MODE_PUBLISH" -eq 0 ] || { [ "$MODE_CHECK" -eq 0 ] && [ "$MODE_INSTALL" -eq 0 ]; } || { echo "--publish gilt allein (nicht mit --check oder --install)" >&2; exit 2; }

fail() { echo "FEHLER: $*" >&2; exit 1; }
step() { echo; echo "==> $*"; }

# ---------------------------------------------------------------------------------------------------------------
step "Vorpruefung"

VERSION_NAME="$(sed -n 's/.*val appVersionName = "\([^"]*\)".*/\1/p' app/build.gradle.kts | head -1)"
VERSION_CODE="$(sed -n 's/.*val appVersionCode = \([0-9]*\).*/\1/p' app/build.gradle.kts | head -1)"
[ -n "$VERSION_NAME" ] && [ -n "$VERSION_CODE" ] || fail "appVersionName/appVersionCode in app/build.gradle.kts nicht gefunden"
echo "Version laut build.gradle.kts: $VERSION_NAME (versionCode $VERSION_CODE)"

# 1. Sauberer Baum: sonst traegt das APK eine Revision, die nicht dem Stand entspricht (B32).
if [ -n "$(git status --porcelain)" ]; then
    git status --short >&2
    fail "Der Arbeitsbaum ist nicht sauber. Version bumpen UND committen, dann bauen (B32)."
fi

# 2. Die beiden ausfuehrlichen READMEs und die Release-Notes nennen dieselbe Version (ReleaseDocsConsistencyTest prueft das ebenfalls;
#    die Kurzfassung README.md nennt keine Version).
grep -q "Aktuelle Version: \*\*$VERSION_NAME\*\* (versionCode $VERSION_CODE)" docs/README.de.md \
    || fail "docs/README.de.md nennt nicht 'Aktuelle Version: **$VERSION_NAME** (versionCode $VERSION_CODE)'"
grep -q "Current version: \*\*$VERSION_NAME\*\* (versionCode $VERSION_CODE)" docs/README.en.md \
    || fail "docs/README.en.md nennt nicht 'Current version: **$VERSION_NAME** (versionCode $VERSION_CODE)'"
head -20 RELEASE_NOTES.md | grep -q "^## Version $VERSION_NAME (versionCode $VERSION_CODE)" \
    || fail "Der oberste Eintrag von RELEASE_NOTES.md ist nicht '## Version $VERSION_NAME (versionCode $VERSION_CODE)'"

# 3. Der Tag existiert noch nicht (er wird nach dem Smoke-Test gesetzt) — beim Veroeffentlichen ist es umgekehrt (unten).
if [ "$MODE_PUBLISH" -eq 0 ] && git rev-parse -q --verify "refs/tags/v$VERSION_NAME" >/dev/null; then
    fail "Der Tag v$VERSION_NAME existiert schon — Version nicht gebumpt?"
fi

# 4. Die Geheimnisse sind nur fuer den Eigentuemer lesbar (B31: beide hatten Modus 644). Nur vorhandene Dateien: ohne
#    Keystore entsteht ohnehin kein signiertes APK (der Build-Schritt unten bricht dann ab).
KEYSTORE_PATH="$(expand_home "$(gradle_property KEYSTORE_FILE)")"
KEYSTORE_PATH="${KEYSTORE_PATH:-$ROOT/keystore.jks}"
for secret in "$KEYSTORE_PATH" "$HOME/.gradle/gradle.properties"; do
    if [ -f "$secret" ] && ! is_private_file "$secret"; then
        fail "$secret ist fuer andere Benutzer lesbar (Modus $(file_mode "$secret")). Abhilfe: chmod 600 \"$secret\" (B31)"
    fi
done
case "$KEYSTORE_PATH" in
    "$ROOT"/*) echo "Hinweis: Der Keystore liegt im Projektbaum ($KEYSTORE_PATH). Besser ausserhalb ablegen und KEYSTORE_FILE setzen (B31)." ;;
esac

HEAD_SHA="$(git rev-parse HEAD)"
echo "HEAD: $HEAD_SHA ($(git log -1 --format=%s))"
echo "Vorpruefung bestanden."
[ "$MODE_CHECK" -eq 1 ] && exit 0

# ---------------------------------------------------------------------------------------------------------------
if [ "$MODE_PUBLISH" -eq 1 ]; then
    step "Veroeffentlichen als GitHub-Release$([ "$DRY_RUN" -eq 1 ] && echo " (Trockenlauf, nichts wird angelegt)")"
    TAG="v$VERSION_NAME"
    APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
    [ -f "$APK" ] || fail "APK nicht gefunden: $APK (erst scripts/release.sh --install laufen lassen)"

    # Der Tag existiert, ist gepusht und zeigt bei origin auf denselben Commit wie lokal.
    LOCAL_REV="$(tag_revision "$TAG" "$ROOT")" || fail "Der Tag $TAG existiert lokal nicht (erst taggen)"
    REMOTE_REV="$(git ls-remote --tags origin "refs/tags/$TAG*" | remote_tag_revision "$TAG")" \
        || fail "Der Tag $TAG ist bei origin nicht vorhanden (erst Tag und main zusammen pushen, Befehl am Ende eines normalen Laufs)"
    [ "$LOCAL_REV" = "$REMOTE_REV" ] || fail "Der Tag $TAG zeigt bei origin auf $REMOTE_REV, lokal auf $LOCAL_REV"
    echo "Tag $TAG zeigt lokal und bei origin auf $LOCAL_REV"

    # Die APK wurde aus genau dem Commit des Tags gebaut (HEAD darf weiter sein: Doku-Commits nach dem Tag sind ueblich).
    EMBEDDED="$(unzip -p "$APK" META-INF/version-control-info.textproto | sed -n 's/.*revision: "\([0-9a-f]*\)".*/\1/p' | head -1)"
    [ -n "$EMBEDDED" ] || fail "Keine eingebettete Revision im APK gefunden"
    [ "$EMBEDDED" = "$LOCAL_REV" ] || fail "Die APK wurde aus $EMBEDDED gebaut, der Tag $TAG zeigt auf $LOCAL_REV"
    echo "Eingebettete Revision == Commit des Tags"

    # Dieselbe Datei wie die archivierte (Pruefsumme des letzten release.sh-Laufs) und mit dem festgehaltenen Zertifikat signiert.
    ARCHIVE="${RELEASE_ARCHIVE_DIR:-$HOME/obd2-release-archive}/$VERSION_NAME"
    [ -f "$ARCHIVE/app-release.apk.sha256" ] || fail "Keine archivierte Pruefsumme: $ARCHIVE/app-release.apk.sha256"
    APK_SHA="$(shasum -a 256 "$APK" | cut -d' ' -f1)"
    [ "$APK_SHA" = "$(cut -d' ' -f1 "$ARCHIVE/app-release.apk.sha256")" ] \
        || fail "Die APK ist nicht die archivierte (Pruefsumme weicht von $ARCHIVE/app-release.apk.sha256 ab)"
    APKSIGNER="$(ls "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1 || true)"
    [ -n "$APKSIGNER" ] || fail "apksigner nicht gefunden (Android SDK build-tools)"
    CERTS="$("$APKSIGNER" verify --print-certs "$APK")" || fail "apksigner verify ist fehlgeschlagen"
    printf '%s\n' "$CERTS" | signer_matches_pin "$ROOT/scripts/release-cert.sha256" \
        || fail "Die APK ist nicht mit dem erwarteten Zertifikat signiert (scripts/release-cert.sha256)"
    CERT_SHA="$(printf '%s\n' "$CERTS" | signer_sha256)"
    echo "APK SHA-256 $APK_SHA (== archiviert), Zertifikat $CERT_SHA (== Pin)"

    command -v gh >/dev/null || fail "gh (GitHub CLI) nicht gefunden"
    gh auth status >/dev/null 2>&1 || fail "gh ist nicht angemeldet (gh auth login)"
    if gh release view "$TAG" >/dev/null 2>&1; then fail "Zu $TAG gibt es schon ein Release"; fi

    # Bereitstellung in einem Temp-Ordner: feste Dateinamen, Pruefsummen-Datei, Text.
    STAGE="$(mktemp -d)"; DOWNLOADED="$(mktemp -d)"
    trap 'rm -rf "$STAGE" "$DOWNLOADED"' EXIT
    cp "$APK" "$STAGE/$RELEASE_ASSET_NAME"
    sha256_line "$APK_SHA" "$RELEASE_ASSET_NAME" > "$STAGE/$RELEASE_ASSET_NAME.sha256"
    compose_release_notes "$VERSION_NAME" "$ROOT/RELEASE_NOTES.md" "$APK_SHA" "$CERT_SHA" > "$STAGE/notes.md" \
        || fail "Kein Release-Text: RELEASE_NOTES.md hat keinen Abschnitt fuer $VERSION_NAME"
    GH_ARGS=(release create "$TAG" "$STAGE/$RELEASE_ASSET_NAME" "$STAGE/$RELEASE_ASSET_NAME.sha256"
        --verify-tag --title "OBD Dashboard $VERSION_NAME" --notes-file "$STAGE/notes.md" --latest)

    step "Das wuerde veroeffentlicht"
    echo "Repository: $(gh repo view --json url -q .url)  (Sichtbarkeit: $(gh repo view --json visibility -q .visibility))"
    echo "Dateien:    $RELEASE_ASSET_NAME ($(wc -c < "$STAGE/$RELEASE_ASSET_NAME" | tr -d ' ') Byte), $RELEASE_ASSET_NAME.sha256"
    echo "Titel:      OBD Dashboard $VERSION_NAME"
    echo "--- Text ------------------------------------------------------------------"
    cat "$STAGE/notes.md"
    echo "---------------------------------------------------------------------------"
    echo "Befehl:     gh ${GH_ARGS[*]}"
    if [ "$DRY_RUN" -eq 1 ]; then
        echo; echo "Trockenlauf: nichts angelegt."
        exit 0
    fi

    publish_confirmed "$VERSION_NAME" \
        || fail "Nicht bestaetigt. Das Veroeffentlichen ist oeffentlich und nicht rueckholbar: RELEASE_PUBLISH_CONFIRM=$VERSION_NAME setzen oder die Version am Terminal tippen."
    step "gh release create"
    gh "${GH_ARGS[@]}"

    # Ruecklesen: die veroeffentlichten Dateien sind die, die hochgeladen wurden.
    step "Ruecklesen"
    FAILED_READBACK="Das Release ist schon OEFFENTLICH. Pruefen, nicht blind wiederholen; entfernen mit: gh release delete $TAG (der Tag bleibt, Kopien bleiben Kopien)."
    gh release download "$TAG" --dir "$DOWNLOADED" || fail "Download der veroeffentlichten Dateien schlug fehl. $FAILED_READBACK"
    [ -f "$DOWNLOADED/$RELEASE_ASSET_NAME" ] && [ -f "$DOWNLOADED/$RELEASE_ASSET_NAME.sha256" ] \
        || fail "Es fehlt eine der beiden Dateien im Release. $FAILED_READBACK"
    [ "$(shasum -a 256 "$DOWNLOADED/$RELEASE_ASSET_NAME" | cut -d' ' -f1)" = "$APK_SHA" ] \
        || fail "Die heruntergeladene APK hat eine andere Pruefsumme als die hochgeladene. $FAILED_READBACK"
    ( cd "$DOWNLOADED" && shasum -a 256 -c "$RELEASE_ASSET_NAME.sha256" ) || fail "Die Pruefsummen-Datei passt nicht zur APK. $FAILED_READBACK"
    REPO_URL="$(gh repo view --json url -q .url)"
    step "Fertig"
    echo "Release:       $REPO_URL/releases/tag/$TAG"
    echo "Stabiler Link: $REPO_URL/releases/latest/download/$RELEASE_ASSET_NAME"
    exit 0
fi

# ---------------------------------------------------------------------------------------------------------------
step "Unit-Tests"
./gradlew :app:testDebugUnitTest

step "assembleRelease"
./gradlew assembleRelease

APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
MAPPING="$ROOT/app/build/outputs/mapping/release/mapping.txt"
[ -f "$APK" ] || fail "APK nicht gefunden: $APK (Signierung konfiguriert? Dann entsteht app-release-unsigned.apk)"
[ -f "$MAPPING" ] || fail "mapping.txt nicht gefunden: $MAPPING"

# ---------------------------------------------------------------------------------------------------------------
step "Pruefung des APK"

# Die eingebettete Revision ist genau die von HEAD — sonst wurde nicht aus dem committeten Stand gebaut (B32).
EMBEDDED="$(unzip -p "$APK" META-INF/version-control-info.textproto | sed -n 's/.*revision: "\([0-9a-f]*\)".*/\1/p' | head -1)"
[ -n "$EMBEDDED" ] || fail "Keine eingebettete Revision im APK gefunden"
[ "$EMBEDDED" = "$HEAD_SHA" ] || fail "Eingebettete Revision $EMBEDDED ist nicht HEAD $HEAD_SHA — aus einem anderen Stand gebaut"
echo "Eingebettete Revision == HEAD ($HEAD_SHA)"

# Signatur und Zertifikat (B31): Ein APK, das mit einem anderen Schluessel signiert ist (falscher Keystore, nach einem Umzug),
# liesse sich nicht ueber die installierte App einspielen. Ohne apksigner laesst sich das nicht pruefen — dann kein Release.
APKSIGNER="$(ls "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1 || true)"
[ -n "$APKSIGNER" ] || fail "apksigner nicht gefunden (Android SDK build-tools) — Signatur und Zertifikat lassen sich nicht pruefen"
CERTS="$("$APKSIGNER" verify --print-certs "$APK")" || fail "apksigner verify ist fehlgeschlagen"
printf '%s\n' "$CERTS" | head -3
printf '%s\n' "$CERTS" | signer_matches_pin "$ROOT/scripts/release-cert.sha256" \
    || fail "Das APK ist nicht mit dem erwarteten Zertifikat signiert (scripts/release-cert.sha256) — falscher Keystore?"
echo "Zertifikat entspricht dem festgehaltenen Fingerabdruck"

# ---------------------------------------------------------------------------------------------------------------
step "mapping.txt archivieren"
ARCHIVE="${RELEASE_ARCHIVE_DIR:-$HOME/obd2-release-archive}/$VERSION_NAME"
mkdir -p "$ARCHIVE"
cp "$MAPPING" "$ARCHIVE/mapping.txt"
( cd "$(dirname "$APK")" && shasum -a 256 "$(basename "$APK")" ) > "$ARCHIVE/app-release.apk.sha256"
echo "$HEAD_SHA" > "$ARCHIVE/revision.txt"
echo "Archiviert in $ARCHIVE (mapping.txt, APK-Pruefsumme, Revision)"

# ---------------------------------------------------------------------------------------------------------------
if [ "$MODE_INSTALL" -eq 1 ]; then
    step "Installation"
    adb get-state >/dev/null 2>&1 || fail "Kein Geraet ueber adb verbunden"
    adb install -r "$APK"
    INSTALLED="$(adb shell dumpsys package de.adv990de.obd2 | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -1)"
    [ "$INSTALLED" = "$VERSION_CODE" ] || fail "Installierter versionCode $INSTALLED, erwartet $VERSION_CODE"
    echo "Installiert: versionCode $INSTALLED"
fi

step "Fertig"
echo "Naechste Schritte (von Hand): Smoke-Test auf dem Geraet (python3 -u scripts/smoke.py $VERSION_NAME $VERSION_CODE), dann"
echo "  git tag -a v$VERSION_NAME -m \"Release $VERSION_NAME (versionCode $VERSION_CODE)\" && git push origin main v$VERSION_NAME"
