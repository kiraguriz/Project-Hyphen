package dev.hyphen.android.service

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
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
    fun `source reselection retirement clears ownership before publishing retry and stopping`() {
        val ownership = TransferForegroundOwnershipCoordinator()
        val pending = ownership.reserve()
        val order = mutableListOf<String>()

        retireTransferForegroundOwnership(
            ownership = ownership,
            lifecycle = TransferForegroundLifecycleCoordinator(),
            publishRetryRequired = {
                assertFalse(ownership.hasReservations())
                order += "retry"
            },
            stopTransferService = {
                assertFalse(ownership.hasReservations())
                order += "stop"
            },
        )

        assertEquals(listOf("retry", "stop"), order)
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

    @Test
    fun `reselection failure never queues normal end after retry publication`() {
        assertEquals(
            TransferWorkerFailurePlan(
                TransferWorkerSenderCleanup.RETIRE_FOR_RESELECTION,
                TransferWorkerForegroundCleanup.RELEASE_RESERVATION_AND_REQUIRE_RESELECTION,
            ),
            planTransferWorkerFailure(
                registrationTransferred = false,
                sourceRetryPending = true,
            ),
        )
        assertEquals(
            TransferWorkerFailurePlan(
                TransferWorkerSenderCleanup.RETIRE_FOR_RESELECTION,
                TransferWorkerForegroundCleanup.REQUIRE_RESELECTION,
            ),
            planTransferWorkerFailure(
                registrationTransferred = true,
                sourceRetryPending = true,
            ),
        )
    }

    @Test
    fun `queued stop cannot overwrite a newer source-reselection generation`() {
        val gate = TransferStateRevisionGate()
        var state = TransferStateValues(retryRequired = false, active = true)
        val queuedStop = gate.snapshot { state }.revision

        gate.mutate {
            state = TransferStateValues(retryRequired = true, active = false)
        }

        assertFalse(
            gate.mutateIfCurrent(queuedStop) {
                state = TransferStateValues(retryRequired = false, active = false)
            },
        )
        assertTrue(state.retryRequired)
        assertFalse(state.active)
    }

    @Test
    fun `queued control from a dead process cannot match a reset revision`() {
        val gate = TransferStateRevisionGate()
        val values = TransferStateValues(retryRequired = false, active = true)
        val oldToken = gate.snapshot(processEpoch = "old-process") { values }.controlToken()
        val restarted = gate.snapshot(processEpoch = "new-process") { values }

        assertFalse(oldToken.matches(restarted))
    }

    @Test
    fun `terminal effects complete before a newly attempted begin can publish work`() {
        val lifecycle = TransferForegroundLifecycleCoordinator()
        val stopCommitted = CountDownLatch(1)
        val beginAttempted = CountDownLatch(1)
        val allowStopEffects = CountDownLatch(1)
        var active = true
        var senderHasWork = true
        var serviceRunning = true

        val stopThread = thread(name = "old-transfer-stop") {
            lifecycle.transaction {
                active = false
                stopCommitted.countDown()
                check(allowStopEffects.await(2, TimeUnit.SECONDS))
                senderHasWork = false
                serviceRunning = false
            }
        }
        assertTrue(stopCommitted.await(2, TimeUnit.SECONDS))

        val beginThread = thread(name = "new-transfer-begin") {
            beginAttempted.countDown()
            lifecycle.transaction {
                active = true
                senderHasWork = true
                serviceRunning = true
            }
        }
        assertTrue(beginAttempted.await(2, TimeUnit.SECONDS))
        allowStopEffects.countDown()
        stopThread.join(2_000)
        beginThread.join(2_000)

        assertFalse(stopThread.isAlive)
        assertFalse(beginThread.isAlive)
        assertTrue(active)
        assertTrue(senderHasWork)
        assertTrue(serviceRunning)
    }

    @Test
    fun `sender completion crosses a queue before acquiring lifecycle ownership`() {
        val senderMonitor = Any()
        val queued = ArrayDeque<() -> Unit>()
        var completionRan = false
        var senderWasStillHeld = false

        synchronized(senderMonitor) {
            dispatchCompletedOutboundTransfer(
                outgoing = true,
                complete = true,
                enqueue = queued::addLast,
            ) {
                completionRan = true
                senderWasStillHeld = Thread.holdsLock(senderMonitor)
            }
            assertFalse(completionRan)
        }

        queued.removeFirst().invoke()
        assertTrue(completionRan)
        assertFalse(senderWasStillHeld)
    }

    @Test
    fun `ordinary failure cancels only registered work and ends its matching ownership`() {
        assertEquals(
            TransferWorkerFailurePlan(
                TransferWorkerSenderCleanup.CANCEL_REGISTERED,
                TransferWorkerForegroundCleanup.END_RESERVED_TRANSFER,
            ),
            planTransferWorkerFailure(false, false),
        )
        assertEquals(
            TransferWorkerFailurePlan(
                TransferWorkerSenderCleanup.CANCEL_REGISTERED,
                TransferWorkerForegroundCleanup.END_REGISTERED_TRANSFER,
            ),
            planTransferWorkerFailure(true, false),
        )
    }
}
