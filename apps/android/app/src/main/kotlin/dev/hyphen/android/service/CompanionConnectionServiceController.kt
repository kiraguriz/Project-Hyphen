package dev.hyphen.android.service

import android.content.Context
import android.content.Intent
import android.os.Build
import dev.hyphen.android.session.ConnectionSupervisor

enum class CompanionServiceStartResult {
    STARTED,
    DISABLED,
    NOTIFICATION_PERMISSION_REQUIRED,
    NOTIFICATIONS_DISABLED,
    START_NOT_ALLOWED,
    FAILED,
}

data class CompanionTransferStartResult(
    val serviceResult: CompanionServiceStartResult,
    val reservation: TransferForegroundReservation? = null,
)

object CompanionConnectionServiceController {
    fun notificationPermissionRequired(context: Context): Boolean =
        CompanionForegroundNotifications.notificationPermissionMissing(context)

    fun notificationsVisible(context: Context): Boolean =
        CompanionForegroundNotifications.canDisplay(context)

    fun keepConnected(context: Context): Boolean =
        CompanionConnectionPreferences.keepConnected(context)

    fun enablePersistentLink(context: Context): CompanionServiceStartResult {
        val visibility = notificationVisibilityResult(context)
        if (visibility != null) return visibility
        CompanionConnectionPreferences.setKeepConnected(context, true)
        val result = startForeground(
            context,
            Intent(context, HyphenConnectionService::class.java)
                .setAction(HyphenConnectionService.ACTION_ENABLE),
        )
        if (result != CompanionServiceStartResult.STARTED) {
            CompanionConnectionPreferences.setKeepConnected(context, false)
        }
        return result
    }

    fun startPersistentLinkIfEnabled(context: Context): CompanionServiceStartResult {
        if (!keepConnected(context)) return CompanionServiceStartResult.DISABLED
        val visibility = notificationVisibilityResult(context)
        if (visibility != null) {
            CompanionConnectionPreferences.setKeepConnected(context, false)
            return visibility
        }
        val result = startForeground(
            context,
            Intent(context, HyphenConnectionService::class.java)
                .setAction(HyphenConnectionService.ACTION_ENABLE),
        )
        if (result != CompanionServiceStartResult.STARTED) {
            CompanionConnectionPreferences.setKeepConnected(context, false)
        }
        return result
    }

    fun disconnect(context: Context) {
        val supervisor = ConnectionSupervisor.getInstance(context.applicationContext)
        TransferForegroundOwnershipRuntime.coordinator.clear()
        supervisor.cancelOutboundTransfersForForegroundStop()
        CompanionConnectionPreferences.setKeepConnected(context, false)
        CompanionConnectionPreferences.setTransferRetryRequired(context, false)
        CompanionConnectionPreferences.setTransferActive(context, false)
        supervisor.pauseConnection()
        context.stopService(Intent(context, HyphenConnectionService::class.java))
        context.stopService(Intent(context, HyphenTransferService::class.java))
    }

    fun stopServicesAfterTrustChange(context: Context) {
        val supervisor = ConnectionSupervisor.getInstance(context.applicationContext)
        TransferForegroundOwnershipRuntime.coordinator.clear()
        supervisor.cancelOutboundTransfersForForegroundStop()
        supervisor.pauseConnection()
        CompanionConnectionPreferences.setKeepConnected(context, false)
        CompanionConnectionPreferences.setTransferRetryRequired(context, false)
        CompanionConnectionPreferences.setTransferActive(context, false)
        context.stopService(Intent(context, HyphenConnectionService::class.java))
        context.stopService(Intent(context, HyphenTransferService::class.java))
    }

    fun beginUserTransfer(context: Context): CompanionTransferStartResult {
        val visibility = notificationVisibilityResult(context)
        if (visibility != null) return CompanionTransferStartResult(visibility)
        val reservation = TransferForegroundOwnershipRuntime.coordinator.reserve()
        CompanionConnectionPreferences.setTransferRetryRequired(context, false)
        CompanionConnectionPreferences.setTransferActive(context, true)
        val result = startForeground(
            context,
            Intent(context, HyphenTransferService::class.java)
                .setAction(HyphenTransferService.ACTION_BEGIN),
        )
        if (result != CompanionServiceStartResult.STARTED) {
            TransferForegroundOwnershipRuntime.coordinator.release(reservation)
            val supervisor = ConnectionSupervisor.getInstance(context.applicationContext)
            if (!hasForegroundTransferWork(supervisor)) {
                CompanionConnectionPreferences.setTransferActive(context, false)
            }
            return CompanionTransferStartResult(result)
        }
        return CompanionTransferStartResult(result, reservation)
    }

