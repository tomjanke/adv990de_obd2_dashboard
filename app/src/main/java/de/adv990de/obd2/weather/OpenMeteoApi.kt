package de.adv990de.obd2.weather

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Open-Meteo (https://open-meteo.com): kein API-Key, keine Anmeldung noetig fuer nicht-
 * kommerzielle Nutzung (verifiziert https://open-meteo.com/en/pricing, 2026-09-25) — 10.000
 * Aufrufe/Tag, 600/Minute. Pflicht laut Lizenz (CC BY 4.0): Namensnennung irgendwo in der App
 * (siehe AppInfoScreen.kt).
 *
 * Bewusst kein neues Netzwerk-Dependency (kein Retrofit/Ktor/OkHttp) — ein GET mit einem
 * Float-Feld braucht keine eigene Library; `HttpURLConnection` + `org.json` sind Android-
 * Bordmittel, das Projekt hatte bisher ueberhaupt keinen Netzwerk-Code.
 *
 * [openConnection] ist der Test-Seam: production nutzt den Default (echtes `URL.openConnection`),
 * Tests injizieren eine Fake-Connection ohne echten Socket.
 *
 * Datenschutz: Die Koordinaten verlassen das Geraet nur auf zwei Nachkommastellen gerundet (rund
 * 1,1 km, s. [roundCoordinate]) — fuer Wetter mehr als genau genug, Open-Meteo rechnet laut eigener
 * Doku ohnehin auf einem Raster vergleichbarer Groesse. So steht in keinem Anfrage-Log eines Dritten
 * der genaue Standort.
 */
@Singleton
class OpenMeteoApi @Inject constructor(
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) : WeatherApi {

    override suspend fun currentTemperatureC(latitude: Double, longitude: Double): Float? =
        withContext(Dispatchers.IO) {
            try {
                val url = URL(
                    "https://api.open-meteo.com/v1/forecast" +
                        "?latitude=${roundCoordinate(latitude)}&longitude=${roundCoordinate(longitude)}" +
                        "&current=temperature_2m"
                )
                val connection = openConnection(url)
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                if (connection.responseCode != 200) return@withContext null
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val current = JSONObject(body).optJSONObject("current") ?: return@withContext null
                if (!current.has("temperature_2m")) return@withContext null
                current.getDouble("temperature_2m").toFloat()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
}

/** Zwei Nachkommastellen (~1,1 km). `Locale.ROOT`, nicht die Geraete-Locale: auf einem deutschen
 *  Geraet ergaebe `String.format` sonst "52,52" — eine ungueltige Koordinate fuer die API. */
private fun roundCoordinate(value: Double): String = String.format(Locale.ROOT, "%.2f", value)
