package de.adv990de.obd2.ui.licenses

import androidx.annotation.StringRes
import de.adv990de.obd2.R

/**
 * Die Lizenztexte, die in der App stehen. Die Dateien liegen in `docs/licenses/` und werden von Gradle als Assets
 * eingebunden (`assets.srcDir`) — eine Quelle fuer Repository und APK.
 */
enum class LicenseKind(val label: String, val assetName: String) {
    APACHE_2("Apache License 2.0", "Apache-2.0.txt"),
    MIT_SENTRY("MIT License (Sentry)", "MIT-Sentry.txt"),
    OFL_IBM_PLEX("SIL Open Font License 1.1 (IBM Plex Mono)", "IBMPlexMono-OFL.txt"),
    OFL_SPACE_GROTESK("SIL Open Font License 1.1 (Space Grotesk)", "SpaceGrotesk-OFL.txt"),
}

/**
 * Eine mitgelieferte Komponente. [groupPrefixes] sind die Gradle-Gruppen, die sie abdeckt — `LicenseCatalogTest` zwingt jede
 * neue DIREKTE Abhaengigkeit zu einem Eintrag. Namen sind Eigennamen und nicht uebersetzt; [detailRes] ist der erklaerende
 * Text. Gepflegt von Hand, nach dem, was im Release-APK landet (R8-Mapping geprueft: AndroidX, Kotlin/Coroutines, Okio,
 * Dagger/Hilt, Sentry).
 */
data class LicensedComponent(
    val name: String,
    @StringRes val detailRes: Int,
    val license: LicenseKind,
    val groupPrefixes: List<String> = emptyList(),
)

val LICENSED_COMPONENTS: List<LicensedComponent> = listOf(
    LicensedComponent("Android Jetpack (AndroidX)", R.string.licenses_detail_androidx, LicenseKind.APACHE_2, listOf("androidx.")),
    LicensedComponent("Kotlin / kotlinx.coroutines", R.string.licenses_detail_kotlin, LicenseKind.APACHE_2, listOf("org.jetbrains.kotlin")),
    LicensedComponent("Dagger / Hilt", R.string.licenses_detail_dagger, LicenseKind.APACHE_2, listOf("com.google.dagger")),
    LicensedComponent("Okio", R.string.licenses_detail_okio, LicenseKind.APACHE_2, listOf("com.squareup.okio")),
    LicensedComponent("Sentry SDK for Android", R.string.licenses_detail_sentry, LicenseKind.MIT_SENTRY, listOf("io.sentry")),
    LicensedComponent("IBM Plex Mono", R.string.licenses_detail_plex, LicenseKind.OFL_IBM_PLEX),
    LicensedComponent("Space Grotesk", R.string.licenses_detail_grotesk, LicenseKind.OFL_SPACE_GROTESK),
)