    fun completeUserTransferRegistration(reservation: TransferForegroundReservation): Boolean =
        TransferForegroundOwnershipRuntime.coordinator.release(reservation)

    fun endUserTransfer(
        context: Context,
        reservation: TransferForegroundReservation? = null,
    ) {
        if (reservation != null &&
            !TransferForegroundOwnershipRuntime.coordinator.release(reservation)
        ) {
            return
        }
        signalTransferService(context, HyphenTransferService.ACTION_END)
    }

    fun abortUserTransfers(context: Context) {
        TransferForegroundOwnershipRuntime.coordinator.clear()
        signalTransferService(context, HyphenTransferService.ACTION_ABORT)
    }

    private fun signalTransferService(context: Context, action: String) {
        try {
            context.startService(
                Intent(context, HyphenTransferService::class.java).setAction(action),
            )
        } catch (_: SecurityException) {
            finishTransferWithoutService(context, action)
        } catch (_: RuntimeException) {
            finishTransferWithoutService(context, action)
        }
    }

    /** Equivalent fail-safe when Android refuses delivery to the running FGS. */
    private fun finishTransferWithoutService(context: Context, action: String) {
        val supervisor = ConnectionSupervisor.getInstance(context.applicationContext)
        val reason = if (action == HyphenTransferService.ACTION_END) {
            TransferForegroundStopReason.DRAINED
        } else {
            TransferForegroundStopReason.FAILED
        }
        val plan = CompanionServicePolicy.planTransferStop(
            reason = reason,
            hasInFlightTransfers = hasForegroundTransferWork(supervisor),
            keepConnected = CompanionConnectionPreferences.keepConnected(context),
        )
        if (!plan.stopService) return
        if (plan.cancelOutstanding) {
            TransferForegroundOwnershipRuntime.coordinator.clear()
            supervisor.cancelOutboundTransfersForForegroundStop()
        }
        if (plan.pauseTransport) supervisor.pauseConnection()
        CompanionConnectionPreferences.setTransferRetryRequired(context, plan.retryRequired)
        CompanionConnectionPreferences.setTransferActive(context, false)
        context.stopService(Intent(context, HyphenTransferService::class.java))
    }

    private fun hasForegroundTransferWork(supervisor: ConnectionSupervisor): Boolean =
        TransferForegroundOwnershipRuntime.coordinator.hasForegroundWork(
            supervisor::hasInFlightOutboundTransfers,
        )

    private fun notificationVisibilityResult(context: Context): CompanionServiceStartResult? {
        CompanionForegroundNotifications.ensureChannel(context)
        if (notificationPermissionRequired(context)) {
            return CompanionServiceStartResult.NOTIFICATION_PERMISSION_REQUIRED
        }
        if (!notificationsVisible(context)) {
            return CompanionServiceStartResult.NOTIFICATIONS_DISABLED
        }
        return null
    }

    private fun startForeground(context: Context, intent: Intent): CompanionServiceStartResult =
        try {
            context.startForegroundService(intent)
            CompanionServiceStartResult.STARTED
        } catch (_: SecurityException) {
            CompanionServiceStartResult.FAILED
        } catch (error: RuntimeException) {
            // Avoid a verifier-time reference to the API-31 exception class on
            // the API-29/30 floor. Older releases report the same restriction
            // as IllegalStateException.
            if (
                error is IllegalStateException ||
                (Build.VERSION.SDK_INT >= 31 &&
                    error.javaClass.name == "android.app.ForegroundServiceStartNotAllowedException")
            ) CompanionServiceStartResult.START_NOT_ALLOWED
            else CompanionServiceStartResult.FAILED
        }
}
