package dev.hyphen.android.session

import android.system.Os
import android.system.OsConstants
import dev.hyphen.android.trust.TrustCipher
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.GeneralSecurityException
import java.util.Base64

/** AES-GCM sealed, atomic, app-private storage for the current peer link. */
class EncryptedFilePeerLinkStore(
    private val file: File,
    private val cipher: TrustCipher,
    private val crossInstanceLock: Any = Any(),
    private val syncDirectory: (File) -> Unit = ::syncDirectoryDurably,
) : PeerLinkStore {
    override fun save(descriptor: RememberedPeerLink) {
        val plaintext = buildString {
            append(HEADER).append('\n')
            append(Base64.getUrlEncoder().withoutPadding().encodeToString(descriptor.host.toByteArray())).append('\n')
            append(descriptor.port).append('\n')
            append(descriptor.peerFingerprint.joinToString("") { "%02x".format(it) }).append('\n')
        }.toByteArray(Charsets.UTF_8)

        synchronized(crossInstanceLock) {
            val parent = requireNotNull(file.parentFile)
            ensureDirectoryDurable(parent)
            val temp = File.createTempFile("${file.name}.", ".tmp", parent)
            try {
                FileOutputStream(temp).use { output ->
                    output.write(cipher.seal(plaintext))
                    output.fd.sync()
                }
                Files.move(
                    temp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
                syncDirectory(parent)
            } finally {
                Files.deleteIfExists(temp.toPath())
            }
        }
    }

    override fun load(): RememberedPeerLink? = synchronized(crossInstanceLock) {
        if (!file.exists()) return@synchronized null
        val plaintext = try {
            cipher.open(file.readBytes()).toString(Charsets.UTF_8)
        } catch (error: GeneralSecurityException) {
            throw PeerLinkException.CorruptStore(error)
        }
        try {
            parse(plaintext)
        } catch (error: PeerLinkException.CorruptStore) {
            throw error
        } catch (error: RuntimeException) {
            throw PeerLinkException.CorruptStore(error)
        }
    }

    override fun clear() {
        synchronized(crossInstanceLock) {
            if (Files.deleteIfExists(file.toPath())) {
                file.parentFile?.let { syncDirectory(it) }
            }
        }
    }

    private fun parse(text: String): RememberedPeerLink {
        val lines = text.split('\n')
        if (lines.size != 5 || lines[0] != HEADER || lines[4].isNotEmpty()) {
            throw PeerLinkException.CorruptStore()
        }
        val host = try {
            Base64.getUrlDecoder().decode(lines[1]).toString(Charsets.UTF_8)
        } catch (error: IllegalArgumentException) {
            throw PeerLinkException.CorruptStore(error)
        }
        val port = lines[2].toIntOrNull() ?: throw PeerLinkException.CorruptStore()
        val fingerprint = decodeHex(lines[3]) ?: throw PeerLinkException.CorruptStore()
        return RememberedPeerLink(host, port, fingerprint)
    }

    private fun decodeHex(value: String): ByteArray? {
        if (value.length != 64) return null
        return ByteArray(32) { index ->
            value.substring(index * 2, index * 2 + 2).toIntOrNull(16)?.toByte() ?: return null
        }
    }

    /**
     * Creates each missing directory one level at a time and fsyncs the
     * containing directory after every new entry. Fsyncing only the new
     * `trust/` directory would not make the `trust` entry durable in its
     * already-existing app-private parent.
     */
    private fun ensureDirectoryDurable(directory: File) {
        if (directory.exists()) {
            check(directory.isDirectory) { "peer-link parent is not a directory" }
            return
        }
        val missing = mutableListOf<File>()
        var cursor: File? = directory
        while (cursor != null && !cursor.exists()) {
            missing += cursor
            cursor = cursor.parentFile
        }
        check(cursor?.isDirectory == true) { "peer-link directory has no existing parent" }
        for (entry in missing.asReversed()) {
            check(entry.mkdir() || entry.isDirectory) { "could not create peer-link directory" }
            entry.parentFile?.let(syncDirectory)
        }
    }

    private companion object {
        const val HEADER = "hyphen-peer-link-v0"
    }
}

/** Directory fsync needs the platform fd API on Android; FileChannel is for JVM tests. */
private fun syncDirectoryDurably(directory: File) {
    if (
        System.getProperty("java.vm.name") == "Dalvik" ||
        System.getProperty("java.vendor") == "The Android Project"
    ) {
        val descriptor = Os.open(
            directory.path,
            OsConstants.O_RDONLY or OsConstants.O_CLOEXEC,
            0,
        )
        try {
            Os.fsync(descriptor)
        } finally {
            Os.close(descriptor)
        }
        return
    }
    FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { channel ->
        channel.force(true)
    }
}
