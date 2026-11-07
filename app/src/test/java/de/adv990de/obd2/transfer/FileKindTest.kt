package de.adv990de.obd2.transfer

import org.junit.Assert.assertEquals
import org.junit.Test

class FileKindTest {

    private fun kind(text: String) = detectFileKind(text.toByteArray(Charsets.UTF_8))
    private val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    @Test fun zipHead() = assertEquals(FileKind.PACKAGE, detectFileKind(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0, 0)))

    @Test fun emptyZipIsUnknown() = assertEquals(FileKind.UNKNOWN, detectFileKind(byteArrayOf(0x50, 0x4B, 0x05, 0x06, 0, 0)))

    @Test fun pkWithoutTheRightMagicIsUnknown() = assertEquals(FileKind.UNKNOWN, kind("PK hello"))

    @Test fun trips() = assertEquals(FileKind.TRIPS_CSV, kind("timestamp,pid,value,name,source,session\n1,010C,1,x,ECU,1\n"))

    @Test fun oldTripsWithoutSource() = assertEquals(FileKind.TRIPS_CSV, kind("timestamp,pid,value\n1,010C,1\n"))

    @Test fun tripsWithBomAndCrLf() =
        assertEquals(FileKind.TRIPS_CSV, detectFileKind(bom + "timestamp,pid,value,name\r\n1,2,3,4\r\n".toByteArray()))

    @Test fun tripsWithQuotes() = assertEquals(FileKind.TRIPS_CSV, kind("\"timestamp\",\"pid\",\"value\"\n"))

    @Test fun logbook() = assertEquals(FileKind.LOGBOOK_CSV, kind("date,type,odometer_km,amount_ml,full_tank,cost_eur,note,brand\n"))

    @Test fun logbookWithSevenColumns() = assertEquals(FileKind.LOGBOOK_CSV, kind("date,type,odometer_km,amount_ml,full_tank,cost_eur,note\n"))

    @Test fun logbookWithBom() = assertEquals(FileKind.LOGBOOK_CSV, detectFileKind(bom + "date,type,odometer_km\n".toByteArray()))

    @Test fun appLog() = assertEquals(FileKind.APP_LOG_CSV, kind("timestamp,level,message\n1,INFO,x\n"))

    @Test fun appLogWithCr() = assertEquals(FileKind.APP_LOG_CSV, kind("timestamp,level,message\r1,INFO,x"))

    @Test fun spacesAroundColumnsAreTrimmed() = assertEquals(FileKind.APP_LOG_CSV, kind("timestamp , level , message\n"))

    @Test fun missingOneRequiredColumnIsUnknown() {
        assertEquals(FileKind.UNKNOWN, kind("timestamp,pid\n"))
        assertEquals(FileKind.UNKNOWN, kind("date,type\n"))
        assertEquals(FileKind.UNKNOWN, kind("timestamp,level\n"))
    }

    @Test fun binaryWithNulIsUnknown() = assertEquals(FileKind.UNKNOWN, detectFileKind(byteArrayOf(1, 2, 0, 3, 4)))

    @Test fun nulAfterAValidHeaderStillMakesItUnknown() =
        assertEquals(FileKind.UNKNOWN, detectFileKind("timestamp,pid,value\n".toByteArray() + byteArrayOf(0)))

    @Test fun emptyIsUnknown() {
        assertEquals(FileKind.UNKNOWN, detectFileKind(ByteArray(0)))
        assertEquals(FileKind.UNKNOWN, kind("\n"))
    }

    @Test fun lengthLimitsWhatIsLooked() {
        val bytes = "timestamp,pid,value\n".toByteArray()
        assertEquals(FileKind.UNKNOWN, detectFileKind(bytes, 5))
    }

    @Test fun otherTextIsUnknown() = assertEquals(FileKind.UNKNOWN, kind("a,b,c\n1,2,3\n"))
}
