package dev.hyphen.android.transfer

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID

/**
 * Delivers a verified receive into user-visible app storage without ever
 * replacing an existing file. The source is copied to a same-directory
 * staging file first, fsynced, then atomically published to the first
 * available name with a same-filesystem no-replace hard link.
 */
class ReceivedFileDelivery internal constructor(
    private val destinationDirectories: List<File>,
    private val onSourceCleanupFailure: () -> Unit = {},
    private val publisher: (Path, Path) -> Unit,
    private val directorySync: (Path) -> Unit,
) {

    constructor(
        destinationDirectory: File,
        onSourceCleanupFailure: () -> Unit = {},
    ) : this(
        destinationDirectories = listOf(destinationDirectory),
        onSourceCleanupFailure = onSourceCleanupFailure,
        publisher = ::publishPreparedFile,
        directorySync = ::syncDirectoryDurably,
    )

    constructor(
        destinationDirectories: List<File>,
        onSourceCleanupFailure: () -> Unit = {},
    ) : this(
        destinationDirectories = destinationDirectories,
        onSourceCleanupFailure = onSourceCleanupFailure,
        publisher = ::publishPreparedFile,
        directorySync = ::syncDirectoryDurably,
    )

    init {
        require(destinationDirectories.isNotEmpty()) { "at least one received-file destination is required" }
        destinationDirectories.forEach(::prepareDestinationIfAvailable)
    }

    @Synchronized
    fun deliver(completed: TransferCompleted): File {
        val originalName = completed.manifest.filename
        require(File(originalName).name == originalName && originalName !in setOf(".", "..")) {
            "received filename must be a plain filename"
        }
        require(completed.file.isFile) { "verified receive source is missing" }
        require(completed.sha256 == completed.manifest.sha256) {
            "verified receive digest does not match manifest"
        }

        var lastFailure: Exception? = null
        destinationDirectories.distinctBy { it.absolutePath }.forEach { destinationDirectory ->
            try {
                return deliverTo(completed, originalName, destinationDirectory)
            } catch (error: PublicationDurabilityException) {
                // A complete final link already exists. Do not create a second
                // user-visible copy on another volume when its directory sync
                // failed; retain the verified source for an explicit retry.
                throw error
            } catch (error: IOException) {
                lastFailure = error
            } catch (error: UnsupportedOperationException) {
                lastFailure = IOException("destination cannot atomically publish received files", error)
            }
        }
        throw lastFailure ?: IOException("received-file destination is unavailable")
    }

    private fun deliverTo(
        completed: TransferCompleted,
        originalName: String,
        destinationDirectory: File,
    ): File {
        if (!destinationDirectory.isDirectory && !destinationDirectory.mkdirs()) {
            throw IOException("received-file destination is unavailable")
        }
        cleanupAgedStaging(destinationDirectory)
        val staged = createStagingFile(destinationDirectory)
        try {
            Files.copy(completed.file.toPath(), staged, StandardCopyOption.REPLACE_EXISTING)
            FileChannel.open(staged, StandardOpenOption.WRITE).use { it.force(true) }
            require(Files.size(staged) == completed.manifest.sizeBytes) {
                "staged receive size does not match manifest"
            }
            require(sha256Hex(staged) == completed.sha256) {
                "staged receive digest does not match verified transfer"
            }
            repeat(MAX_COLLISION_ATTEMPTS) { collisionIndex ->
                val candidate = destinationDirectory.resolve(
                    collisionSafeName(originalName, collisionIndex),
                )
                try {
                    publisher(staged, candidate.toPath())
                    try {
                        // Persist the desired final namespace state in one
                        // directory fsync: final link present, staging absent.
                        Files.delete(staged)
                        directorySync(destinationDirectory.toPath())
                    } catch (error: Exception) {
                        // The candidate is already a complete, verified inode.
                        // Retain it with the source/checkpoint: a retry recognizes
                        // the matching payload and retries only directory sync.
                        throw PublicationDurabilityException(error)
                    }
                    cleanupVerifiedSource(completed.file)
                    return candidate
                } catch (_: FileAlreadyExistsException) {
                    if (matchesVerifiedCandidate(candidate.toPath(), completed)) {
                        try {
                            directorySync(destinationDirectory.toPath())
                        } catch (error: Exception) {
                            throw PublicationDurabilityException(error)
                        }
                        cleanupVerifiedSource(completed.file)
                        return candidate
                    }
                    // A different pre-existing file or concurrent delivery owns this name.
                }
            }
            throw IOException("too many received-file name collisions")
        } finally {
            // A published hard link already exposes the complete inode. Do not
            // turn best-effort staging cleanup failure into a false delivery
            // failure; the exact-name age scavenger will retry later.
            runCatching { Files.deleteIfExists(staged) }
        }
    }

    private fun prepareDestinationIfAvailable(destinationDirectory: File) {
        if (destinationDirectory.isDirectory || destinationDirectory.mkdirs()) {
            cleanupAgedStaging(destinationDirectory)
        }
    }

    private fun createStagingFile(destinationDirectory: File): Path {
        repeat(STAGING_NAME_ATTEMPTS) {
            val candidate = destinationDirectory.resolve(".hyphen-${UUID.randomUUID()}.part").toPath()
            try {
                return Files.createFile(candidate)
            } catch (_: FileAlreadyExistsException) {
                // UUID collisions are implausible, but retry without replacing.
            }
        }
        throw IOException("could not allocate received-file staging")
    }

    private fun cleanupAgedStaging(destinationDirectory: File) {
        val cutoff = System.currentTimeMillis() - DELIVERY_STAGING_MAX_AGE_MS
        destinationDirectory.listFiles().orEmpty().forEach { file ->
            if (STAGING_NAME.matches(file.name)) {
                OwnedFileCleanup.deleteIfAgedRegular(
                    candidate = file.toPath(),
                    cutoffMillis = cutoff,
                )
            }
        }
    }

    private fun sha256Hex(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun matchesVerifiedCandidate(candidate: Path, completed: TransferCompleted): Boolean =
        runCatching {
            Files.isRegularFile(candidate, java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
                Files.size(candidate) == completed.manifest.sizeBytes &&
                sha256Hex(candidate) == completed.sha256
        }.getOrDefault(false)

    private fun cleanupVerifiedSource(source: File) {
        if (!source.delete()) {
            source.deleteOnExit()
            onSourceCleanupFailure()
        }
    }

    private fun collisionSafeName(original: String, collisionIndex: Int): String {
        val extensionIndex = original.lastIndexOf('.').takeIf { it > 0 } ?: original.length
        val rawStem = original.substring(0, extensionIndex)
        val rawExtension = original.substring(extensionIndex)
        val suffix = if (collisionIndex == 0) "" else " ($collisionIndex)"
        val extension = utf8Prefix(rawExtension, MAX_EXTENSION_BYTES)
        val stemBudget = MAX_FILENAME_BYTES - suffix.toByteArray(Charsets.UTF_8).size -
            extension.toByteArray(Charsets.UTF_8).size
        val stem = utf8Prefix(rawStem, stemBudget)
        return "$stem$suffix$extension"
    }

    /** Keep every candidate below common Android/macOS NAME_MAX without splitting UTF-8. */
    private fun utf8Prefix(value: String, maxBytes: Int): String {
        if (maxBytes <= 0) return ""
        var index = 0
        var usedBytes = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            val codePointBytes = when {
                codePoint <= 0x7f -> 1
                codePoint <= 0x7ff -> 2
                codePoint <= 0xffff -> 3
                else -> 4
            }
            if (usedBytes + codePointBytes > maxBytes) break
            usedBytes += codePointBytes
            index += Character.charCount(codePoint)
        }
        return value.substring(0, index)
    }

    companion object {
        private const val MAX_COLLISION_ATTEMPTS = 10_000
        private const val MAX_FILENAME_BYTES = 255
        private const val MAX_EXTENSION_BYTES = 64
        private const val DELIVERY_STAGING_MAX_AGE_MS = 24L * 60 * 60 * 1000
        private const val STAGING_NAME_ATTEMPTS = 16
        private val STAGING_NAME = Regex(
            "^\\.hyphen-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-" +
                "[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}\\.part$",
        )

        /**
         * The prepared inode is complete before its final basename exists.
         * createLink is atomic and fails when the candidate already exists, so
         * a crash can leave only a hidden staging link or a complete final link.
         */
        internal fun publishPreparedFile(staged: Path, candidate: Path) {
            Files.createLink(candidate, staged)
        }
    }

    private class PublicationDurabilityException(cause: Throwable) :
        IOException("received-file publication directory could not be synced", cause)
}
