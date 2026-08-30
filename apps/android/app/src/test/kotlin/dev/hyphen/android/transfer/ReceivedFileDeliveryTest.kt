package dev.hyphen.android.transfer

import dev.hyphen.android.transport.Json
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReceivedFileDeliveryTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `delivery preserves an existing same-name file`() {
        val destination = temp.newFolder("received")
        val existing = destination.resolve("report.txt").apply { writeText("keep me") }
        val source = temp.newFile("verified.part").apply { writeText("new payload") }
        val digest = sha256(source.readBytes())
        val completed = TransferCompleted(
            manifest = manifest("report.txt", source.length(), digest),
            file = source,
            sha256 = digest,
        )

        val delivered = ReceivedFileDelivery(destination).deliver(completed)

        assertEquals("keep me", existing.readText())
        assertEquals("report (1).txt", delivered.name)
        assertEquals("new payload", delivered.readText())
        assertFalse(source.exists())
        assertTrue(delivered.isFile)
    }

    @Test
    fun `delivery rejects a verified source mutated before publication`() {
        val destination = temp.newFolder("mutated-source-received")
        val source = temp.newFile("mutated-source.part").apply { writeText("verified payload") }
        val verifiedDigest = sha256(source.readBytes())
        val completed = TransferCompleted(
            manifest = manifest("report.txt", source.length(), verifiedDigest),
            file = source,
            sha256 = verifiedDigest,
        )
        source.writeText("tampered payload")

        assertThrows(IllegalArgumentException::class.java) {
            ReceivedFileDelivery(destination).deliver(completed)
        }

        assertTrue(source.exists())
        assertTrue(destination.listFiles().orEmpty().none { !it.name.startsWith(".hyphen-") })
    }

    @Test
    fun `delivery syncs final directory before deleting verified source`() {
        val destination = temp.newFolder("directory-sync-received")
        val source = temp.newFile("directory-sync-source.part").apply { writeText("verified payload") }
        val digest = sha256(source.readBytes())
        var directorySynced = false
        val delivery = ReceivedFileDelivery(
            destinationDirectories = listOf(destination),
            onSourceCleanupFailure = {},
            publisher = ReceivedFileDelivery::publishPreparedFile,
            directorySync = { directory ->
                assertEquals(destination.toPath(), directory)
                assertTrue(source.exists())
                assertTrue(destination.resolve("report.txt").isFile)
                assertTrue(destination.listFiles().orEmpty().none { it.name.startsWith(".hyphen-") })
                directorySynced = true
            },
        )

        val delivered = delivery.deliver(
            TransferCompleted(
                manifest = manifest("report.txt", source.length(), digest),
                file = source,
                sha256 = digest,
            ),
        )

        assertTrue(directorySynced)
        assertEquals("verified payload", delivered.readText())
        assertFalse(source.exists())
    }

    @Test
    fun `directory sync failure retains verified candidate and retry resyncs original name`() {
        val destination = temp.newFolder("directory-sync-failure")
        val source = temp.newFile("directory-sync-failure-source.part")
            .apply { writeText("verified payload") }
        val digest = sha256(source.readBytes())
        var syncAttempts = 0
        val delivery = ReceivedFileDelivery(
            destinationDirectories = listOf(destination),
            onSourceCleanupFailure = {},
            publisher = ReceivedFileDelivery::publishPreparedFile,
            directorySync = {
                syncAttempts += 1
                if (syncAttempts == 1) throw IOException("injected directory sync failure")
            },
        )

        assertThrows(IOException::class.java) {
            delivery.deliver(
                TransferCompleted(
                    manifest = manifest("report.txt", source.length(), digest),
                    file = source,
                    sha256 = digest,
                ),
            )
        }

        assertTrue(source.exists())
        assertEquals("verified payload", destination.resolve("report.txt").readText())

        val delivered = delivery.deliver(
            TransferCompleted(
                manifest = manifest("report.txt", source.length(), digest),
                file = source,
                sha256 = digest,
            ),
        )

        assertEquals("report.txt", delivered.name)
        assertEquals("verified payload", delivered.readText())
        assertFalse(source.exists())
        assertEquals(listOf("report.txt"), destination.listFiles().orEmpty().map { it.name })
    }

    @Test
    fun `concurrent delivery instances never overwrite the same candidate`() {
        val destination = temp.newFolder("concurrent-received")
        val firstSource = temp.newFile("first.part").apply { writeText("first payload") }
        val secondSource = temp.newFile("second.part").apply { writeText("second payload") }
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = listOf(
                firstSource to "first payload",
                secondSource to "second payload",
            ).map { (source, _) ->
                executor.submit<File> {
                    start.await()
                    ReceivedFileDelivery(destination).deliver(
                        TransferCompleted(
                            manifest = manifest(
                                "report.txt",
                                source.length(),
                                sha256(source.readBytes()),
                            ),
                            file = source,
                            sha256 = sha256(source.readBytes()),
                        ),
                    )
                }
            }
            start.countDown()
            val delivered = futures.map { it.get() }

            assertEquals(setOf("report.txt", "report (1).txt"), delivered.map { it.name }.toSet())
            assertEquals(
                setOf("first payload", "second payload"),
                delivered.map { it.readText() }.toSet(),
            )
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `delivery truncates utf8 names and reserves collision suffix bytes`() {
        val destination = temp.newFolder("long-name-received")
        val veryLongName = buildString { repeat(255) { appendCodePoint(0x1F600) } }
        val longSource = temp.newFile("long-name.part").apply { writeText("long payload") }
        val longDigest = sha256(longSource.readBytes())

        val deliveredLong = ReceivedFileDelivery(destination).deliver(
            TransferCompleted(
                manifest = manifest(veryLongName, longSource.length(), longDigest),
                file = longSource,
                sha256 = longDigest,
            ),
        )

        assertTrue(deliveredLong.isFile)
        assertTrue(deliveredLong.name.toByteArray(Charsets.UTF_8).size <= 255)

        val collisionName = buildString {
            repeat(62) { appendCodePoint(0x1F600) }
            append(".txt")
        }
        val existing = destination.resolve(collisionName).apply { writeText("keep me") }
        val collisionSource = temp.newFile("collision-name.part").apply { writeText("new payload") }
        val collisionDigest = sha256(collisionSource.readBytes())
        val deliveredCollision = ReceivedFileDelivery(destination).deliver(
            TransferCompleted(
                manifest = manifest(collisionName, collisionSource.length(), collisionDigest),
                file = collisionSource,
                sha256 = collisionDigest,
            ),
        )

        assertEquals("keep me", existing.readText())
        assertTrue(deliveredCollision.name.endsWith(" (1).txt"))
        assertTrue(deliveredCollision.name.toByteArray(Charsets.UTF_8).size <= 255)
        assertEquals("new payload", deliveredCollision.readText())
    }

    @Test
    fun `prepared file is atomically published by no-replace hard link`() {
        val destination = temp.newFolder("atomic-publication")
        val staged = destination.resolve(".hyphen-ready.part").apply { writeText("verified payload") }
        val candidate = destination.resolve("report.txt")

        ReceivedFileDelivery.publishPreparedFile(staged.toPath(), candidate.toPath())

        assertTrue(candidate.isFile)
        assertTrue(Files.isSameFile(staged.toPath(), candidate.toPath()))
        assertEquals("verified payload", candidate.readText())

        val competing = destination.resolve("competing.part").apply { writeText("different") }
        assertThrows(FileAlreadyExistsException::class.java) {
            ReceivedFileDelivery.publishPreparedFile(competing.toPath(), candidate.toPath())
        }
        assertEquals("verified payload", candidate.readText())
    }

    @Test
    fun `cleanup never truncates a published file sharing the staging inode`() {
        val destination = temp.newFolder("published-staging-orphan")
        val staged = destination.resolve(
            ".hyphen-923e4567-e89b-42d3-8456-426614174000.part",
        ).apply { writeText("published payload") }
        val delivered = destination.resolve("report.txt")
        ReceivedFileDelivery.publishPreparedFile(staged.toPath(), delivered.toPath())
        assertTrue(staged.setLastModified(1L))

        ReceivedFileDelivery(destination)

        assertEquals("published payload", delivered.readText())
        assertTrue(staged.exists())
        assertTrue(Files.isSameFile(staged.toPath(), delivered.toPath()))
    }

    @Test
    fun `delivery falls back when preferred volume cannot publish hard links`() {
        val preferred = temp.newFolder("preferred-volume")
        val fallback = temp.newFolder("internal-fallback")
        val source = temp.newFile("fallback-source.part").apply { writeText("verified payload") }
        val digest = sha256(source.readBytes())
        val completed = TransferCompleted(
            manifest = manifest("report.txt", source.length(), digest),
            file = source,
            sha256 = digest,
        )
        val publisher: (Path, Path) -> Unit = { staged, candidate ->
            if (candidate.startsWith(preferred.toPath())) {
                throw UnsupportedOperationException("volume has no hard-link support")
            }
            ReceivedFileDelivery.publishPreparedFile(staged, candidate)
        }

        val delivered = ReceivedFileDelivery(
            destinationDirectories = listOf(preferred, fallback),
            onSourceCleanupFailure = {},
            publisher = publisher,
            directorySync = {},
        ).deliver(completed)

        assertEquals(fallback.canonicalFile, delivered.parentFile.canonicalFile)
        assertEquals("verified payload", delivered.readText())
        assertFalse(source.exists())
        assertTrue(preferred.listFiles().orEmpty().none { !it.name.startsWith(".hyphen-") })
    }

    @Test
    fun `delivery startup scavenges aged publication staging only`() {
        val destination = temp.newFolder("delivery-orphans")
        val aged = destination.resolve(".hyphen-123e4567-e89b-42d3-a456-426614174000.part").apply {
            writeText("partial")
            setLastModified(1L)
        }
        val fresh = destination.resolve(".hyphen-223e4567-e89b-42d3-b456-426614174000.part")
            .apply { writeText("active") }
        val versionOneLookalike = destination.resolve(
            ".hyphen-323e4567-e89b-12d3-a456-426614174000.part",
        ).apply {
            writeText("not produced by Hyphen")
            setLastModified(1L)
        }
        val lookalike = destination.resolve(".hyphen-user.part").apply {
            writeText("user file")
            setLastModified(1L)
        }
        val lookalikeDirectory = destination.resolve(
            ".hyphen-423e4567-e89b-42d3-8456-426614174000.part",
        ).apply {
            mkdir()
            resolve("keep.txt").writeText("keep")
            setLastModified(1L)
        }
        val unrelated = destination.resolve("keep.part").apply {
            writeText("keep")
            setLastModified(1L)
        }

        ReceivedFileDelivery(destination)

        assertFalse(aged.exists())
        assertTrue(fresh.exists())
        assertTrue(versionOneLookalike.exists())
        assertTrue(lookalike.exists())
        assertTrue(lookalikeDirectory.resolve("keep.txt").exists())
        assertTrue(unrelated.exists())
    }

    @Test
    fun `owned cleanup quarantines before inspection and preserves a swapped directory`() {
        val root = temp.newFolder("cleanup-race")
        val candidate = root.resolve(".hyphen-523e4567-e89b-42d3-8456-426614174000.part")
            .apply {
                writeText("old staging")
                setLastModified(1L)
            }
        val saved = root.resolve("saved-staging.part")

        val deleted = OwnedFileCleanup.deleteIfAgedRegular(
            candidate = candidate.toPath(),
            cutoffMillis = System.currentTimeMillis(),
            beforeQuarantineMove = {
                Files.move(candidate.toPath(), saved.toPath())
                assertTrue(candidate.mkdir())
                candidate.resolve("keep.txt").writeText("keep")
            },
        )

        assertFalse(deleted)
        assertEquals("old staging", saved.readText())
        assertEquals("keep", candidate.resolve("keep.txt").readText())
    }

    @Test
    fun `owned cleanup preserves a regular file swapped after preflight`() {
        val root = temp.newFolder("cleanup-inode-race")
        val candidate = root.resolve(".hyphen-623e4567-e89b-42d3-8456-426614174000.part")
            .apply {
                writeText("original staging")
                setLastModified(1L)
            }
        val saved = root.resolve("saved-original.part")

        val deleted = OwnedFileCleanup.deleteIfAgedRegular(
            candidate = candidate.toPath(),
            cutoffMillis = System.currentTimeMillis(),
            beforeQuarantineMove = {
                Files.move(candidate.toPath(), saved.toPath())
                candidate.writeText("replacement")
                candidate.setLastModified(1L)
            },
        )

        assertFalse(deleted)
        assertEquals("original staging", saved.readText())
        assertEquals("replacement", candidate.readText())
    }

    @Test
    fun `owned cleanup never truncates a replacement swapped after descriptor binding`() {
        val root = temp.newFolder("cleanup-final-race")
        val candidate = root.resolve(".hyphen-723e4567-e89b-42d3-8456-426614174000.part")
            .apply {
                writeText("owned staging")
                setLastModified(1L)
            }
        val movedOwnedFile = root.resolve("owned-moved-aside.part")
        var replacement: File? = null

        val reclaimed = OwnedFileCleanup.deleteIfAgedRegular(
            candidate = candidate.toPath(),
            cutoffMillis = System.currentTimeMillis(),
            quarantineName = { ".hyphen-823e4567-e89b-42d3-8456-426614174000.part" },
            afterQuarantineBound = { quarantine ->
                Files.move(quarantine, movedOwnedFile.toPath())
                replacement = quarantine.toFile().apply { writeText("victim") }
            },
        )

        assertTrue(reclaimed)
        assertEquals(0L, movedOwnedFile.length())
        assertEquals("victim", candidate.readText())
        assertFalse(replacement?.exists() ?: true)
        assertTrue(root.listFiles().orEmpty().none { it.name.endsWith(".hold") })
    }

    @Test
    fun `file transfer storage scavenges aged unreferenced part files`() {
        val root = temp.newFolder("receive-storage-orphans")
        val aged = root.resolve("f_aged_receive.part").apply {
            writeText("verified but retained")
            setLastModified(1L)
        }
        val fresh = root.resolve("f_active_receive.part").apply { writeText("active") }
        val unrelated = root.resolve("keep.txt").apply {
            writeText("keep")
            setLastModified(1L)
        }

        FileTransferStorage(root)

        assertFalse(aged.exists())
        assertTrue(fresh.exists())
        assertTrue(unrelated.exists())
    }

    @Test
    fun `file transfer storage preserves aged parts with an active checkpoint`() {
        val root = temp.newFolder("referenced-receive-storage")
        val referenced = root.resolve("f_referenced_receive.part").apply {
            writeText("resumable")
            setLastModified(1L)
        }

        FileTransferStorage(root, isCheckpointActive = { it == "f_referenced_receive" })

        assertTrue(referenced.exists())
    }

    private fun manifest(
        filename: String,
        sizeBytes: Long,
        sha256: String = "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20",
    ): TransferManifest =
        TransferManifest.fromJson(
            Json.obj(
                "fileId" to Json.Str("f_delivery_test012345"),
                "filename" to Json.Str(filename),
                "sizeBytes" to Json.Num(sizeBytes.toString()),
                "mimeType" to Json.Str("text/plain"),
                "sha256" to Json.Str(sha256),
                "chunkSizeBytes" to Json.Num("1024"),
                "chunkCount" to Json.Num(if (sizeBytes == 0L) "0" else "1"),
            ),
        )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
