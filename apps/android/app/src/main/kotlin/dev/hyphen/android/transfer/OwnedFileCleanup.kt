package dev.hyphen.android.transfer

import android.system.Os
import android.system.OsConstants
import java.io.FileDescriptor
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/**
 * Best-effort space reclamation for files whose basename is already proven to
 * be owned by Hyphen. The regular inode is opened without following symlinks,
 * atomically moved to another self-recognized name, then truncated and fsynced
 * through that bound descriptor. No pathname is ever unlinked, because POSIX
 * has no primitive that conditionally unlinks only when an inode still matches.
 * A race can move a name, but cannot make Hyphen truncate a replacement inode.
 */
internal object OwnedFileCleanup {
    fun deleteIfAgedRegular(
        candidate: Path,
        cutoffMillis: Long,
        stillOwned: () -> Boolean = { true },
        beforeQuarantineMove: (Path) -> Unit = {},
        quarantineName: () -> String = { ".hyphen-${UUID.randomUUID()}.part" },
        afterQuarantineBound: (Path) -> Unit = {},
    ): Boolean {
        val parent = candidate.parent ?: return false
        val bound = openBoundRegular(candidate) ?: return false
        bound.use {
            // A post-publication staging link shares its inode with the final
            // user-visible file. Truncating it would corrupt the delivered file.
            if (bound.linkCount != 1L) return false
            if (bound.modifiedAtMillis > cutoffMillis) return false
            if (!runCatching { stillOwned() }.getOrDefault(false)) return false
            if (runCatching { beforeQuarantineMove(candidate) }.isFailure) return false

            val basename = runCatching { quarantineName() }.getOrNull() ?: return false
            if (!isPlainBasename(basename)) return false
            val quarantine = parent.resolve(basename)
            if (
                runCatching {
                    Files.move(candidate, quarantine, StandardCopyOption.ATOMIC_MOVE)
                }.isFailure
            ) {
                return false
            }

            var reclaimed = false
            try {
                if (pathIdentity(quarantine) != bound.identity) return false
                if (!runCatching { stillOwned() }.getOrDefault(false)) return false
                if (runCatching { afterQuarantineBound(quarantine) }.isFailure) return false
                if (!runCatching { stillOwned() }.getOrDefault(false)) return false
                if (bound.currentLinkCount(quarantine) != 1L) return false

                bound.truncateAndSync()
                reclaimed = true

                // If another actor replaced the quarantine basename, preserve
                // that replacement under the original or an unrecognized hold
                // name. The descriptor above still reclaimed only our inode.
                if (pathIdentity(quarantine) != bound.identity) {
                    preserveReplacement(quarantine, candidate)
                }
                return true
            } finally {
                if (!reclaimed) restoreWithoutReplacing(quarantine, candidate)
            }
        }
    }

    private interface BoundRegularFile : AutoCloseable {
        val identity: Any
        val modifiedAtMillis: Long
        val linkCount: Long
        fun currentLinkCount(path: Path): Long?
        fun truncateAndSync()
    }

    private data class PosixIdentity(val device: Long, val inode: Long)

    private class AndroidBoundRegularFile(
        private val descriptor: FileDescriptor,
        override val identity: PosixIdentity,
        override val modifiedAtMillis: Long,
        override val linkCount: Long,
    ) : BoundRegularFile {
        override fun currentLinkCount(path: Path): Long? =
            runCatching { Os.fstat(descriptor).st_nlink }.getOrNull()

        override fun truncateAndSync() {
            Os.ftruncate(descriptor, 0)
            Os.fsync(descriptor)
        }

        override fun close() {
            runCatching { Os.close(descriptor) }
        }
    }

    private class NioBoundRegularFile(
        private val channel: FileChannel,
        override val identity: Any,
        override val modifiedAtMillis: Long,
        override val linkCount: Long,
    ) : BoundRegularFile {
        override fun currentLinkCount(path: Path): Long? =
            runCatching {
                (Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS) as Number).toLong()
            }.getOrNull()

