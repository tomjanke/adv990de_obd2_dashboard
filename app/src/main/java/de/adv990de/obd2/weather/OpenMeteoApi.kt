package de.adv990de.obd2.weather

import de.adv990de.obd2.transfer.jsonDepthWithin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
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
 *
 * Gegenueber dem Dritten gilt (QS SEC-9): Die Anfrage nennt die App im `User-Agent` (Name und Version, sonst
 * nichts — [weatherUserAgent]) statt des JVM-Standards "Dalvik/…", der Open-Meteo bei Auffaelligkeiten keinen
 * Ansprechpartner liesse; und die Antwort wird hoechstens [MAX_RESPONSE_BYTES] weit gelesen, damit ein
 * fehlerhafter oder boesartiger Server die App weder Speicher noch Daten kosten kann (echte Antwort: rund 400 Byte).
 */
class OpenMeteoApi(
    private val userAgent: String = DEFAULT_USER_AGENT,
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
                    setRequestProperty("User-Agent", userAgent)
                    setRequestProperty("Accept", "application/json")
                }
                if (connection.responseCode != 200) return@withContext null
                // Schon die Ankuendigung genuegt: eine zu grosse Antwort wird gar nicht erst geoeffnet.
                if (connection.contentLengthLong > MAX_RESPONSE_BYTES) return@withContext null
                val bytes = connection.inputStream.use { readBounded(it, MAX_RESPONSE_BYTES) } ?: return@withContext null
                val body = String(bytes, Charsets.UTF_8)
                // Androids org.json parst rekursiv ohne Tiefenlimit: 16 KiB aus "[" waeren ein StackOverflowError (ein Error, den das catch unten nicht faengt).
                if (!jsonDepthWithin(body)) return@withContext null
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

/** Obergrenze fuer die Antwort in Bytes (nach dem Entpacken). Die echte Antwort ist rund 400 Byte gross. */
internal const val MAX_RESPONSE_BYTES = 16 * 1024

/** Der Name der App fuer Open-Meteo, wenn kein Versionsname bekannt ist. */
internal const val DEFAULT_USER_AGENT = "OBD2Monitor"

/** `User-Agent` der Wetterabfrage: App und Version — kein Geraet, kein Standort, keine Kennung. */
fun weatherUserAgent(versionName: String): String =
    if (versionName.isBlank()) DEFAULT_USER_AGENT else "$DEFAULT_USER_AGENT/${versionName.trim()}"

/**
 * Liest [input] vollstaendig, aber hoechstens [maxBytes] Bytes: `null`, sobald mehr kommt. Liest nie mehr als
 * `maxBytes + 1` Bytes — der eine zusaetzliche genuegt als Beweis, dass die Antwort zu gross ist.
 */
internal fun readBounded(input: InputStream, maxBytes: Int): ByteArray? {
    val out = ByteArrayOutputStream(minOf(maxBytes, 1024))
    val chunk = ByteArray(1024)
    var total = 0
    while (true) {
        val n = input.read(chunk, 0, minOf(chunk.size, maxBytes + 1 - total))
        if (n < 0) return out.toByteArray()
        total += n
        if (total > maxBytes) return null
        out.write(chunk, 0, n)
    }
}

/** Zwei Nachkommastellen (~1,1 km). `Locale.ROOT`, nicht die Geraete-Locale: auf einem deutschen
 *  Geraet ergaebe `String.format` sonst "52,52" — eine ungueltige Koordinate fuer die API. */
private fun roundCoordinate(value: Double): String = String.format(Locale.ROOT, "%.2f", value)
