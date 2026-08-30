package dev.hyphen.android.transfer

import android.system.Os
import android.system.OsConstants
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Makes a directory-name mutation durable on both Android and host-JVM tests. */
internal fun syncDirectoryDurably(path: Path) {
    if (isAndroidRuntime()) {
        val descriptor = Os.open(
            path.toString(),
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
    FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) }
}

private fun isAndroidRuntime(): Boolean =
    System.getProperty("java.vm.name") == "Dalvik" ||
        System.getProperty("java.vendor") == "The Android Project"
