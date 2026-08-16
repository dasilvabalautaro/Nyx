package chat.neto.nyx.p2p

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageEnvelopeTest {

    @Test
    fun `text round-trips id and body (including newlines in body)`() {
        val body = "hola\ncon salto".toByteArray()
        val decoded = MessageEnvelope.decode(MessageEnvelope.encodeText("id-123", body))
        assertTrue(decoded is MessageEnvelope.Decoded.Text)
        decoded as MessageEnvelope.Decoded.Text
        assertEquals("id-123", decoded.id)
        assertArrayEquals(body, decoded.body)
    }

    @Test
    fun `text supports arbitrary bytes in body`() {
        val body = ByteArray(256) { it.toByte() }
        val decoded = MessageEnvelope.decode(MessageEnvelope.encodeText("x", body))
        assertArrayEquals(body, (decoded as MessageEnvelope.Decoded.Text).body)
    }

    @Test
    fun `image round-trips id and jpeg bytes`() {
        val jpeg = ByteArray(500) { (it % 256).toByte() }
        val decoded = MessageEnvelope.decode(MessageEnvelope.encodeImage("img-1", jpeg))
        assertTrue(decoded is MessageEnvelope.Decoded.Image)
        decoded as MessageEnvelope.Decoded.Image
        assertEquals("img-1", decoded.id)
        assertArrayEquals(jpeg, decoded.bytes)
    }

    @Test
    fun `file meta, chunk and descriptor round-trip`() {
        val meta = MessageEnvelope.decode(
            MessageEnvelope.encodeFileMeta("fid", "mi archivo.pdf", "application/pdf", 12345L, 3),
        )
        assertTrue(meta is MessageEnvelope.Decoded.FileMeta)
        meta as MessageEnvelope.Decoded.FileMeta
        assertEquals("fid", meta.fileId)
        assertEquals("mi archivo.pdf", meta.name)
        assertEquals("application/pdf", meta.mime)
        assertEquals(12345L, meta.size)
        assertEquals(3, meta.totalChunks)

        val chunkBytes = ByteArray(200) { (it % 256).toByte() }
        val chunk = MessageEnvelope.decode(MessageEnvelope.encodeFileChunk("fid", 2, chunkBytes))
        chunk as MessageEnvelope.Decoded.FileChunk
        assertEquals("fid", chunk.fileId)
        assertEquals(2, chunk.index)
        assertArrayEquals(chunkBytes, chunk.bytes)

        val desc = MessageEnvelope.decode(
            MessageEnvelope.encodeFileDescriptor("doc.txt", "text/plain", 99L, "/data/doc.txt"),
        )
        desc as MessageEnvelope.Decoded.FileDescriptor
        assertEquals("doc.txt", desc.name)
        assertEquals("/data/doc.txt", desc.path)
        assertEquals(99L, desc.size)
    }

    @Test
    fun `read round-trips the id list`() {
        val decoded = MessageEnvelope.decode(MessageEnvelope.encodeRead(listOf("a", "b", "c")))
        assertEquals(listOf("a", "b", "c"), (decoded as MessageEnvelope.Decoded.Read).ids)
    }

    @Test
    fun `call signal round-trips kind, callId and ts`() {
        val decoded = MessageEnvelope.decode(MessageEnvelope.encodeCall("invite", "call-42", 1_720_000_000_000))
        val call = decoded as MessageEnvelope.Decoded.Call
        assertEquals("invite", call.kind)
        assertEquals("call-42", call.callId)
        assertEquals(1_720_000_000_000, call.ts)
        // Malformados → null (no revientan el decode).
        assertNull(MessageEnvelope.decode("C\ninvite\nsolo-dos-campos".toByteArray()))
        assertNull(MessageEnvelope.decode("C\n\nid\n123".toByteArray()))
    }

    @Test
    fun `like round-trips its timestamp and nothing else`() {
        val decoded = MessageEnvelope.decode(MessageEnvelope.encodeLike(1_720_000_000_000))
        assertEquals(1_720_000_000_000, (decoded as MessageEnvelope.Decoded.Like).ts)
        // El sobre `L` es el único que se acepta de peers desconocidos, así que su superficie
        // se mantiene mínima a propósito: nada más que el ts, y lo malformado cae a null.
        assertNull(MessageEnvelope.decode("L\n".toByteArray()))
        assertNull(MessageEnvelope.decode("L\nno-es-un-numero".toByteArray()))
    }

    @Test
    fun `decode returns null for non-enveloped (legacy) bytes`() {
        assertNull(MessageEnvelope.decode("mensaje viejo sin sobre".toByteArray()))
        assertNull(MessageEnvelope.decode(ByteArray(0)))
        assertNull(MessageEnvelope.decode(byteArrayOf('T'.code.toByte()))) // sin salto
    }
}
