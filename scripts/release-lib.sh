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
