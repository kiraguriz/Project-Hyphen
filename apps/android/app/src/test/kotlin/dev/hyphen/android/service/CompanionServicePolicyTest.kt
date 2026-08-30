package dev.hyphen.android.service

import dev.hyphen.android.ui.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionServicePolicyTest {
    @Test
    fun `persistent link owns only connected-device foreground work`() {
        val state = CompanionServicePolicy.reduce(
            CompanionServiceState(),
            CompanionServiceCommand.EnablePersistentLink,
        )

        assertTrue(state.shouldRun)
        assertEquals(setOf(CompanionForegroundKind.CONNECTED_DEVICE), state.foregroundKinds)
        assertEquals(CompanionNotificationState.CONNECTING, state.notificationState)
    }

    @Test
    fun `user transfer temporarily adds and then removes data-sync`() {
        val persistent = CompanionServicePolicy.reduce(
            CompanionServiceState(),
            CompanionServiceCommand.EnablePersistentLink,
        )
        val transferring = CompanionServicePolicy.reduce(
            persistent,
            CompanionServiceCommand.BeginUserTransfer,
        )
        val completed = CompanionServicePolicy.reduce(
            transferring,
            CompanionServiceCommand.EndUserTransfer,
        )

        assertEquals(
            setOf(CompanionForegroundKind.CONNECTED_DEVICE, CompanionForegroundKind.DATA_SYNC),
            transferring.foregroundKinds,
        )
        assertEquals(CompanionNotificationState.TRANSFERRING, transferring.notificationState)
        assertTrue(completed.shouldRun)
        assertEquals(setOf(CompanionForegroundKind.CONNECTED_DEVICE), completed.foregroundKinds)
    }

    @Test
    fun `transient transfer stops service when persistent link is disabled`() {
        val transferring = CompanionServicePolicy.reduce(
            CompanionServiceState(),
            CompanionServiceCommand.BeginUserTransfer,
        )
        val completed = CompanionServicePolicy.reduce(
            transferring,
            CompanionServiceCommand.EndUserTransfer,
        )

        assertTrue(transferring.shouldRun)
        assertEquals(setOf(CompanionForegroundKind.DATA_SYNC), transferring.foregroundKinds)
        assertFalse(completed.shouldRun)
    }

    @Test
    fun `link events never erase active transfer ownership`() {
        val transferring = CompanionServicePolicy.reduce(
            CompanionServicePolicy.reduce(
                CompanionServiceState(),
                CompanionServiceCommand.BeginUserTransfer,
            ),
            CompanionServiceCommand.LinkChanged(ConnectionState.RECONNECTING),
        )

        assertTrue(transferring.transferActive)
        assertEquals(CompanionNotificationState.TRANSFERRING, transferring.notificationState)
    }

    @Test
    fun `data-sync timeout stops transfer service and requires explicit retry`() {
        val transferring = CompanionServicePolicy.reduce(
            CompanionServicePolicy.reduce(
                CompanionServiceState(linkState = ConnectionState.CONNECTED),
                CompanionServiceCommand.EnablePersistentLink,
            ),
            CompanionServiceCommand.BeginUserTransfer,
        )
        val timedOut = CompanionServicePolicy.reduce(
            transferring,
            CompanionServiceCommand.DataSyncTimedOut,
        )

        assertTrue(timedOut.shouldRun)
        assertTrue(timedOut.connectionServiceRequired)
        assertFalse(timedOut.transferServiceRequired)
        assertTrue(timedOut.retryRequired)
        assertEquals(ConnectionState.CONNECTED, timedOut.linkState)
        assertEquals(CompanionNotificationState.RETRY_REQUIRED, timedOut.notificationState)
        assertFalse(CompanionServicePolicy.shouldPauseTransportAfterTransferStops(timedOut.keepConnected))
        assertTrue(CompanionServicePolicy.shouldPauseTransportAfterTransferStops(keepConnected = false))
    }

    @Test
    fun `normal completion keeps foreground ownership while another transfer is active`() {
        val plan = CompanionServicePolicy.planTransferStop(
            reason = TransferForegroundStopReason.DRAINED,
            hasInFlightTransfers = true,
            keepConnected = false,
        )

        assertFalse(plan.stopService)
        assertFalse(plan.cancelOutstanding)
        assertFalse(plan.pauseTransport)
    }

    @Test
    fun `last completed transfer releases foreground ownership and pauses an unowned link`() {
        val plan = CompanionServicePolicy.planTransferStop(
            reason = TransferForegroundStopReason.DRAINED,
            hasInFlightTransfers = false,
            keepConnected = false,
        )

        assertTrue(plan.stopService)
        assertFalse(plan.cancelOutstanding)
        assertFalse(plan.retryRequired)
        assertTrue(plan.pauseTransport)
    }

    @Test
    fun `failed transfer cancels all outbound work before releasing foreground ownership`() {
        val plan = CompanionServicePolicy.planTransferStop(
            reason = TransferForegroundStopReason.FAILED,
            hasInFlightTransfers = true,
            keepConnected = true,
        )

        assertTrue(plan.stopService)
        assertTrue(plan.cancelOutstanding)
        assertTrue(plan.retryRequired)
        assertFalse(plan.pauseTransport)
    }

    @Test
    fun `explicit disconnect wins over every active mode`() {
        val active = CompanionServiceState(
            keepConnected = true,
            transferActive = true,
            linkState = ConnectionState.CONNECTED,
        )
        val stopped = CompanionServicePolicy.reduce(active, CompanionServiceCommand.Disconnect)

        assertFalse(stopped.shouldRun)
        assertFalse(stopped.transferActive)
        assertFalse(stopped.keepConnected)
    }
}
