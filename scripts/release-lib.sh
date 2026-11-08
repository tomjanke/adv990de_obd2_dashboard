#!/usr/bin/env bash
#
# Pruefungen, die scripts/release.sh vor und nach dem Signieren macht — hier getrennt, damit sie einzeln und ECHT
# getestet werden koennen (app/src/test/.../docs/ReleaseScriptLibTest.kt). Nicht ausfuehren, nur einlesen (`source`).
#
# Hintergrund (QS-Bericht B31): Keystore und ~/.gradle/gradle.properties hatten Modus 644 (fuer alle lesbar), und ein
# falscher Schluessel haette ein Update erzeugt, das sich nicht ueber die installierte App einspielen laesst.

# Dateimodus als Oktalzahl (macOS: stat -f, Linux: stat -c).
file_mode() { stat -f '%Lp' "$1" 2>/dev/null || stat -c '%a' "$1"; }

# Erfolg (0), wenn Gruppe und Andere keinerlei Rechte an der Datei haben.
is_private_file() {
    local mode
    mode="$(file_mode "$1")" || return 1
    [ "${mode: -2}" = "00" ]
}

# SHA-256-Fingerabdruck des ersten Signierers aus der Ausgabe von `apksigner verify --print-certs` (stdin), klein geschrieben.
signer_sha256() {
    sed -n 's/^Signer #1 certificate SHA-256 digest: \([0-9a-fA-F]*\).*/\1/p' | head -1 | tr 'A-F' 'a-f'
}

# Erfolg, wenn der Signierer in der apksigner-Ausgabe (stdin) dem festgehaltenen Fingerabdruck in $1 entspricht.
# Ein leerer Fingerabdruck (apksigner lieferte kein Zertifikat) oder eine leere Datei ist nie ein Treffer.
signer_matches_pin() {
    local pin_file="$1" pinned actual
    pinned="$(tr -d '[:space:]' < "$pin_file" | tr 'A-F' 'a-f')"
    actual="$(signer_sha256)"
    if [ -z "$pinned" ] || [ -z "$actual" ] || [ "$actual" != "$pinned" ]; then
        echo "Signaturzertifikat (SHA-256): ${actual:-<keines>}, erwartet: ${pinned:-<leer>}" >&2
        return 1
    fi
}

# Wert einer Gradle-Property: Umgebungsvariable zuerst (wie `findProperty(key) ?: System.getenv(key)` im Build), sonst die
# Zeile `KEY=...` aus $2 (Standard ~/.gradle/gradle.properties). Gibt nur den Wert des erfragten Schluessels aus.
gradle_property() {
    local key="$1" file="${2:-$HOME/.gradle/gradle.properties}" value
    value="${!key:-}"
    if [ -z "$value" ] && [ -f "$file" ]; then
        value="$(grep -E "^${key}=" "$file" | head -1 | cut -d= -f2- || true)"
    fi
    printf '%s' "$value"
}

# Fuehrendes `~` wie im Build (`KEYSTORE_FILE`): "~" und "~/..." zeigen auf das Home-Verzeichnis, alles andere bleibt.
expand_home() {
    case "$1" in
        "~")   printf '%s' "$HOME" ;;
        "~/"*) printf '%s' "$HOME/${1#\~/}" ;;
        *)     printf '%s' "$1" ;;
    esac
}

# ---------------------------------------------------------------------------------------------------------------------
# Veroeffentlichen als GitHub-Release (`scripts/release.sh --publish`). Oeffentlich und nicht rueckholbar: alles, was sich ohne
# Netz und ohne Veroeffentlichen pruefen laesst, steht hier und ist getestet.

# Fester Dateiname der veroeffentlichten APK: der Link `.../releases/latest/download/<Name>` bleibt nur stabil, wenn er sich nie aendert.
RELEASE_ASSET_NAME="OBD-Dashboard.apk"

# Der Text einer Version aus RELEASE_NOTES.md ($2): ab der Zeile nach `## Version <$1> (` bis vor die naechste Zeile `---` oder
# `## Version `, ohne fuehrende und abschliessende Leerzeilen. Die Version muss genau stimmen (1.45.1 ist nicht 1.45.10).
# Nichts gefunden, Datei fehlt oder Text leer: Exit 1, nichts auf stdout.
release_notes_section() {
    local version="$1" file="$2" body
    [ -f "$file" ] || return 1
    body="$(awk -v head="## Version $version (" '
        index($0, head) == 1 { on = 1; found = 1; next }
        on && ($0 == "---" || index($0, "## Version ") == 1) { exit }
        on { print }
        END { exit found ? 0 : 1 }
    ' "$file")" || return 1
    body="$(printf '%s\n' "$body" | sed -e '/./,$!d')"
    [ -n "$body" ] || return 1
    printf '%s\n' "$body"
}

