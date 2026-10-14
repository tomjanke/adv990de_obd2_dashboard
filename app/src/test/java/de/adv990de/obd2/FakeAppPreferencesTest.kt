package de.adv990de.obd2

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import de.adv990de.obd2.data.AppPreferences
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.lang.reflect.Method

/**
 * Haelt [fakeAppPreferences] an der echten Klasse fest: dieselben Standardwerte, und kein Lese-Flow, den die Attrappe vergisst. Ein
 * neuer Flow in `AppPreferences` macht diesen Test rot, bis die Attrappe ihn kennt — sonst scheiterte erst ein entfernter Test mit
 * „no answer found“, oder, schlimmer, ein Test liefe gegen einen Standardwert, den es in der Produktion nicht gibt.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeAppPreferencesTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private fun realPreferences(): AppPreferences {
        // UnconfinedTestDispatcher: s. AppPreferencesTest — der Speicher laeuft auf einem eigenen Test-Scope.
        val dataStore = PreferenceDataStoreFactory.create(
            scope = TestScope(UnconfinedTestDispatcher()),
            produceFile = { tempFolder.newFile("fake_parity.preferences_pb") },
        )
        return AppPreferences(dataStore, mockk(relaxed = true))
    }

    /** Alle öffentlichen Lese-Flows von [AppPreferences] (`val x: Flow<…>` ergibt `getX()` ohne Parameter). */
    private val flowGetters: List<Method> = AppPreferences::class.java.methods
        .filter { it.parameterCount == 0 && Flow::class.java.isAssignableFrom(it.returnType) }
        .sortedBy { it.name }

    @Test
    fun `the class has flows to compare at all`() {
        assertTrue("Reflexion findet die Flows nicht: ${flowGetters.map { it.name }}", flowGetters.size >= 18)
    }

    @Test
    fun `every flow of the real class has the same default in the fake`() = runTest {
        val real = realPreferences()
        val fake = fakeAppPreferences()

        for (getter in flowGetters) {
            val expected = (getter.invoke(real) as Flow<*>).first()
            val actual = (getter.invoke(fake) as Flow<*>).first()
            assertEquals("Standardwert von ${getter.name}", expected, actual)
        }
    }

    @Test
    fun `a named parameter changes only its own flow`() = runTest {
        val fake = fakeAppPreferences(useGpsSpeed = true, slotPids = listOf("010C"))

        assertEquals(true, fake.useGpsSpeed.first())
        assertEquals(listOf("010C"), fake.slotPids.first())
        assertEquals(false, fake.useImperial.first())
    }
}
