package dev.hyphen.android.pairing

import dev.hyphen.android.transport.SelfSignedCertificateMinter
import dev.hyphen.android.transport.TlsClient
import dev.hyphen.android.transport.TlsIdentity
import dev.hyphen.android.transport.TlsServer
import dev.hyphen.android.transport.softwareKeyPair
import dev.hyphen.android.trust.AesGcmTrustCipher
import dev.hyphen.android.trust.EncryptedFilePeerTrustStore
import dev.hyphen.android.trust.softwareAesKey
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PairingWireProtocolTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val nonce = ByteArray(16) { 0x33 }

    @Test
    fun `bilateral confirm commits trust on both sides`() {
        val serverIdentity = identity("Mac")
        val clientIdentity = identity("Phone")
        val macFp = serverIdentity.spkiFingerprint
        val androidFp = clientIdentity.spkiFingerprint
        val serverOutcome = LinkedBlockingQueue<PairingCommit.Outcome>()
        val server = TlsServer(serverIdentity, { it.contentEquals(androidFp) })
        val port = server.start { socket ->
            val wire = PairingWireProtocol.runResponder(
                socket = socket,
                nonce = nonce,
                macSpkiFingerprint = macFp,
                expectedAndroidFingerprint = androidFp,
            )
            val gate = makeGate(peerFp = androidFp, macFp = macFp, androidFp = androidFp, peerName = "Phone")
            serverOutcome.put(PairingCommit.finalize(gate.gate, wire.confirm, localAccepted = true))
        }

        val clientSocket = TlsClient.connect("127.0.0.1", port, clientIdentity, { it.contentEquals(macFp) })
        val wire = PairingWireProtocol.runInitiator(
            socket = clientSocket,
            nonce = nonce,
            macSpkiFingerprint = macFp,
            androidSpkiFingerprint = androidFp,
            device = PairingWireProtocol.WireDeviceInfo("android", "0.0.1", "Phone"),
        )
        val clientGate = makeGate(peerFp = macFp, macFp = macFp, androidFp = androidFp, peerName = "Mac")
        val clientOutcome = PairingCommit.finalize(clientGate.gate, wire.confirm, localAccepted = true)

        assertEquals(PairingCommit.Outcome.TRUSTED, clientOutcome)
        assertEquals(PairingCommit.Outcome.TRUSTED, serverOutcome.poll(5, TimeUnit.SECONDS))
        assertTrue(clientGate.store.allPeers().isNotEmpty())
        clientSocket.close()
        server.stop()
    }

    @Test
    fun `remote reject persists nothing on accepting side`() {
        val serverIdentity = identity("Mac")
        val clientIdentity = identity("Phone")
        val macFp = serverIdentity.spkiFingerprint
        val androidFp = clientIdentity.spkiFingerprint
        val serverOutcome = LinkedBlockingQueue<PairingCommit.Outcome>()
        val serverStores = LinkedBlockingQueue<EncryptedFilePeerTrustStore>()
        val server = TlsServer(serverIdentity, { it.contentEquals(androidFp) })
        val port = server.start { socket ->
            val wire = PairingWireProtocol.runResponder(
                socket = socket,
                nonce = nonce,
                macSpkiFingerprint = macFp,
                expectedAndroidFingerprint = androidFp,
            )
            val gate = makeGate(peerFp = androidFp, macFp = macFp, androidFp = androidFp, peerName = "Phone")
            serverStores.put(gate.store)
            serverOutcome.put(PairingCommit.finalize(gate.gate, wire.confirm, localAccepted = true))
        }

        val clientSocket = TlsClient.connect("127.0.0.1", port, clientIdentity, { it.contentEquals(macFp) })
        val wire = PairingWireProtocol.runInitiator(
            socket = clientSocket,
            nonce = nonce,
            macSpkiFingerprint = macFp,
            androidSpkiFingerprint = androidFp,
            device = PairingWireProtocol.WireDeviceInfo("android", "0.0.1", "Phone"),
        )
        val clientGate = makeGate(peerFp = macFp, macFp = macFp, androidFp = androidFp, peerName = "Mac")
        val clientOutcome = PairingCommit.finalize(clientGate.gate, wire.confirm, localAccepted = false)

        assertEquals(PairingCommit.Outcome.REJECTED, clientOutcome)
        assertEquals(PairingCommit.Outcome.REJECTED, serverOutcome.poll(5, TimeUnit.SECONDS))
        assertTrue(clientGate.store.allPeers().isEmpty())
        assertTrue(serverStores.poll(5, TimeUnit.SECONDS)!!.allPeers().isEmpty())
        clientSocket.close()
        server.stop()
    }

    @Test
    fun `local reject persists nothing`() {
        val macFp = ByteArray(32) { 0x11 }
        val androidFp = ByteArray(32) { 0x22 }
        val gate = makeGate(peerFp = macFp, macFp = macFp, androidFp = androidFp, peerName = "Mac")
        val confirm = PairingWireProtocol.PairingConfirmExchange(
            ByteArrayInputStream(byteArrayOf()),
            ByteArrayOutputStream(),
            4,
        )
        assertEquals(PairingCommit.Outcome.REJECTED, PairingCommit.finalize(gate.gate, confirm, localAccepted = false))
        assertTrue(gate.store.allPeers().isEmpty())
    }

    @Test
    fun `responder rejects android fingerprint mismatch`() {
        val serverIdentity = identity("Mac")
        val clientIdentity = identity("Phone")
        val badIdentity = identity("Attacker")
        val macFp = serverIdentity.spkiFingerprint
        val androidFp = clientIdentity.spkiFingerprint
        val serverError = LinkedBlockingQueue<Throwable>()
        val server = TlsServer(serverIdentity, { true })
        val port = server.start { socket ->
            serverError.put(
                runCatching {
                    PairingWireProtocol.runResponder(
                        socket = socket,
                        nonce = nonce,
                        macSpkiFingerprint = macFp,
                        expectedAndroidFingerprint = androidFp,
                    )
                }.exceptionOrNull()!!,
            )
        }
        val clientSocket = TlsClient.connect("127.0.0.1", port, badIdentity, { it.contentEquals(macFp) })
        try {
            PairingWireProtocol.runInitiator(
                socket = clientSocket,
                nonce = nonce,
                macSpkiFingerprint = macFp,
                androidSpkiFingerprint = badIdentity.spkiFingerprint,
                device = PairingWireProtocol.WireDeviceInfo("android", "0.0.1", "Attacker"),
            )
        } catch (_: Exception) {
            // initiator may fail after the responder rejects the mismatch
        } finally {
            clientSocket.close()
            server.stop()
        }
        val error = serverError.poll(5, TimeUnit.SECONDS)
        assertTrue(error is PairingWireProtocol.PairingException)
        assertEquals(PairingWireProtocol.ERROR_SAS_REJECTED, (error as PairingWireProtocol.PairingException).code)
    }

    private fun identity(name: String): TlsIdentity =
        softwareKeyPair().let { TlsIdentity(it.private, SelfSignedCertificateMinter.mint(it, name)) }

    private fun makeGate(
        peerFp: ByteArray,
        macFp: ByteArray,
        androidFp: ByteArray,
        peerName: String,
    ): GateBundle {
        val store = EncryptedFilePeerTrustStore(
            File(folder.root, "trust-${peerFp.first()}-${androidFp.first()}.bin"),
            AesGcmTrustCipher(softwareAesKey()),
        )
        val transcript = PairingTranscript.create(
            nonce = nonce,
            macSpkiFingerprint = macFp,
            androidSpkiFingerprint = androidFp,
            protocolVersion = PairingTranscript.PROTOCOL_VERSION,
        )!!
        return GateBundle(SasConfirmationGate(transcript, peerFp, peerName, store), store)
    }

    private data class GateBundle(val gate: SasConfirmationGate, val store: EncryptedFilePeerTrustStore)
}
