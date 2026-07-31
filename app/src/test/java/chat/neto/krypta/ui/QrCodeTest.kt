package chat.neto.krypta.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrCodeTest {

    private val peerId = "12D3KooWLf8epxajtBmE88KPyweQEwK4SPSPHT1ozbxiwGM9nV3d"

    @Test
    fun `payload round-trips the peerId`() {
        assertEquals(peerId, QrCode.parseVerifyPayload(QrCode.verifyPayload(peerId)))
    }

    @Test
    fun `parse tolerates surrounding whitespace`() {
        assertEquals(peerId, QrCode.parseVerifyPayload("  ${QrCode.verifyPayload(peerId)}\n"))
    }

    @Test
    fun `parse rejects non-krypta QR content`() {
        assertNull(QrCode.parseVerifyPayload("https://example.com"))
        assertNull(QrCode.parseVerifyPayload(peerId)) // sin el esquema krypta:verify:
        assertNull(QrCode.parseVerifyPayload("krypta:verify:")) // vacío
    }
}
