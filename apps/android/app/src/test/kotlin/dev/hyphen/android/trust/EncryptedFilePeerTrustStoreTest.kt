package dev.hyphen.android.trust

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

/** Runs against a real file with a real (software) AES key — only the
 *  Android Keystore key provider is substituted versus production. */
class EncryptedFilePeerTrustStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val key = softwareAesKey()
    private val fpA = ByteArray(32) { 0xAA.toByte() }
    private val fpB = ByteArray(32) { 0xBB.toByte() }

    private lateinit var file: File
    private lateinit var store: EncryptedFilePeerTrustStore

    private fun peer(fp: ByteArray, name: String) =
        TrustedPeer(fp, name, addedAtEpochMs = 1_781_020_800_000)

    @Before
    fun setUp() {
        file = File(folder.root, "trust/peers.v0.bin")
        store = EncryptedFilePeerTrustStore(file, AesGcmTrustCipher(key))
    }

    @Test
    fun `add then get roundtrips a record`() {
        val original = peer(fpA, "Pixel")
        store.add(original)
        assertEquals(original, store.get(fpA))
    }

    @Test
    fun `get unknown fingerprint returns null`() {
        assertNull(store.get(fpA))
        store.add(peer(fpA, "Pixel"))
        assertNull(store.get(fpB))
    }

    @Test
    fun `add is upsert`() {
        store.add(peer(fpA, "Pixel"))
        store.add(peer(fpA, "Pixel 9 Pro"))
        assertEquals("Pixel 9 Pro", store.get(fpA)?.displayName)
        assertEquals(1, store.allPeers().size)
    }

    @Test
    fun `remove reports presence`() {
        store.add(peer(fpA, "Pixel"))
        assertTrue(store.remove(fpA))
        assertNull(store.get(fpA))
        assertFalse(store.remove(fpA))
    }

    @Test
    fun `allPeers lists everything`() {
        store.add(peer(fpA, "Pixel"))
        store.add(peer(fpB, "Galaxy"))
        assertEquals(setOf("Pixel", "Galaxy"), store.allPeers().map { it.displayName }.toSet())
    }

    @Test
    fun `removeAll empties the store`() {
        store.add(peer(fpA, "Pixel"))
        store.add(peer(fpB, "Galaxy"))
        store.removeAll()
        assertEquals(emptyList<TrustedPeer>(), store.allPeers())
        assertFalse(file.exists())
    }

    @Test
    fun `wrong fingerprint length is rejected everywhere`() {
        val short = ByteArray(31) { 0x01 }
        val addError = assertThrows(TrustStoreException.InvalidFingerprintLength::class.java) {
            store.add(peer(short, "x"))
        }
        assertEquals(31, addError.actual)
        assertThrows(TrustStoreException.InvalidFingerprintLength::class.java) { store.get(short) }
        assertThrows(TrustStoreException.InvalidFingerprintLength::class.java) { store.remove(short) }
    }

    @Test
    fun `records persist across store instances`() {
        store.add(peer(fpA, "Pixel"))
        val reopened = EncryptedFilePeerTrustStore(file, AesGcmTrustCipher(key))
        assertEquals(peer(fpA, "Pixel"), reopened.get(fpA))
    }

    @Test
    fun `display names with spaces newlines and empty survive roundtrip`() {
        store.add(peer(fpA, "Pixel 9 Pro\nsecond line"))
        store.add(peer(fpB, ""))
        assertEquals("Pixel 9 Pro\nsecond line", store.get(fpA)?.displayName)
        assertEquals("", store.get(fpB)?.displayName)
    }

    @Test
    fun `file on disk never contains plaintext`() {
        store.add(peer(fpA, "Pixel"))
        val raw = file.readBytes()
        val rawText = raw.toString(Charsets.ISO_8859_1)
        assertFalse("display name leaked", rawText.contains("Pixel"))
        assertFalse("fingerprint hex leaked", rawText.contains(fpA.toHyphenHex()))
        assertFalse("format header leaked", rawText.contains("hyphen-trust-v0"))
    }

    @Test
    fun `tampered file surfaces as corrupt store, not as empty`() {
        store.add(peer(fpA, "Pixel"))
        val raw = file.readBytes()
        raw[raw.size - 1] = (raw.last().toInt() xor 0x01).toByte()
        file.writeBytes(raw)
        assertThrows(TrustStoreException.CorruptStore::class.java) { store.get(fpA) }
        assertThrows(TrustStoreException.CorruptStore::class.java) { store.allPeers() }
    }

    @Test
    fun `wrong key surfaces as corrupt store`() {
        store.add(peer(fpA, "Pixel"))
        val otherKey = EncryptedFilePeerTrustStore(file, AesGcmTrustCipher(softwareAesKey()))
        assertThrows(TrustStoreException.CorruptStore::class.java) { otherKey.allPeers() }
    }

    @Test
    fun `stores are isolated by file`() {
        store.add(peer(fpA, "Pixel"))
        val other = EncryptedFilePeerTrustStore(
            File(folder.root, "other/peers.v0.bin"),
            AesGcmTrustCipher(key),
        )
        assertNull(other.get(fpA))
        assertEquals(emptyList<TrustedPeer>(), other.allPeers())
    }

    @Test
    fun `concurrent store instances do not lose peer updates`() {
        val sharedLock = Any()
        val first = EncryptedFilePeerTrustStore(file, AesGcmTrustCipher(key), sharedLock)
        val second = EncryptedFilePeerTrustStore(file, AesGcmTrustCipher(key), sharedLock)
        val start = java.util.concurrent.CountDownLatch(1)
        val done = java.util.concurrent.CountDownLatch(2)
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()

        fun worker(store: EncryptedFilePeerTrustStore, peer: TrustedPeer) {
            Thread {
                try {
                    start.await()
                    store.add(peer)
                } catch (e: Throwable) {
                    errors.add(e)
                } finally {
                    done.countDown()
                }
            }.start()
        }

        worker(first, peer(fpA, "Pixel"))
        worker(second, peer(fpB, "Galaxy"))
        start.countDown()
        assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(errors.isEmpty())
        val reopened = EncryptedFilePeerTrustStore(file, AesGcmTrustCipher(key), sharedLock)
        assertEquals(setOf("Pixel", "Galaxy"), reopened.allPeers().map { it.displayName }.toSet())
    }
}
