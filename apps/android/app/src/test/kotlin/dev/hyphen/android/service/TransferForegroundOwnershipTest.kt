package dev.hyphen.android.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferForegroundOwnershipTest {
    @Test
    fun `reservations remain visible until every begin-to-registration window closes`() {
        val ownership = TransferForegroundOwnershipCoordinator()
        val first = ownership.reserve()
        val second = ownership.reserve()

        assertTrue(ownership.hasReservations())
        assertTrue(ownership.release(first))
        assertTrue(ownership.hasReservations())
        assertTrue(ownership.release(second))
        assertFalse(ownership.hasReservations())
        assertFalse(ownership.release(second))
    }

    @Test
    fun `terminal abort invalidates every pending reservation`() {
        val ownership = TransferForegroundOwnershipCoordinator()
        val pending = ownership.reserve()

        assertTrue(ownership.clear())
        assertFalse(ownership.hasReservations())
        assertFalse(ownership.release(pending))
    }

    @Test
    fun `drain policy keeps service while an unregistered transfer is reserved`() {
        val ownership = TransferForegroundOwnershipCoordinator()
        ownership.reserve()

        val plan = CompanionServicePolicy.planTransferStop(
            reason = TransferForegroundStopReason.DRAINED,
            hasInFlightTransfers = ownership.hasReservations(),
            keepConnected = false,
        )

        assertFalse(plan.stopService)
    }

    @Test
    fun `reservation racing the sender probe cannot produce a stale empty result`() {
        val ownership = TransferForegroundOwnershipCoordinator()

        val hasWork = ownership.hasForegroundWork {
            ownership.reserve()
            false
        }

        assertTrue(hasWork)
    }

    @Test
    fun `transient failed reservation retries to a stable empty result`() {
        val ownership = TransferForegroundOwnershipCoordinator()
        var probes = 0

        val hasWork = ownership.hasForegroundWork {
            probes += 1
            if (probes == 1) {
                val failedStart = ownership.reserve()
                ownership.release(failedStart)
            }
            false
        }

        assertFalse(hasWork)
        assertEquals(2, probes)
    }
}
