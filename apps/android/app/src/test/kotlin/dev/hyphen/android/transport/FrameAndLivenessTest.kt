package dev.hyphen.android.transport

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class FrameAndLivenessTest {

    @Test
    fun `frames roundtrip back to back`() {
        val out = ByteArrayOutputStream()
        FrameIO.write(out, "first".toByteArray())
        FrameIO.write(out, "second".toByteArray())
        val input = ByteArrayInputStream(out.toByteArray())
        assertArrayEquals("first".toByteArray(), FrameIO.read(input))
        assertArrayEquals("second".toByteArray(), FrameIO.read(input))
        assertNull("clean EOF between frames", FrameIO.read(input))
    }

    @Test
    fun `oversized declared length is rejected before reading the body`() {
        val fiveMiB = 5 * 1024 * 1024
        val header = byteArrayOf(
            (fiveMiB ushr 24).toByte(), (fiveMiB ushr 16).toByte(),
            (fiveMiB ushr 8).toByte(), fiveMiB.toByte(),
        )
        assertThrows(FrameIO.FrameTooLarge::class.java) {
            FrameIO.read(ByteArrayInputStream(header))
        }
        assertThrows(FrameIO.FrameTooLarge::class.java) {
            FrameIO.write(ByteArrayOutputStream(), ByteArray(fiveMiB))
        }
    }

    @Test
    fun `truncated frame is a hard error not a clean EOF`() {
        val out = ByteArrayOutputStream()
        FrameIO.write(out, "payload".toByteArray())
        val truncated = out.toByteArray().copyOfRange(0, 6)
        assertThrows(EOFException::class.java) {
            FrameIO.read(ByteArrayInputStream(truncated))
        }
    }

    @Test
    fun `monitor degrades on silence and recovers on traffic`() {
        val transitions = mutableListOf<HeartbeatMonitor.State>()
        val monitor = HeartbeatMonitor(
            intervalMs = 10_000, missThreshold = 2, startedAtMs = 0,
            onStateChange = transitions::add,
        )
        monitor.tick(20_000) // exactly 2 intervals: not yet missed
        assertEquals(HeartbeatMonitor.State.HEALTHY, monitor.state)
        monitor.tick(20_001) // >2 intervals of silence
        assertEquals(HeartbeatMonitor.State.DEGRADED, monitor.state)
        monitor.envelopeReceived(25_000)
        assertEquals(HeartbeatMonitor.State.HEALTHY, monitor.state)
        monitor.tick(30_000)
        assertEquals(HeartbeatMonitor.State.HEALTHY, monitor.state)
        assertEquals(
            listOf(HeartbeatMonitor.State.DEGRADED, HeartbeatMonitor.State.HEALTHY),
            transitions,
        )
    }

    @Test
    fun `ack tracker times out exactly once and resolves on ack`() {
        val timeouts = mutableListOf<String>()
        val tracker = AckTracker(timeoutMs = 10_000, onTimeout = timeouts::add)
        tracker.registerSent("AAAAAAAAAAAAAAAAAAAAAAAAAA", nowMs = 0)
        tracker.registerSent("BBBBBBBBBBBBBBBBBBBBBBBBBB", nowMs = 0)
        assertEquals(true, tracker.ackReceived("AAAAAAAAAAAAAAAAAAAAAAAAAA"))
        tracker.tick(10_001)
        tracker.tick(20_000)
        assertEquals(listOf("BBBBBBBBBBBBBBBBBBBBBBBBBB"), timeouts)
        assertEquals(0, tracker.pendingCount())
        assertEquals(false, tracker.ackReceived("BBBBBBBBBBBBBBBBBBBBBBBBBB"))
    }
}
