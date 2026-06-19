package dev.hyphen.android.trust

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.GeneralSecurityException
import java.util.Base64

/**
 * File-backed encrypted peer trust store (HYP-M2-006), Android counterpart
 * of the macOS `KeychainTrustStore` (HYP-M2-005, plan §8.3).
 *
 * Records are serialized to a small line format, sealed with [TrustCipher]
 * (production key lives in the Android Keystore — [AndroidTrustStores]),
 * and written atomically (temp file + rename). The file lives in
 * app-private storage and `allowBackup=false` keeps it out of device
 * backups: trust must never leave the device.
 *
 * Plaintext format before sealing (peer counts are tiny, so every
 * operation is load → mutate → persist):
 *
 * ```text
 * hyphen-trust-v0
 * <fingerprintHex> <addedAtEpochMs> <base64(displayName)>
 * ```
 *
 * GCM authentication makes tampering and wrong-key reads detectable; both
 * surface as [TrustStoreException.CorruptStore], never as silently empty.
 */
class EncryptedFilePeerTrustStore(
    private val file: File,
    private val cipher: TrustCipher,
    private val crossInstanceLock: Any = Any(),
) : PeerTrustStore {

    companion object {
        const val FINGERPRINT_LENGTH = 32
        private const val HEADER = "hyphen-trust-v0"
    }

    private val lock = crossInstanceLock

    override fun add(peer: TrustedPeer) {
        validate(peer.spkiFingerprint)
        synchronized(lock) {
            val peers = load()
            peers[peer.fingerprintHex] = peer
            persist(peers)
        }
    }

    override fun get(fingerprint: ByteArray): TrustedPeer? {
        validate(fingerprint)
        synchronized(lock) {
            return load()[fingerprint.toHyphenHex()]
        }
    }

    override fun remove(fingerprint: ByteArray): Boolean {
        validate(fingerprint)
        synchronized(lock) {
            val peers = load()
            val removed = peers.remove(fingerprint.toHyphenHex()) != null
            if (removed) persist(peers)
            return removed
        }
    }

    override fun allPeers(): List<TrustedPeer> {
        synchronized(lock) {
            return load().values.toList()
        }
    }

    override fun removeAll() {
        synchronized(lock) {
            Files.deleteIfExists(file.toPath())
        }
    }

    private fun validate(fingerprint: ByteArray) {
        if (fingerprint.size != FINGERPRINT_LENGTH) {
            throw TrustStoreException.InvalidFingerprintLength(fingerprint.size)
        }
    }

    private fun load(): LinkedHashMap<String, TrustedPeer> {
        if (!file.exists()) return LinkedHashMap()
        val plaintext = try {
            cipher.open(file.readBytes())
        } catch (e: GeneralSecurityException) {
            throw TrustStoreException.CorruptStore(e)
        }
        return parse(plaintext.toString(Charsets.UTF_8))
    }

    private fun parse(text: String): LinkedHashMap<String, TrustedPeer> {
        val lines = text.split('\n')
        if (lines.firstOrNull() != HEADER) throw TrustStoreException.CorruptStore()
        val peers = LinkedHashMap<String, TrustedPeer>()
        for (line in lines.drop(1)) {
            if (line.isEmpty()) continue
            val fields = line.split(' ', limit = 3)
            if (fields.size != 3) throw TrustStoreException.CorruptStore()
            val fingerprint = fields[0].hyphenHexToBytesOrNull()
                ?.takeIf { it.size == FINGERPRINT_LENGTH }
                ?: throw TrustStoreException.CorruptStore()
            val addedAt = fields[1].toLongOrNull() ?: throw TrustStoreException.CorruptStore()
            val displayName = try {
                Base64.getDecoder().decode(fields[2]).toString(Charsets.UTF_8)
            } catch (e: IllegalArgumentException) {
                throw TrustStoreException.CorruptStore(e)
            }
            peers[fields[0]] = TrustedPeer(fingerprint, displayName, addedAt)
        }
        return peers
    }

    private fun persist(peers: Map<String, TrustedPeer>) {
        val text = buildString {
            append(HEADER).append('\n')
            for (peer in peers.values) {
                append(peer.fingerprintHex)
                append(' ').append(peer.addedAtEpochMs)
                append(' ').append(Base64.getEncoder().encodeToString(peer.displayName.toByteArray(Charsets.UTF_8)))
                append('\n')
            }
        }
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeBytes(cipher.seal(text.toByteArray(Charsets.UTF_8)))
        Files.move(
            temp.toPath(),
            file.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }
}
