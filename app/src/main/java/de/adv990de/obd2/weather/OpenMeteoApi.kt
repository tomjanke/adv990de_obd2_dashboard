package de.adv990de.obd2.weather

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Open-Meteo (https://open-meteo.com): kein API-Key, keine Anmeldung noetig fuer nicht-
 * kommerzielle Nutzung (verifiziert https://open-meteo.com/en/pricing, 2026-09-25) — 10.000
 * Aufrufe/Tag, 600/Minute. Diese App ist ein privates, nicht-kommerzielles Projekt (vom Nutzer
 * bestaetigt 2026-09-27). Pflicht laut Lizenz CC BY 4.0 (https://open-meteo.com/en/licence):
 * Namensnennung MIT Link auf die Quelle UND ein zweiter, separater Link auf den Lizenztext selbst
 * (reine Namensnennung des Lizenznamens genuegt laut dessen eigenem Text nicht) — beide als eigene,
 * antippbare Zeilen in `AppInfoScreen.kt` (`WeatherAttributionRow` bzw. `WeatherLicenseRow`).
 *
 * Bewusst kein neues Netzwerk-Dependency (kein Retrofit/Ktor/OkHttp) — ein GET mit einem
 * Float-Feld braucht keine eigene Library; `HttpURLConnection` + `org.json` sind Android-
 * Bordmittel, das Projekt hatte bisher ueberhaupt keinen Netzwerk-Code.
 *
 * [openConnection] ist der Test-Seam: production nutzt den Default (echtes `URL.openConnection`),
 * Tests injizieren eine Fake-Connection ohne echten Socket. Bewusst ohne `@Inject`/`@Singleton`:
 * Dagger ignoriert Kotlin-Default-Parameter und haette fuer `openConnection` ein Binding des
 * Funktionstyps verlangt (so stand es im frueher generierten `OpenMeteoApi_Factory`, das nie
 * benutzt wurde). `WeatherModule` baut die Instanz deshalb per `@Provides` selbst; der
 * Singleton-Scope sitzt dort.
 *
 * Datenschutz: Die Koordinaten verlassen das Geraet nur auf zwei Nachkommastellen gerundet (rund
 * 1,1 km, s. [roundCoordinate]) — fuer Wetter mehr als genau genug, Open-Meteo rechnet laut eigener
 * Doku ohnehin auf einem Raster vergleichbarer Groesse. So steht in keinem Anfrage-Log eines Dritten
 * der genaue Standort.
 */
class OpenMeteoApi(
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) : WeatherApi {

    override suspend fun currentTemperatureC(latitude: Double, longitude: Double): Float? =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val url = URL(
                    "https://api.open-meteo.com/v1/forecast" +
                        "?latitude=${roundCoordinate(latitude)}&longitude=${roundCoordinate(longitude)}" +
                        "&current=temperature_2m"
                )
                connection = openConnection(url).apply {
                    connectTimeout = 10_000
                    readTimeout = 10_000
                }
                if (connection.responseCode != 200) return@withContext null
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val current = JSONObject(body).optJSONObject("current") ?: return@withContext null
                if (!current.has("temperature_2m")) return@withContext null
                // NaN/Unendlich (z. B. "NaN" als String, oder ein Wert jenseits des Float-Bereichs)
                // ist keine Temperatur, sondern eine unbrauchbare Antwort: null, wie jeder andere
                // Fehler — und damit ein Fehlschlag fuer die 30-Minuten-Haltelogik, kein neuer Wert.
                current.getDouble("temperature_2m").toFloat().takeIf { it.isFinite() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } finally {
                // Auf jedem Pfad sofort freigeben — auch bei Nicht-200-Antworten, deren Error-Stream
                // nie gelesen wird und deren Socket sonst bis zum Timeout offen bliebe. Kostet pro
                // Abruf einen neuen Verbindungsaufbau statt Keep-Alive-Wiederverwendung; bei einem
                // Abruf pro Minute vernachlaessigbar.
                connection?.disconnect()
            }
        }
}

/** Zwei Nachkommastellen (~1,1 km). `Locale.ROOT`, nicht die Geraete-Locale: auf einem deutschen
 *  Geraet ergaebe `String.format` sonst "52,52" — eine ungueltige Koordinate fuer die API. */
private fun roundCoordinate(value: Double): String = String.format(Locale.ROOT, "%.2f", value)
