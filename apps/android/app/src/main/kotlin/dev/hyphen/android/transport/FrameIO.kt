package dev.hyphen.android.transport

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Length-prefixed framing (protocol v0 §2.1, HYP-M2-012): each frame is
 * `length(4 bytes, big-endian uint32) || payload(UTF-8 JSON)`. Frames
 * above 4 MiB are rejected (`transport/frame-too-large`); a length that
 * doesn't materialize is `transport/malformed-frame` territory.
 */
object FrameIO {

    const val MAX_FRAME_BYTES = 4 * 1024 * 1024

    class FrameTooLarge(val declared: Long) :
        IOException("frame of $declared bytes exceeds the 4 MiB cap")

    fun write(out: OutputStream, payload: ByteArray) {
        if (payload.size > MAX_FRAME_BYTES) throw FrameTooLarge(payload.size.toLong())
        val size = payload.size
        out.write(
            byteArrayOf(
                (size ushr 24).toByte(),
                (size ushr 16).toByte(),
                (size ushr 8).toByte(),
                size.toByte(),
            )
        )
        out.write(payload)
        out.flush()
    }

    /** @return the payload, or null on clean EOF between frames. */
    fun read(input: InputStream): ByteArray? {
        val header = readFully(input, 4, eofIsClean = true) ?: return null
        val length = ((header[0].toLong() and 0xFF) shl 24) or
            ((header[1].toLong() and 0xFF) shl 16) or
            ((header[2].toLong() and 0xFF) shl 8) or
            (header[3].toLong() and 0xFF)
        if (length > MAX_FRAME_BYTES) throw FrameTooLarge(length)
        return readFully(input, length.toInt(), eofIsClean = false)
    }

    private fun readFully(input: InputStream, count: Int, eofIsClean: Boolean): ByteArray? {
        val bytes = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(bytes, offset, count - offset)
            if (read < 0) {
                if (eofIsClean && offset == 0) return null
                throw EOFException("stream ended mid-frame ($offset/$count bytes)")
            }
            offset += read
        }
        return bytes
    }
}
