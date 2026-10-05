package de.adv990de.obd2.docs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * BLE ist seit 1.24.9 ausdruecklich NICHT Teil der App (QS C / PRO-8: ~370 Zeilen toter Code — `scanBle()` hatte keinen
 * Aufrufer, die Geraeteliste bot nie BLE-Adapter an, die README warb trotzdem damit). Dieser Test haelt das fest, damit
 * nichts davon still zurueckkommt: weder Code noch die Berechtigungen, die nur der BLE-Scan brauchte, noch ein
 * README-Versprechen.
 */
class BluetoothScopeSourceTest {

    private val projectRoot: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: error("settings.gradle.kts nicht gefunden")

    private fun read(relative: String) = File(projectRoot, relative).readText()

    private val mainSources = File(projectRoot, "app/src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()

    @Test
    fun `no BLE code is left in the app`() {
        val offenders = mainSources.filter { f ->
            val text = f.readText()
            listOf("bluetoothLeScanner", "connectGatt", "BluetoothGattCallback", "android.bluetooth.le.", "BtType.BLE").any { it in text }
        }.map { it.name }
        assertTrue("BLE-Reste in: $offenders", offenders.isEmpty())
    }

    @Test
    fun `the manifest asks for no permission only the BLE scan needed`() {
        val manifest = read("app/src/main/AndroidManifest.xml")
        for (permission in listOf("BLUETOOTH_SCAN", "BLUETOOTH_ADMIN")) {
            assertFalse("$permission gehoerte nur zum BLE-Scan", manifest.contains("android.permission.$permission"))
        }
        assertTrue("Verbinden braucht BLUETOOTH_CONNECT", manifest.contains("android.permission.BLUETOOTH_CONNECT"))
    }

    @Test
    fun `the README does not promise BLE adapters`() {
        val readme = read("README.md")
        assertFalse("README bewirbt BLE-Adapter", readme.contains("**BLE** (ELM327-BLE-Adapter"))
        assertFalse(readme.contains("Bluetooth Classic + BLE"))
        assertFalse("kein Verweis mehr auf die geloeschte Datei", readme.contains("`BleConnection.kt` nutzt"))
    }
}
