package chat.neto.krypta

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import chat.neto.krypta.nativebridge.Libp2pNode
import chat.neto.krypta.p2p.AesGcmMessageCipher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Live, on-device rendezvous discovery against our own DHT node (Phase 1 seed running on
 * the dev machine, reached over `adb reverse`). Skipped unless run with instrumentation
 * args, so it never breaks the normal suite. Example:
 *
 *   adb reverse tcp:4101 tcp:4101
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=chat.neto.krypta.KryptaDiscoveryDeviceTest \
 *     -Pandroid.testInstrumentationRunnerArguments.bootstrap="/ip4/127.0.0.1/tcp/4101/p2p/<PeerID>" \
 *     -Pandroid.testInstrumentationRunnerArguments.rendezvous="<hex>"
 */
@RunWith(AndroidJUnit4::class)
class KryptaDiscoveryDeviceTest {

    private val args = InstrumentationRegistry.getArguments()

    @Test
    fun discoversPeerUnderRendezvousViaDht() = runBlocking {
        val bootstrap = args.getString("bootstrap")
        val rendezvousHex = args.getString("rendezvous")
        assumeTrue(
            "pass -P...bootstrap and -P...rendezvous to run the live discovery test",
            bootstrap != null && rendezvousHex != null,
        )
        val rdv = rendezvousHex!!.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        val node = Libp2pNode(InstrumentationRegistry.getInstrumentation().targetContext)
        node.start()
        try {
            node.startDht(bootstrap!!, server = false)
            node.advertise(rdv)

            var found = emptyList<String>()
            var attempts = 0
            while (found.isEmpty() && attempts < 10) {
                found = node.findPeers(rdv, 5)
                attempts++
            }
            assertTrue("no peers discovered under rendezvous via DHT", found.isNotEmpty())
        } finally {
            node.stop()
        }
    }

    /**
     * Live E2EE: the phone encrypts a message with AES-256-GCM (key derived from a shared
     * secret) and delivers the CIPHERTEXT to the bootstrap node over a libp2p stream. The
     * node has no key, so its log shows only opaque bytes — demonstrating confidentiality
     * in transit. The encrypt/decrypt round-trip itself is covered by AesGcmMessageCipherTest.
     */
    @Test
    fun sendsEncryptedMessageToBootstrapPeerOverStream() = runBlocking {
        val bootstrap = args.getString("bootstrap")
        assumeTrue("pass -P...bootstrap to run the live send test", bootstrap != null)
        val peerId = bootstrap!!.substringAfterLast("/p2p/")

        val sharedSecret = ByteArray(32) { it.toByte() } // fija para el demo
        val ciphertext = AesGcmMessageCipher()
            .encrypt(sharedSecret, "hola E2EE desde el movil krypta".toByteArray())

        val node = Libp2pNode(InstrumentationRegistry.getInstrumentation().targetContext)
        node.start()
        try {
            node.startDht(bootstrap, server = false) // connects to the bootstrap peer
            node.sendMessage(peerId, ciphertext)
        } finally {
            node.stop()
        }
    }
}
