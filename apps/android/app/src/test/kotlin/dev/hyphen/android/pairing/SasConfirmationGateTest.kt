package dev.hyphen.android.pairing

import dev.hyphen.android.trust.AesGcmTrustCipher
import dev.hyphen.android.trust.EncryptedFilePeerTrustStore
import dev.hyphen.android.trust.softwareAesKey
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The acceptance property of HYP-M2-011: the user confirms the matching
 * code BEFORE trust is stored — proven against the real encrypted store.
 */
class SasConfirmationGateTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val peerFp = ByteArray(32) { 0xAB.toByte() }
    private lateinit var store: EncryptedFilePeerTrustStore

    @Before
    fun setUp() {
        store = EncryptedFilePeerTrustStore(
            File(folder.root, "peers.v0.bin"),
            AesGcmTrustCipher(softwareAesKey()),
        )
    }

    private fun makeGate() = SasConfirmationGate(
        transcript = PairingTranscript.create(
            nonce = ByteArray(16) { 0x01 },
            macSpkiFingerprint = peerFp,
            androidSpkiFingerprint = ByteArray(32) { 0x02 },
            protocolVersion = "hyphen/0.3",
        )!!,
        peerFingerprint = peerFp,
        peerDisplayName = "Haitian's Mac",
        trustStore = store,
    )

    @Test
    fun `nothing is stored before confirmation`() {
        val gate = makeGate()
        assertEquals(6, gate.sas.length)
        assertNull(gate.outcome)
        assertTrue("displaying the SAS must not persist anything", store.allPeers().isEmpty())
    }

    @Test
    fun `confirm stores exactly the peer fingerprint`() {
        val gate = makeGate()
        assertEquals(SasConfirmationGate.Outcome.TRUSTED, gate.confirm(nowEpochMs = 1_781_107_200_000))
        val stored = store.get(peerFp)!!
        assertEquals("Haitian's Mac", stored.displayName)
        assertEquals(1, store.allPeers().size)
    }

    @Test
    fun `reject persists nothing and is sticky`() {
        val gate = makeGate()
        assertEquals(SasConfirmationGate.Outcome.REJECTED, gate.reject())
        assertTrue(store.allPeers().isEmpty())
        // A dead pairing session can never become trusted.
        assertEquals(SasConfirmationGate.Outcome.REJECTED, gate.confirm())
        assertTrue(store.allPeers().isEmpty())
    }

    @Test
    fun `double confirm is idempotent and reject after confirm keeps trust`() {
        val gate = makeGate()
        gate.confirm()
        gate.confirm()
        assertEquals(1, store.allPeers().size)
        assertEquals(SasConfirmationGate.Outcome.TRUSTED, gate.reject())
        assertEquals(1, store.allPeers().size)
    }
}
