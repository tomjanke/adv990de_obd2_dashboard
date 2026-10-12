package de.adv990de.obd2.bluetooth


/**
 * Parses ELM327 commands and returns simulated responses.
 *
 * RPM follows a triangle wave (period 20 s, range 900–8500 rpm)
 * simulating varied city/highway driving.
 * Coolant temperature ramps from 20°C to 90°C over 90 s.
 * Speed, load, throttle, MAP and MAF are derived from the current RPM.
 * Fuel level (65 %), board voltage (14.2 V), ambient temp (20 °C) and timing advance (10°)
 * are constant.
 *
 * Hinweis fuer Tests: die Kuehlmittelrampe endet bei 90 °C, die Warn- und Kritisch-Schwellen
 * der Kachel liegen bei 110 bzw. 120 °C. Ueber den Simulator laesst sich der Statuswechsel
 * daher nur bei der Drehzahl ausloesen.
 *
 * [clockMs] ist die Uhr der Simulation (Standard: Wanduhr). Als Parameter, damit Tests die Kurven an festen Zeitpunkten pruefen statt
 * mit `Thread.sleep` auf "irgendeine" Aenderung zu warten (TST-13); die Drehzahl haengt an der absoluten Zeit, die Kuehlmittelrampe an
 * der Zeit seit der Konstruktion (= Verbindungsaufbau).
 */
class SimulatedEcuDispatcher(private val clockMs: () -> Long = System::currentTimeMillis) {

    private val connectTimeMs = clockMs()

    private val BITMAP_0100 = buildBitmap(0x00, setOf(0x04, 0x05, 0x0B, 0x0C, 0x0D, 0x0E, 0x10, 0x11, 0x20))
    private val BITMAP_0120 = buildBitmap(0x20, setOf(0x2F, 0x40))
    private val BITMAP_0140 = buildBitmap(0x40, setOf(0x42, 0x46))

    fun respond(command: String): String {
        val cmd = command.trim().uppercase()
        return when {
            cmd == "ATZ"          -> "ELM327 v1.5"
            cmd.startsWith("AT") -> "OK"
            else                  -> respondPid(cmd)
        }
    }

    private fun respondPid(cmd: String): String = when (cmd) {
        // Ein gesundes Steuergeraet antwortet auf die Fehlerspeicher-Abfragen positiv ohne Codes (nicht mit NO DATA,
        // das seit A9 als "keine Antwort" gilt und keinen Haken "Keine Fehlercodes" mehr ergibt).
        "03"   -> "43 00"
        "07"   -> "47 00"
        "0100" -> "41 00 $BITMAP_0100"
        "0120" -> "41 20 $BITMAP_0120"
        "0140" -> "41 40 $BITMAP_0140"
        "010C" -> respondRpm()
        "010D" -> respondSpeed()
        "0105" -> respondCoolant()
        "0104" -> respondLoad()
        "0111" -> respondThrottle()
        "010B" -> respondMap()
        "0110" -> respondMaf()
        "012F" -> "41 2F A6"   // fuel level 65 % (constant): 0xA6=166, 166*100/255=65
        "0142" -> "41 42 37 78" // board voltage 14.2 V (constant): 0x3778=14200, /1000
        "0146" -> "41 46 3C"   // ambient temp 20°C (constant)
        "010E" -> "41 0E 94"   // timing advance 10° (constant)
        else   -> "NO DATA"
    }

    // ── Animated helpers ─────────────────────────────────────────────────────

    private fun rpm(): Int {
        val t = clockMs()
        // Triangle wave: 900–8500 rpm over 20 s — varied city/highway driving
        val phase = (t % 20_000L) / 20_000.0          // 0.0 .. 1.0
        val triangle = if (phase < 0.5) 2 * phase else 2 * (1 - phase)  // 0.0 .. 1.0
        return (900 + 7600 * triangle).toInt()
    }

    private fun respondRpm(): String {
        val raw = rpm() * 4
        return "41 0C %02X %02X".format(raw / 256, raw % 256)
    }

    private fun respondSpeed(): String {
        val speed = ((rpm() - 900) * 120 / 7600.0).toInt().coerceIn(0, 120)
        return "41 0D %02X".format(speed)
    }

    private fun respondCoolant(): String {
        val elapsed = ((clockMs() - connectTimeMs) / 1000.0).coerceAtMost(90.0)
        val temp = (20 + 70 * elapsed / 90.0).toInt()
        return "41 05 %02X".format(temp + 40)
    }

    private fun respondLoad(): String {
        val load = (20 + 75.0 * (rpm() - 900) / 7600).toInt().coerceIn(20, 95)
        return "41 04 %02X".format(load * 255 / 100)
    }

    private fun respondThrottle(): String {
        val throttle = (100.0 * (rpm() - 900) / 7600).toInt().coerceIn(0, 100)
        return "41 11 %02X".format(throttle * 255 / 100)
    }

    private fun respondMap(): String {
        val map = (30 + 70.0 * (rpm() - 900) / 7600).toInt().coerceIn(30, 100)
        return "41 0B %02X".format(map)
    }

    private fun respondMaf(): String {
        val mafCentigs = (50 + 4950.0 * (rpm() - 900) / 7600).toInt().coerceIn(50, 4999)
        return "41 10 %02X %02X".format(mafCentigs / 256, mafCentigs % 256)
    }

    // ── Bitmap builder ───────────────────────────────────────────────────────

    private fun buildBitmap(rangeStart: Int, pids: Set<Int>): String {
        var bits = 0
        for (pid in pids) {
            val offset = pid - rangeStart  // 1..32
            // Frueher wurde ein PID ausserhalb des Bereichs stillschweigend verschluckt: der
            // Eintrag stand im Code, im Bitmap fehlte er, und der PID war nie waehlbar.
            require(offset in 1..32) {
                "PID 0x%02X gehoert nicht in den Bereich 0x%02X..0x%02X".format(
                    pid, rangeStart + 1, rangeStart + 32
                )
            }
            bits = bits or (1 shl (32 - offset))
        }
        return "%02X %02X %02X %02X".format(
            (bits ushr 24) and 0xFF,
            (bits ushr 16) and 0xFF,
            (bits ushr 8)  and 0xFF,
             bits           and 0xFF
        )
    }
}
