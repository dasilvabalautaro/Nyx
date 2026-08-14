package chat.neto.krypta

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Decisión pura de re-bloqueo al volver la app al frente ([AppLock.shouldRelock]). */
class AppLockTest {

    @Test
    fun `disabled lock never relocks`() {
        assertFalse(AppLock.shouldRelock(enabled = false, hiddenAtMs = 1L, nowMs = 999_999L, graceMs = 0L))
    }

    @Test
    fun `no background transition yet does not relock`() {
        // hiddenAt = 0 → la app no llegó a pasar a 2.º plano (p. ej. primer onStart tras
        // el arranque en frío, donde init() ya decidió el estado inicial).
        assertFalse(AppLock.shouldRelock(enabled = true, hiddenAtMs = 0L, nowMs = 999_999L, graceMs = 0L))
    }

    @Test
    fun `immediate grace relocks on any return from background`() {
        assertTrue(AppLock.shouldRelock(enabled = true, hiddenAtMs = 1_000L, nowMs = 1_000L, graceMs = 0L))
        assertTrue(AppLock.shouldRelock(enabled = true, hiddenAtMs = 1_000L, nowMs = 1_001L, graceMs = 0L))
    }

    @Test
    fun `within grace period does not relock`() {
        assertFalse(
            AppLock.shouldRelock(
                enabled = true, hiddenAtMs = 1_000L, nowMs = 1_000L + AppLock.GRACE_1_MIN - 1,
                graceMs = AppLock.GRACE_1_MIN,
            )
        )
    }

    @Test
    fun `past grace period relocks`() {
        assertTrue(
            AppLock.shouldRelock(
                enabled = true, hiddenAtMs = 1_000L, nowMs = 1_000L + AppLock.GRACE_1_MIN,
                graceMs = AppLock.GRACE_1_MIN,
            )
        )
    }
}
