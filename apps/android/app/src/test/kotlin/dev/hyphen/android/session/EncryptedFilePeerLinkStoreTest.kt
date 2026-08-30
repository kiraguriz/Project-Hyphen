package dev.hyphen.android.session

import dev.hyphen.android.trust.AesGcmTrustCipher
import dev.hyphen.android.trust.softwareAesKey
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EncryptedFilePeerLinkStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val key = softwareAesKey()
    private val fingerprint = ByteArray(32) { 0xA5.toByte() }
    private lateinit var file: File
    private lateinit var store: EncryptedFilePeerLinkStore

    @Before
    fun setUp() {
        file = File(folder.root, "trust/peer-link.v0.bin")
        store = EncryptedFilePeerLinkStore(file, AesGcmTrustCipher(key))
    }

    @Test
    fun `descriptor roundtrips across store instances`() {
        val descriptor = RememberedPeerLink("192.168.1.42", 43123, fingerprint)
        store.save(descriptor)

        val reopened = EncryptedFilePeerLinkStore(file, AesGcmTrustCipher(key))
        assertEquals(descriptor, reopened.load())
    }

    @Test
    fun `routing metadata and fingerprint are encrypted at rest`() {
        store.save(RememberedPeerLink("macbook.local", 43123, fingerprint))

        val raw = file.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(raw.contains("macbook.local"))
        assertFalse(raw.contains("a5".repeat(32)))
        assertFalse(raw.contains("hyphen-peer-link-v0"))
    }

    @Test
    fun `tampering fails closed instead of becoming an empty store`() {
        store.save(RememberedPeerLink("192.168.1.42", 43123, fingerprint))
        val bytes = file.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
        file.writeBytes(bytes)

        assertThrows(PeerLinkException.CorruptStore::class.java) { store.load() }
    }

    @Test
    fun `clear removes the descriptor`() {
        store.save(RememberedPeerLink("192.168.1.42", 43123, fingerprint))
        store.clear()
        assertNull(store.load())
        assertTrue(!file.exists())
    }

    @Test
    fun `save and clear make both directory mutations durable`() {
        val syncedDirectories = mutableListOf<File>()
        val durableStore = EncryptedFilePeerLinkStore(
            file = file,
            cipher = AesGcmTrustCipher(key),
            syncDirectory = { syncedDirectories += it },
        )

        durableStore.save(RememberedPeerLink("192.168.1.42", 43123, fingerprint))
        durableStore.clear()

        assertEquals(
            listOf(folder.root, file.parentFile, file.parentFile),
            syncedDirectories,
        )
    }
}
