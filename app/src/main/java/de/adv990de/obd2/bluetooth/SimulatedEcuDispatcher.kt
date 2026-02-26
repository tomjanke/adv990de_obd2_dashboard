package de.adv990de.obd2.bluetooth


/**
 * Parses ELM327 commands and returns simulated responses.
 *
 * RPM follows a slow triangle wave (period 60 s, range 1500–2500 rpm)
 * simulating a calm city cruise.
 * Coolant temperature ramps from 20°C to 90°C over 90 s.
 * Speed, load, throttle, MAP and MAF are derived from the current RPM.
 * Fuel level, ambient temp and timing advance are constant.
 */
class SimulatedEcuDispatcher {

    private val connectTimeMs = System.currentTimeMillis()

    private val BITMAP_0100 = buildBitmap(0x00, setOf(0x04, 0x05, 0x0B, 0x0C, 0x0D, 0x0E, 0x10, 0x11, 0x20))
    private val BITMAP_0120 = buildBitmap(0x20, setOf(0x2F, 0x40))
    private val BITMAP_0140 = buildBitmap(0x40, setOf(0x46))

    fun respond(command: String): String {
        val cmd = command.trim().uppercase()
        return when {
            cmd == "ATZ"          -> "ELM327 v1.5"
            cmd.startsWith("AT") -> "OK"
            else                  -> respondPid(cmd)
        }
    }

    private fun respondPid(cmd: String): String = when (cmd) {
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
        "0146" -> "41 46 3C"   // ambient temp 20°C (constant)
        "010E" -> "41 0E 94"   // timing advance 10° (constant)
        else   -> "NO DATA"
    }

    // ── Animated helpers ─────────────────────────────────────────────────────

    private fun rpm(): Int {
        val t = System.currentTimeMillis()
        // Slow triangle wave: 1500–2500 rpm over 60 s — calm city cruise
        val phase = (t % 60_000L) / 60_000.0          // 0.0 .. 1.0
        val triangle = if (phase < 0.5) 2 * phase else 2 * (1 - phase)  // 0.0 .. 1.0
        return (1500 + 1000 * triangle).toInt()
    }

    private fun respondRpm(): String {
        val raw = rpm() * 4
        return "41 0C %02X %02X".format(raw / 256, raw % 256)
    }

    private fun respondSpeed(): String {
        val speed = ((rpm() - 800) * 120 / 3700.0).toInt().coerceIn(0, 120)
        return "41 0D %02X".format(speed)
    }

    private fun respondCoolant(): String {
        val elapsed = ((System.currentTimeMillis() - connectTimeMs) / 1000.0).coerceAtMost(90.0)
        val temp = (20 + 70 * elapsed / 90.0).toInt()
        return "41 05 %02X".format(temp + 40)
    }

    private fun respondLoad(): String {
        val load = (20 + 75.0 * (rpm() - 800) / 3700).toInt().coerceIn(20, 95)
        return "41 04 %02X".format(load * 255 / 100)
    }

    private fun respondThrottle(): String {
        val throttle = (100.0 * (rpm() - 800) / 3700).toInt().coerceIn(0, 100)
        return "41 11 %02X".format(throttle * 255 / 100)
    }

    private fun respondMap(): String {
        val map = (30 + 70.0 * (rpm() - 800) / 3700).toInt().coerceIn(30, 100)
        return "41 0B %02X".format(map)
    }

    private fun respondMaf(): String {
        val mafCentigs = (50 + 4950.0 * (rpm() - 800) / 3700).toInt().coerceIn(50, 4999)
        return "41 10 %02X %02X".format(mafCentigs / 256, mafCentigs % 256)
    }

    // ── Bitmap builder ───────────────────────────────────────────────────────

    private fun buildBitmap(rangeStart: Int, pids: Set<Int>): String {
        var bits = 0
        for (pid in pids) {
            val offset = pid - rangeStart  // 1..32
            if (offset in 1..32) bits = bits or (1 shl (32 - offset))
        }
        return "%02X %02X %02X %02X".format(
            (bits ushr 24) and 0xFF,
            (bits ushr 16) and 0xFF,
            (bits ushr 8)  and 0xFF,
             bits           and 0xFF
        )
    }
}
