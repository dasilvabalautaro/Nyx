package chat.neto.nyx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemePreferenceTest {

    @Test
    fun `system mode follows the system`() {
        assertTrue(ThemePreference.resolveDark(ThemeMode.SYSTEM, systemDark = true))
        assertFalse(ThemePreference.resolveDark(ThemeMode.SYSTEM, systemDark = false))
    }

    @Test
    fun `fixed modes ignore the system`() {
        assertTrue(ThemePreference.resolveDark(ThemeMode.DARK, systemDark = false))
        assertTrue(ThemePreference.resolveDark(ThemeMode.DARK, systemDark = true))
        assertFalse(ThemePreference.resolveDark(ThemeMode.LIGHT, systemDark = true))
        assertFalse(ThemePreference.resolveDark(ThemeMode.LIGHT, systemDark = false))
    }

    @Test
    fun `read falls back to SYSTEM on missing or corrupt value`() {
        assertEquals(ThemeMode.SYSTEM, ThemePreference.read(null))
        assertEquals(ThemeMode.SYSTEM, ThemePreference.read("not-a-mode"))
        assertEquals(ThemeMode.SYSTEM, ThemePreference.read(""))
    }

    @Test
    fun `read round-trips every mode name`() {
        ThemeMode.entries.forEach { mode ->
            assertEquals(mode, ThemePreference.read(mode.name))
        }
    }
}