# Eine Zeile fuer `shasum -a 256 -c`: Pruefsumme, zwei Leerzeichen, Dateiname.
sha256_line() { printf '%s  %s\n' "$1" "$2"; }

# Der Text des Release: die Notizen der Version ($1, aus der Datei $2) plus ein Installationsblock auf Englisch und Deutsch mit der
# Pruefsumme der APK ($3) und dem Fingerabdruck des Signaturzertifikats ($4). Fehlt eines davon oder gibt es keine Notizen: Exit 1,
# nichts auf stdout — ein Release geht nie leer oder ohne Pruefsumme hinaus.
compose_release_notes() {
    local version="$1" file="$2" apk_sha="$3" cert_sha="$4" body
    [ -n "$apk_sha" ] && [ -n "$cert_sha" ] || return 1
    body="$(release_notes_section "$version" "$file")" || return 1
    printf '%s\n\n---\n\n### Installation\n\n' "$body"
    printf '%s\n\n' "**English:** Download \`$RELEASE_ASSET_NAME\` below and open it on the phone; Android asks once to allow installing from this source. The app does not update itself — to update, download the newer release. It needs a classic-Bluetooth ELM327 adapter; without one it only shows the \"preview · not connected\" state. Check the download with \`shasum -a 256 -c $RELEASE_ASSET_NAME.sha256\`."
    printf '%s\n\n' "**Deutsch:** Die Datei \`$RELEASE_ASSET_NAME\` unten herunterladen und auf dem Telefon öffnen; Android fragt einmal, ob die Installation aus dieser Quelle erlaubt sein soll. Die App aktualisiert sich nicht selbst — für ein Update das neuere Release herunterladen. Sie braucht einen klassischen Bluetooth-ELM327-Adapter; ohne ihn zeigt sie nur „Vorschau · nicht verbunden“. Den Download prüfen: \`shasum -a 256 -c $RELEASE_ASSET_NAME.sha256\`."
    printf 'APK SHA-256: `%s`  \n' "$apk_sha"
    printf 'Signing certificate SHA-256: `%s` (same as `scripts/release-cert.sha256` in the repository)\n' "$cert_sha"
}

# Erfolg nur, wenn der Nutzer das Veroeffentlichen der Version $1 ausdruecklich bestaetigt hat: RELEASE_PUBLISH_CONFIRM ist genau
# diese Version, oder die Version wird an einem Terminal getippt. Alles andere (leer, `y`, eine andere Version, mit Leerzeichen,
# eine Eingabe aus einer Pipe) ist keine Bestaetigung.
publish_confirmed() {
    local version="$1" typed
    if [ -n "${RELEASE_PUBLISH_CONFIRM:-}" ]; then
        [ "$RELEASE_PUBLISH_CONFIRM" = "$version" ]
        return
    fi
    [ -t 0 ] || return 1
    printf 'Oeffentlich und nicht rueckholbar. Zum Veroeffentlichen die Version %s tippen: ' "$version" >&2
    read -r typed || return 1
    [ "$typed" = "$version" ]
}

# Der Commit, auf den der Tag $1 im Verzeichnis $2 (Standard: hier) zeigt — bei einem annotierten Tag der Commit, nicht das Tag-Objekt.
# Der Tag fehlt: Exit 1, nichts auf stdout.
tag_revision() {
    local tag="$1" dir="${2:-.}" rev
    rev="$(git -C "$dir" rev-list -n 1 "refs/tags/$tag" 2>/dev/null)" || return 1
    [ -n "$rev" ] || return 1
    printf '%s' "$rev"
}

# Der Commit des Tags $1 aus der Ausgabe von `git ls-remote --tags` (stdin): bei einem annotierten Tag die Zeile `<tag>^{}` (der
# Commit), sonst die Zeile des Tags. Ein Tag, der nur als Praefix passt (v1.45.10 fuer v1.45.1), zaehlt nicht. Kein Treffer: Exit 1.
remote_tag_revision() {
    awk -v t="refs/tags/$1" '
        $2 == t "^{}" { peeled = $1 }
        $2 == t { plain = $1 }
        END { if (peeled != "") print peeled; else if (plain != "") print plain; else exit 1 }
    '
}
