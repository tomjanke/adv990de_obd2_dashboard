package de.adv990de.obd2.crash

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import de.adv990de.obd2.data.AppPreferences
import io.sentry.Sentry
import io.sentry.android.core.SentryAndroid
import javax.inject.Inject

/** Die Sentry-Schnittstelle, die die App braucht — als Seam, damit der [CrashReportingController] ohne Android testbar ist. */
interface SentrySdk {
    /** Der DSN aus dem Manifest; `null` heisst: Diese Version ist ohne Sentry gebaut (leerer DSN). */
    val configuredDsn: String?

    /** Ob Sentry gerade initialisiert ist und Berichte versenden wuerde. */
    val isActive: Boolean

    fun start()
    fun stop()
}

/** Ein leerer oder nur aus Leerraum bestehender DSN ist kein DSN. */
internal fun normalizedDsn(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }

/** Die echte Anbindung. Sentry startet NICHT von selbst (`io.sentry.auto-init=false` im Manifest), nur ueber [start]. */
class AndroidSentrySdk @Inject constructor(
    @ApplicationContext private val context: Context,
) : SentrySdk {

    override val configuredDsn: String? by lazy { normalizedDsn(readManifestDsn()) }

    override val isActive: Boolean get() = Sentry.isEnabled()

    // Liest DSN, Umgebung usw. aus den Manifest-Metadaten — auch bei auto-init=false.
    override fun start() = SentryAndroid.init(context)

    override fun stop() = Sentry.close()

    private fun readManifestDsn(): String? {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getApplicationInfo(context.packageName, PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        }
        return info.metaData?.getString("io.sentry.dsn")
    }
}

/** Was die App-Info ueber die Absturzberichte sagt. */
enum class CrashReportingStatus { NotIncluded, Off, On }

internal fun crashReportingStatus(available: Boolean, enabled: Boolean): CrashReportingStatus = when {
    !available -> CrashReportingStatus.NotIncluded
    enabled -> CrashReportingStatus.On
    else -> CrashReportingStatus.Off
}

/**
 * Steuert Sentry zur Laufzeit (QS B33): Vorher war es nur beim Bauen schaltbar (leerer DSN), die README sagte
 * "deaktivierbar", einen Schalter gab es nicht.
 *
 * Sentry startet nicht von selbst, sondern erst hier, nachdem der Wert des Nutzers gelesen wurde ([run]): Beim
 * Automatik-Start vor `Application.onCreate` koennte ein Absturz in den ersten Millisekunden gegen die Einstellung des
 * Nutzers gemeldet werden. Der Preis: Ein Absturz in den ersten Millisekunden nach dem Prozessstart, bevor die Einstellung
 * gelesen ist, wird nicht gemeldet. Ohne DSN im Build (der Fall der ausgelieferten Releases) tut der Controller nie etwas.
 */
class CrashReportingController @Inject constructor(
    private val sdk: SentrySdk,
    private val appPreferences: AppPreferences,
) {
    /** Ob diese Version mit einem DSN gebaut ist — nur dann gibt es etwas einzuschalten. */
    val isAvailable: Boolean get() = sdk.configuredDsn != null

    /** Folgt dem Schalter fuer die Lebensdauer des Aufrufers (`Obd2Application`, im `@ApplicationScope`). */
    suspend fun run() {
        appPreferences.crashReportsEnabled.collect { apply(it) }
    }

    internal fun apply(enabled: Boolean) {
        if (!isAvailable) return
        if (enabled && !sdk.isActive) sdk.start()
        else if (!enabled && sdk.isActive) sdk.stop()
    }
}
