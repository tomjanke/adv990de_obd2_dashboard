package de.adv990de.obd2.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PackageManifestTest {

    private val manifest = PackageManifest(
        format = 1, appId = PackageFormat.APP_ID, appVersion = "1.44.20", createdMs = 1_700_000_000_000,
        parts = mapOf(DataPart.TRIPS to 61, DataPart.LOGBOOK to 104),
    )

    @Test
    fun roundTrip() {
        assertEquals(manifest, parseManifest(manifest.toJson()))
    }

    @Test
    fun theJsonUsesTheContractKeys() {
        val json = manifest.toJson()
        for (key in listOf("\"format\"", "\"app_id\"", "\"app_version\"", "\"created_ms\"", "\"parts\"", "\"TRIPS\"")) {
            assert(json.contains(key)) { "missing $key in $json" }
        }
    }

    @Test
    fun notJsonIsRejected() {
        assertNull(parseManifest("hello"))
        assertNull(parseManifest(""))
    }

    @Test
    fun aJsonArrayIsRejected() {
        assertNull(parseManifest("[1,2]"))
    }

    @Test
    fun aBadFormatIsRejected() {
        val base = """"app_id":"de.adv990de.obd2","app_version":"1","created_ms":1,"parts":{}"""
        assertNull(parseManifest("""{"format":"1",$base}"""))
        assertNull(parseManifest("""{"format":0,$base}"""))
        assertNull(parseManifest("""{"format":-1,$base}"""))
        assertNull(parseManifest("""{$base}"""))
        assertNull(parseManifest("""{"format":1.5,$base}"""))
    }

    @Test
    fun aWrongOrMissingAppIdIsRejected() {
        assertNull(parseManifest("""{"format":1,"app_id":"com.other","app_version":"1","created_ms":1,"parts":{}}"""))
        assertNull(parseManifest("""{"format":1,"app_version":"1","created_ms":1,"parts":{}}"""))
    }

    @Test
    fun aNewerFormatIsStillRead() {
        val parsed = parseManifest("""{"format":7,"app_id":"de.adv990de.obd2","app_version":"9","created_ms":5,"parts":{"TRIPS":2}}""")
        assertNotNull(parsed)
        assertEquals(7, parsed!!.format)
        assertEquals(mapOf(DataPart.TRIPS to 2), parsed.parts)
    }

    @Test
    fun unknownPartNamesAreSkipped() {
        val parsed = parseManifest("""{"format":1,"app_id":"de.adv990de.obd2","app_version":"9","created_ms":5,"parts":{"TRIPS":2,"FUTURE":3}}""")!!
        assertEquals(mapOf(DataPart.TRIPS to 2), parsed.parts)
    }
}
