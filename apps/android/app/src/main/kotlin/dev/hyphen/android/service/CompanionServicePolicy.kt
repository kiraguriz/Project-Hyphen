package dev.hyphen.android.service

import dev.hyphen.android.ui.ConnectionState

enum class CompanionForegroundKind {
    CONNECTED_DEVICE,
    DATA_SYNC,
}

enum class CompanionNotificationState {
    CONNECTING,
    CONNECTED,
    DEGRADED,
    PAUSED,
    TRANSFERRING,
    RETRY_REQUIRED,
}

data class CompanionServiceState(
    val keepConnected: Boolean = false,
    val transferActive: Boolean = false,
    val linkState: ConnectionState = ConnectionState.RECONNECTING,
    val retryRequired: Boolean = false,
) {
    val shouldRun: Boolean
        get() = keepConnected || transferActive

    val connectionServiceRequired: Boolean
        get() = keepConnected

    val transferServiceRequired: Boolean
        get() = transferActive

    val foregroundKinds: Set<CompanionForegroundKind>
        get() = buildSet {
            if (keepConnected) add(CompanionForegroundKind.CONNECTED_DEVICE)
            if (transferActive) add(CompanionForegroundKind.DATA_SYNC)
        }

    val notificationState: CompanionNotificationState
        get() = when {
            retryRequired -> CompanionNotificationState.RETRY_REQUIRED
            transferActive -> CompanionNotificationState.TRANSFERRING
            linkState == ConnectionState.CONNECTED -> CompanionNotificationState.CONNECTED
            linkState == ConnectionState.DEGRADED -> CompanionNotificationState.DEGRADED
            linkState == ConnectionState.SUSPENDED || linkState == ConnectionState.SLEEPING ->
                CompanionNotificationState.PAUSED
            else -> CompanionNotificationState.CONNECTING
        }
}

sealed interface CompanionServiceCommand {
    data object EnablePersistentLink : CompanionServiceCommand
    data object DisablePersistentLink : CompanionServiceCommand
    data object BeginUserTransfer : CompanionServiceCommand
    data object EndUserTransfer : CompanionServiceCommand
    data object DataSyncTimedOut : CompanionServiceCommand
    data object Disconnect : CompanionServiceCommand
    data class LinkChanged(val state: ConnectionState) : CompanionServiceCommand
}

enum class TransferForegroundStopReason {
    DRAINED,
    FAILED,
    INTERRUPTED,
    TIMED_OUT,
    NOTIFICATION_HIDDEN,
}

data class TransferForegroundStopPlan(
    val stopService: Boolean,
    val cancelOutstanding: Boolean,
    val retryRequired: Boolean,
    val pauseTransport: Boolean,
)

object CompanionServicePolicy {
    fun reduce(
        state: CompanionServiceState,
        command: CompanionServiceCommand,
    ): CompanionServiceState = when (command) {
        CompanionServiceCommand.EnablePersistentLink ->
            state.copy(keepConnected = true, retryRequired = false)
        CompanionServiceCommand.DisablePersistentLink ->
            state.copy(keepConnected = false)
        CompanionServiceCommand.BeginUserTransfer ->
            state.copy(transferActive = true, retryRequired = false)
        CompanionServiceCommand.EndUserTransfer ->
            state.copy(transferActive = false)
        CompanionServiceCommand.DataSyncTimedOut ->
            state.copy(transferActive = false, retryRequired = true)
        CompanionServiceCommand.Disconnect ->
            state.copy(keepConnected = false, transferActive = false, retryRequired = false)
        is CompanionServiceCommand.LinkChanged ->
            state.copy(linkState = command.state)
    }

    fun shouldPauseTransportAfterTransferStops(keepConnected: Boolean): Boolean =
        !keepConnected

    fun planTransferStop(
        reason: TransferForegroundStopReason,
        hasInFlightTransfers: Boolean,
        keepConnected: Boolean,
    ): TransferForegroundStopPlan {
        if (reason == TransferForegroundStopReason.DRAINED && hasInFlightTransfers) {
            return TransferForegroundStopPlan(
                stopService = false,
                cancelOutstanding = false,
                retryRequired = false,
                pauseTransport = false,
            )
        }
        val failed = reason != TransferForegroundStopReason.DRAINED
        return TransferForegroundStopPlan(
            stopService = true,
            cancelOutstanding = failed,
            retryRequired = failed,
            pauseTransport = !keepConnected,
        )
    }
}
