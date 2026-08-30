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
        TransferForegroundOwnershipRuntime.lifecycle.transaction {
            TransferForegroundOwnershipRuntime.coordinator.clear()
            supervisor.cancelOutboundTransfersForForegroundStop()
            CompanionConnectionPreferences.setKeepConnected(context, false)
            CompanionConnectionPreferences.setTransferState(
                context,
                retryRequired = false,
                active = false,
            )
            supervisor.pauseConnection()
            context.stopService(Intent(context, HyphenConnectionService::class.java))
            context.stopService(Intent(context, HyphenTransferService::class.java))
        }
    }

    fun stopServicesAfterTrustChange(context: Context) {
        val supervisor = ConnectionSupervisor.getInstance(context.applicationContext)
        TransferForegroundOwnershipRuntime.lifecycle.transaction {
            TransferForegroundOwnershipRuntime.coordinator.clear()
            supervisor.cancelOutboundTransfersForForegroundStop()
            supervisor.pauseConnection()
            CompanionConnectionPreferences.setKeepConnected(context, false)
            CompanionConnectionPreferences.setTransferState(
                context,
                retryRequired = false,
                active = false,
            )
            context.stopService(Intent(context, HyphenConnectionService::class.java))
            context.stopService(Intent(context, HyphenTransferService::class.java))
        }
    }

    fun beginUserTransfer(context: Context): CompanionTransferStartResult {
        val visibility = notificationVisibilityResult(context)
        if (visibility != null) return CompanionTransferStartResult(visibility)
        val reservation = TransferForegroundOwnershipRuntime.lifecycle.transaction {
            TransferForegroundOwnershipRuntime.coordinator.reserve().also {
                CompanionConnectionPreferences.setTransferState(
                    context,
                    retryRequired = false,
                    active = true,
                )
            }
        }
        val result = startForeground(
            context,
            Intent(context, HyphenTransferService::class.java)
                .setAction(HyphenTransferService.ACTION_BEGIN),
        )
        if (result != CompanionServiceStartResult.STARTED) {
            val supervisor = ConnectionSupervisor.getInstance(context.applicationContext)
            TransferForegroundOwnershipRuntime.lifecycle.transaction {
                if (
                    TransferForegroundOwnershipRuntime.coordinator.release(reservation) &&
                    !hasForegroundTransferWork(supervisor)
                ) {
                    CompanionConnectionPreferences.setTransferActive(context, false)
                }
            }
            return CompanionTransferStartResult(result)
        }
        return CompanionTransferStartResult(result, reservation)
    }

    fun completeUserTransferRegistration(reservation: TransferForegroundReservation): Boolean =
        TransferForegroundOwnershipRuntime.lifecycle.transaction {
            TransferForegroundOwnershipRuntime.coordinator.release(reservation)
        }

    fun endUserTransfer(
        context: Context,
        reservation: TransferForegroundReservation? = null,
    ) {
        val expectedToken = TransferForegroundOwnershipRuntime.lifecycle.transaction {
            if (
                reservation != null &&
                !TransferForegroundOwnershipRuntime.coordinator.release(reservation)
            ) {
                null
            } else {
                CompanionConnectionPreferences.transferStateSnapshot(context).controlToken()
            }
        }
        expectedToken ?: return
        signalTransferService(context, HyphenTransferService.ACTION_END, expectedToken)
    }

    fun abortUserTransfers(context: Context) {
        val expectedToken = TransferForegroundOwnershipRuntime.lifecycle.transaction {
            TransferForegroundOwnershipRuntime.coordinator.clear()
            CompanionConnectionPreferences.transferStateSnapshot(context).controlToken()
        }
        signalTransferService(context, HyphenTransferService.ACTION_ABORT, expectedToken)
    }

    /**
     * Retires foreground ownership after a non-durable content source is lost.
     * This bypasses the normal drained path, which would clear the retry marker.
     */
    fun requireTransferSourceReselection(context: Context) {
        retireTransferForegroundOwnership(
            ownership = TransferForegroundOwnershipRuntime.coordinator,
            lifecycle = TransferForegroundOwnershipRuntime.lifecycle,
            publishRetryRequired = {
                CompanionConnectionPreferences.setTransferState(
                    context,
                    retryRequired = true,
                    active = false,
                )
            },
            stopTransferService = {
                context.stopService(Intent(context, HyphenTransferService::class.java))
            },
        )
    }

    private fun signalTransferService(
        context: Context,
        action: String,
        expectedToken: TransferStateControlToken,
    ) {
        try {
            context.startService(
                Intent(context, HyphenTransferService::class.java)
                    .setAction(action)
                    .putExtra(HyphenTransferService.EXTRA_STATE_REVISION, expectedToken.revision)
                    .putExtra(
                        HyphenTransferService.EXTRA_STATE_PROCESS_EPOCH,
                        expectedToken.processEpoch,
                    ),
            )
        } catch (_: SecurityException) {
            finishTransferWithoutService(context, action, expectedToken)
        } catch (_: RuntimeException) {
            finishTransferWithoutService(context, action, expectedToken)
        }
    }

    /** Equivalent fail-safe when Android refuses delivery to the running FGS. */
    private fun finishTransferWithoutService(
        context: Context,
        action: String,
        expectedToken: TransferStateControlToken,
    ) {
        if (!expectedToken.matches(CompanionConnectionPreferences.transferStateSnapshot(context))) {
            return
        }
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
        val applied = TransferForegroundOwnershipRuntime.lifecycle.transaction {
            if (!CompanionConnectionPreferences.applyTransferStopIfCurrent(
                    context,
                    expectedRevision = expectedToken.revision,
                    retryRequired = plan.retryRequired,
                    terminalEffects = {
                        if (plan.cancelOutstanding) {
                            TransferForegroundOwnershipRuntime.coordinator.clear()
                            supervisor.cancelOutboundTransfersForForegroundStop()
                        }
                        if (plan.pauseTransport) supervisor.pauseConnection()
                    },
                )
            ) {
                return@transaction false
            }
            context.stopService(Intent(context, HyphenTransferService::class.java))
            true
        }
        if (!applied) return
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

internal enum class TransferWorkerSenderCleanup {
    RETIRE_FOR_RESELECTION,
    CANCEL_REGISTERED,
}

internal enum class TransferWorkerForegroundCleanup {
    REQUIRE_RESELECTION,
    RELEASE_RESERVATION_AND_REQUIRE_RESELECTION,
    END_REGISTERED_TRANSFER,
    END_RESERVED_TRANSFER,
}

internal data class TransferWorkerFailurePlan(
    val senderCleanup: TransferWorkerSenderCleanup,
    val foregroundCleanup: TransferWorkerForegroundCleanup,
)

/** Pure decision seam guarding retry-marker ordering in the async send worker. */
internal fun planTransferWorkerFailure(
    registrationTransferred: Boolean,
    sourceRetryPending: Boolean,
): TransferWorkerFailurePlan = when {
    sourceRetryPending && registrationTransferred -> TransferWorkerFailurePlan(
        TransferWorkerSenderCleanup.RETIRE_FOR_RESELECTION,
        TransferWorkerForegroundCleanup.REQUIRE_RESELECTION,
    )
    sourceRetryPending -> TransferWorkerFailurePlan(
        TransferWorkerSenderCleanup.RETIRE_FOR_RESELECTION,
        TransferWorkerForegroundCleanup.RELEASE_RESERVATION_AND_REQUIRE_RESELECTION,
    )
    registrationTransferred -> TransferWorkerFailurePlan(
        TransferWorkerSenderCleanup.CANCEL_REGISTERED,
        TransferWorkerForegroundCleanup.END_REGISTERED_TRANSFER,
    )
    else -> TransferWorkerFailurePlan(
        TransferWorkerSenderCleanup.CANCEL_REGISTERED,
        TransferWorkerForegroundCleanup.END_RESERVED_TRANSFER,
    )
}

internal fun retireTransferForegroundOwnership(
    ownership: TransferForegroundOwnershipCoordinator,
    lifecycle: TransferForegroundLifecycleCoordinator,
    publishRetryRequired: () -> Unit,
    stopTransferService: () -> Unit,
) {
    lifecycle.transaction {
        ownership.clear()
        publishRetryRequired()
        stopTransferService()
    }
}

/** Prevent sender-monitor callbacks from acquiring foreground lifecycle state. */
internal fun dispatchCompletedOutboundTransfer(
    outgoing: Boolean,
    complete: Boolean,
    enqueue: (() -> Unit) -> Unit,
    completion: () -> Unit,
) {
    if (outgoing && complete) enqueue(completion)
}
