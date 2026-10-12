package de.adv990de.obd2.docs

import de.adv990de.obd2.projectRoot
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
    fun `the manifest declares Bluetooth hardware as required - the app is useless and crashes at start without it`() {
        // Ohne Bluetooth liefert getSystemService(BLUETOOTH_SERVICE) null; AppModule.provideBluetoothAdapter wirft dann beim
        // Aufbau des Hilt-Graphen (DAT-17). required="true" haelt solche Geraete aus Stores und Installern heraus.
        val manifest = read("app/src/main/AndroidManifest.xml")
        val declaration = Regex("""<uses-feature\s[^>]*android:name="android\.hardware\.bluetooth"[^>]*/>""").find(manifest)
        assertTrue("uses-feature android.hardware.bluetooth fehlt", declaration != null)
        assertTrue("muss required=true sein", declaration!!.value.contains("""android:required="true""""))
    }

    @Test
    fun `location hardware is optional - the GPS speed is an opt-in, so a device without it must still install`() {
        // Die Standort-Berechtigungen deklarieren die Hardware sonst implizit als Pflicht (aapt2: uses-implied-feature).
        val manifest = read("app/src/main/AndroidManifest.xml")
        for (feature in listOf("android.hardware.location", "android.hardware.location.gps")) {
            val declaration = Regex("""<uses-feature\s[^>]*android:name="${Regex.escape(feature)}"[^>]*/>""").find(manifest)
            assertTrue("uses-feature $feature fehlt", declaration != null)
            assertTrue("$feature muss required=false sein", declaration!!.value.contains("""android:required="false""""))
        }
    }

    @Test
    fun `the README does not promise BLE adapters`() {
        val readme = read("README.md")
        assertFalse("README bewirbt BLE-Adapter", readme.contains("**BLE** (ELM327-BLE-Adapter"))
        assertFalse(readme.contains("Bluetooth Classic + BLE"))
        assertFalse("kein Verweis mehr auf die geloeschte Datei", readme.contains("`BleConnection.kt` nutzt"))
    }
}