        override fun truncateAndSync() {
            channel.truncate(0)
            channel.force(true)
        }

        override fun close() = channel.close()
    }

    private fun openBoundRegular(path: Path): BoundRegularFile? =
        if (isAndroidRuntime()) openAndroidBoundRegular(path) else openNioBoundRegular(path)

    private fun openAndroidBoundRegular(path: Path): BoundRegularFile? {
        val descriptor = runCatching {
            Os.open(
                path.toString(),
                OsConstants.O_WRONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,
                0,
            )
        }.getOrNull() ?: return null
        return try {
            val metadata = Os.fstat(descriptor)
            if (metadata.st_mode and OsConstants.S_IFMT != OsConstants.S_IFREG) {
                Os.close(descriptor)
                null
            } else {
                AndroidBoundRegularFile(
                    descriptor = descriptor,
                    identity = PosixIdentity(metadata.st_dev, metadata.st_ino),
                    modifiedAtMillis = metadata.st_mtim.tv_sec * 1_000L +
                        metadata.st_mtim.tv_nsec / 1_000_000L,
                    linkCount = metadata.st_nlink,
                )
            }
        } catch (_: Exception) {
            runCatching { Os.close(descriptor) }
            null
        }
    }

    private fun openNioBoundRegular(path: Path): BoundRegularFile? {
        val options: Set<OpenOption> = setOf(StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
        val channel = runCatching { FileChannel.open(path, options) }.getOrNull() ?: return null
        return try {
            val attributes = readAttributes(path) ?: run {
                channel.close()
                return null
            }
            val identity = attributes.fileKey() ?: run {
                channel.close()
                return null
            }
            val linkCount = runCatching {
                (Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS) as Number).toLong()
            }.getOrNull() ?: run {
                channel.close()
                return null
            }
            if (!attributes.isRegularFile) {
                channel.close()
                null
            } else {
                NioBoundRegularFile(
                    channel = channel,
                    identity = identity,
                    modifiedAtMillis = attributes.lastModifiedTime().toMillis(),
                    linkCount = linkCount,
                )
            }
        } catch (_: Exception) {
            runCatching { channel.close() }
            null
        }
    }

    private fun pathIdentity(path: Path): Any? =
        if (isAndroidRuntime()) {
            runCatching {
                val metadata = Os.lstat(path.toString())
                PosixIdentity(metadata.st_dev, metadata.st_ino)
            }.getOrNull()
        } else {
            readAttributes(path)?.fileKey()
        }

    private fun readAttributes(path: Path): BasicFileAttributes? =
        runCatching {
            Files.readAttributes(
                path,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
        }.getOrNull()

    private fun restoreWithoutReplacing(quarantine: Path, candidate: Path) {
        runCatching {
            if (
                Files.exists(quarantine, LinkOption.NOFOLLOW_LINKS) &&
                !Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)
            ) {
                Files.move(quarantine, candidate)
            }
        }
    }

    private fun preserveReplacement(quarantine: Path, candidate: Path) {
        if (!Files.exists(quarantine, LinkOption.NOFOLLOW_LINKS)) return
        if (
            !Files.exists(candidate, LinkOption.NOFOLLOW_LINKS) &&
            runCatching { Files.move(quarantine, candidate) }.isSuccess
        ) {
            return
        }
        repeat(PRESERVE_NAME_ATTEMPTS) {
            val hold = quarantine.parent.resolve(".hyphen-preserved-${UUID.randomUUID()}.hold")
            if (runCatching { Files.move(quarantine, hold) }.isSuccess) return
        }
    }

    private fun isPlainBasename(value: String): Boolean =
        value.isNotEmpty() && value !in setOf(".", "..") && Path.of(value).fileName.toString() == value

    private fun isAndroidRuntime(): Boolean =
        System.getProperty("java.vm.name") == "Dalvik" ||
            System.getProperty("java.vendor") == "The Android Project"

    private const val PRESERVE_NAME_ATTEMPTS = 16
}
