package de.adv990de.obd2.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class ShareMimeTypeTest {
    @Test fun zip() = assertEquals("application/zip", shareMimeType("obd-dashboard_20261107T120000123Z.zip"))
    @Test fun csv() = assertEquals("text/csv", shareMimeType("applog_1.csv"))
    @Test fun upperCaseZip() = assertEquals("application/zip", shareMimeType("BACKUP.ZIP"))
    @Test fun emptyName() = assertEquals("text/csv", shareMimeType(""))
    @Test fun counterBeforeTheExtension() = assertEquals("application/zip", shareMimeType("obd-dashboard_x-1.zip"))
    @Test fun nameWithZipInTheMiddle() = assertEquals("text/csv", shareMimeType("my.zip.notes.csv"))
}
