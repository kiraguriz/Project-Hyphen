package dev.hyphen.android.service

import android.annotation.TargetApi
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import dev.hyphen.android.session.ConnectionSupervisor
import dev.hyphen.android.transfer.TransferProgress

class HyphenTransferService : Service(), ConnectionSupervisor.Listener {
    private lateinit var supervisor: ConnectionSupervisor
    private val handler = Handler(Looper.getMainLooper())
    private var promoted = false
    private var stopping = false
    @Volatile
    private var destroyed = false

    private val visibilityWatchdog = object : Runnable {
        override fun run() {
            if (!CompanionForegroundNotifications.canDisplay(this@HyphenTransferService)) {
                stopForInvisibleNotification()
                return
            }
            handler.postDelayed(this, VISIBILITY_CHECK_MILLIS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        CompanionForegroundNotifications.ensureChannel(this)
        supervisor = ConnectionSupervisor.getInstance(applicationContext)
        supervisor.addListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_END -> {
                val token = intent.stateControlTokenOrNull() ?: return START_NOT_STICKY
                finishTransfer(
                    TransferForegroundStopReason.DRAINED,
                    startId,
                    token,
                )
                return START_NOT_STICKY
            }
            ACTION_ABORT -> {
                val token = intent.stateControlTokenOrNull() ?: return START_NOT_STICKY
                finishTransfer(
                    TransferForegroundStopReason.FAILED,
                    startId,
                    token,
                )
                return START_NOT_STICKY
            }
            ACTION_BEGIN -> Unit
            else -> {
                if (CompanionConnectionPreferences.transferActive(this)) {
                    finishTransfer(TransferForegroundStopReason.INTERRUPTED, startId)
                } else {
                    stopping = true
                    stopSelf(startId)
                }
                return START_NOT_STICKY
            }
        }
        stopping = false
        if (!CompanionConnectionPreferences.transferActive(this)) {
            stopping = true
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!CompanionForegroundNotifications.canDisplay(this)) {
            stopForInvisibleNotification()
            return START_NOT_STICKY
        }
        startForeground(
            CompanionForegroundNotifications.TRANSFER_NOTIFICATION_ID,
            CompanionForegroundNotifications.transfer(this),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        promoted = true
        // The worker may register and fully drain before this Service reaches
        // onStartCommand (for example, a zero-byte local transfer). Do not
        // depend solely on a progress callback that predates listener attach.
        if (!hasForegroundTransferWork()) {
            finishTransfer(TransferForegroundStopReason.DRAINED, startId)
            return START_NOT_STICKY
        }
        handler.removeCallbacks(visibilityWatchdog)
        handler.postDelayed(visibilityWatchdog, VISIBILITY_CHECK_MILLIS)
        return START_NOT_STICKY
    }

    override fun onTransferProgress(progress: TransferProgress, outgoing: Boolean) {
        dispatchCompletedOutboundTransfer(
            outgoing = outgoing,
            complete = progress.isComplete,
            enqueue = { callback -> handler.post(callback) },
        ) {
            if (!destroyed) finishTransfer(TransferForegroundStopReason.DRAINED)
        }
    }

    @TargetApi(35)
    override fun onTimeout(startId: Int, fgsType: Int) {
        if (fgsType and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC == 0) return
        TransferForegroundOwnershipRuntime.lifecycle.transaction {
            val plan = transferStopPlan(TransferForegroundStopReason.TIMED_OUT)
            // A platform timeout is authoritative for this whole FGS lifetime
            // and must terminate promptly even if another mutation races it.
            applyStopEffects(plan)
            CompanionConnectionPreferences.setTransferState(
                this,
                retryRequired = plan.retryRequired,
                active = false,
            )
            if (promoted) stopForeground(STOP_FOREGROUND_DETACH)
            runCatching {
                getSystemService(NotificationManager::class.java).notify(
                    CompanionForegroundNotifications.TRANSFER_NOTIFICATION_ID,
                    CompanionForegroundNotifications.transferRetryRequired(this),
                )
            }
            promoted = false
            stopping = true
            stopSelf(startId)
        }
    }

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacks(visibilityWatchdog)
        val state = CompanionConnectionPreferences.transferStateSnapshot(this)
        if (!stopping && state.active) {
            applyStopState(
                transferStopPlan(TransferForegroundStopReason.INTERRUPTED),
                state.revision,
            )
        }
        supervisor.removeListener(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun finishTransfer(
        reason: TransferForegroundStopReason,
        startId: Int? = null,
        queuedToken: TransferStateControlToken? = null,
    ) {
        TransferForegroundOwnershipRuntime.lifecycle.transaction {
            val state = CompanionConnectionPreferences.transferStateSnapshot(this)
            if (queuedToken != null && !queuedToken.matches(state)) return@transaction
            val plan = transferStopPlan(reason)
            if (!plan.stopService) return@transaction
            if (!applyStopState(plan, state.revision)) return@transaction
            handler.removeCallbacks(visibilityWatchdog)
            if (promoted) stopForeground(STOP_FOREGROUND_REMOVE)
            promoted = false
            stopping = true
            if (startId == null) stopSelf() else stopSelf(startId)
        }
    }

    private fun stopForInvisibleNotification() {
        val plan = CompanionServicePolicy.planTransferStop(
            reason = TransferForegroundStopReason.NOTIFICATION_HIDDEN,
            hasInFlightTransfers = hasForegroundTransferWork(),
            keepConnected = false,
        )
        TransferForegroundOwnershipRuntime.lifecycle.transaction {
            if (plan.cancelOutstanding) {
                TransferForegroundOwnershipRuntime.coordinator.clear()
                supervisor.cancelOutboundTransfersForForegroundStop()
            }
            // If this service cannot display its notification, the connection
            // service cannot satisfy Hyphen's transparency policy either. Stop
            // both owners immediately instead of waiting for its next watchdog.
            if (CompanionConnectionPreferences.keepConnected(this)) {
                CompanionConnectionPreferences.setKeepConnected(this, false)
                stopService(Intent(this, HyphenConnectionService::class.java))
            }
            if (plan.pauseTransport) supervisor.pauseConnection()
            CompanionConnectionPreferences.setTransferState(
                this,
                retryRequired = plan.retryRequired,
                active = false,
            )
            handler.removeCallbacks(visibilityWatchdog)
            if (promoted) stopForeground(STOP_FOREGROUND_REMOVE)
            promoted = false
            stopping = true
            stopSelf()
        }
    }

    private fun transferStopPlan(reason: TransferForegroundStopReason): TransferForegroundStopPlan =
        CompanionServicePolicy.planTransferStop(
            reason = reason,
            hasInFlightTransfers = hasForegroundTransferWork(),
            keepConnected = CompanionConnectionPreferences.keepConnected(this),
        )

    private fun applyStopState(
        plan: TransferForegroundStopPlan,
        expectedRevision: Long,
    ): Boolean = TransferForegroundOwnershipRuntime.lifecycle.transaction {
        if (!CompanionConnectionPreferences.applyTransferStopIfCurrent(
                this,
                expectedRevision = expectedRevision,
                retryRequired = plan.retryRequired,
                terminalEffects = { applyStopEffects(plan) },
            )
        ) {
            return@transaction false
        }
        true
    }

    private fun applyStopEffects(plan: TransferForegroundStopPlan) {
        if (plan.cancelOutstanding) {
            TransferForegroundOwnershipRuntime.coordinator.clear()
            supervisor.cancelOutboundTransfersForForegroundStop()
        }
        if (plan.pauseTransport) supervisor.pauseConnection()
    }

    private fun hasForegroundTransferWork(): Boolean =
        TransferForegroundOwnershipRuntime.coordinator.hasForegroundWork(
            supervisor::hasInFlightOutboundTransfers,
        )

    companion object {
        const val ACTION_BEGIN = "dev.hyphen.android.action.BEGIN_USER_TRANSFER"
        const val ACTION_END = "dev.hyphen.android.action.END_USER_TRANSFER"
        const val ACTION_ABORT = "dev.hyphen.android.action.ABORT_USER_TRANSFER"
        const val EXTRA_STATE_REVISION = "dev.hyphen.android.extra.TRANSFER_STATE_REVISION"
        const val EXTRA_STATE_PROCESS_EPOCH =
            "dev.hyphen.android.extra.TRANSFER_STATE_PROCESS_EPOCH"
        private const val VISIBILITY_CHECK_MILLIS = 60_000L
    }

    private fun Intent.stateControlTokenOrNull(): TransferStateControlToken? =
        if (hasExtra(EXTRA_STATE_REVISION) && hasExtra(EXTRA_STATE_PROCESS_EPOCH)) {
            TransferStateControlToken(
                processEpoch = getStringExtra(EXTRA_STATE_PROCESS_EPOCH) ?: return null,
                revision = getLongExtra(EXTRA_STATE_REVISION, Long.MIN_VALUE),
            )
        } else {
            null
        }
}
