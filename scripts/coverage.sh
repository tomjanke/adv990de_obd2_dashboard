#!/usr/bin/env bash
#
# Testabdeckung messen (JaCoCo) und aufschreiben. Nur auf Abruf: ein normaler Build, der Gate-Lauf und jeder Release bleiben davon unberuehrt — die Einstellungen im
# Build (app/build.gradle.kts) gelten nur mit -PcoverageReport, das dieses Skript setzt.
#
#   scripts/coverage.sh              JVM- UND Geraete-Tests, schreibt docs/qa/coverage.md. Rund 17 Minuten (gemessen: 16:37 min am 2026-10-26); das Telefon muss angeschlossen, wach und entsperrt
#                                    sein (die Sperre umgeht das Skript nie). `connectedDebugAndroidTest` deinstalliert die Debug-App danach, wie immer.
#   scripts/coverage.sh --jvm-only   nur die JVM-Tests, wenige Minuten, kein Geraet. Eine UNTERGRENZE (die Oberflaeche laeuft fast nur ueber die Geraete-Tests) —
#                                    schreibt deshalb NICHT docs/qa/coverage.md, sondern app/build/reports/jacoco/coverage-jvm.md; der festgehaltene Stand
#                                    (die echte Zahl) wird nie von einer Untergrenze ueberschrieben.
#
# Danach: die Zahlen je Paket stehen in der Markdown-Datei, der HTML-Bericht (Zeile fuer Zeile) in app/build/reports/jacoco/coverageReport/html/index.html.
# Die Rechnung steht in scripts/coverage_summary.py (Tests: scripts/test_coverage_summary.py); was die Zahl aussagt und was nicht, steht in docs/qa/coverage.md.
set -euo pipefail
cd "$(dirname "$0")/.."

mode=combined
case "${1:-}" in
    "") ;;
    --jvm-only) mode=jvm ;;
    *) echo "Aufruf: scripts/coverage.sh [--jvm-only]" >&2; exit 2 ;;
esac

# Das Geraet vor dem langen Lauf pruefen: genau eines, wach, entsperrt. Ein gesperrtes Telefon liesse den Lauf nach Minuten ohne Fehlermeldung haengen.
check_device() {
    command -v adb >/dev/null || { echo "adb fehlt (Android SDK platform-tools)." >&2; exit 1; }
    local count
    count="$(adb devices | awk 'NR > 1 && $2 == "device"' | wc -l | tr -d ' ')"
    [ "$count" = "1" ] || { echo "Es muss genau ein Geraet angeschlossen sein (gefunden: $count)." >&2; exit 1; }
    adb shell dumpsys power | grep -q 'mWakefulness=Awake' || { echo "Das Telefon ist nicht wach — bitte den Bildschirm einschalten." >&2; exit 1; }
    adb shell dumpsys window | grep -q 'isKeyguardShowing=false' || { echo "Das Telefon ist gesperrt — bitte entsperren (das Skript umgeht die Sperre nie)." >&2; exit 1; }
}

# Alte Messdaten weg: ein Bericht darf nie Daten eines frueheren Laufs mitzaehlen.
rm -rf app/build/outputs/unit_test_code_coverage app/build/outputs/code_coverage app/build/reports/jacoco
mkdir -p app/build/reports/jacoco
log="app/build/reports/jacoco/gradle.log"

props=(-PcoverageReport)
tasks=(:app:cleanTestDebugUnitTest :app:testDebugUnitTest)
if [ "$mode" = jvm ]; then
    props+=(-PcoverageJvmOnly)
    out="app/build/reports/jacoco/coverage-jvm.md"
else
    check_device
    tasks+=(:app:connectedDebugAndroidTest)
    out="docs/qa/coverage.md"
fi
tasks+=(:app:coverageReport)

# Ohne pipefail-Verlust: der Exit-Code von Gradle zaehlt (ein roter Test bricht ab, es gibt dann keinen Stand).
./gradlew "${props[@]}" "${tasks[@]}" 2>&1 | tee "$log"

summary=(python3 -I scripts/coverage_summary.py app/build/reports/jacoco/coverageReport/coverageReport.xml --mode "$mode" --out "$out"
    --jvm-results app/build/test-results/testDebugUnitTest --gradle-log "$log")
if [ "$mode" = combined ]; then
    summary+=(--device-results app/build/outputs/androidTest-results/connected/debug)
fi
"${summary[@]}"

echo
echo "Testabdeckung geschrieben: $out"
echo "HTML-Bericht: app/build/reports/jacoco/coverageReport/html/index.html"
