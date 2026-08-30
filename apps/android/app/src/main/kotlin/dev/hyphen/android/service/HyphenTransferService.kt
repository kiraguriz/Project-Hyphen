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
                finishTransfer(TransferForegroundStopReason.DRAINED, startId)
                return START_NOT_STICKY
            }
            ACTION_ABORT -> {
                finishTransfer(TransferForegroundStopReason.FAILED, startId)
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
        CompanionConnectionPreferences.setTransferActive(this, true)
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
        if (outgoing && progress.isComplete) {
            finishTransfer(TransferForegroundStopReason.DRAINED)
        }
    }

    @TargetApi(35)
    override fun onTimeout(startId: Int, fgsType: Int) {
        if (fgsType and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC == 0) return
        val plan = transferStopPlan(TransferForegroundStopReason.TIMED_OUT)
        applyStopState(plan)
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

    override fun onDestroy() {
        handler.removeCallbacks(visibilityWatchdog)
        if (!stopping && CompanionConnectionPreferences.transferActive(this)) {
            applyStopState(transferStopPlan(TransferForegroundStopReason.INTERRUPTED))
        }
        supervisor.removeListener(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun finishTransfer(
        reason: TransferForegroundStopReason,
        startId: Int? = null,
    ) {
        val plan = transferStopPlan(reason)
        if (!plan.stopService) return
        applyStopState(plan)
        handler.removeCallbacks(visibilityWatchdog)
        if (promoted) stopForeground(STOP_FOREGROUND_REMOVE)
        promoted = false
        stopping = true
        if (startId == null) stopSelf() else stopSelf(startId)
    }

    private fun stopForInvisibleNotification() {
        val plan = CompanionServicePolicy.planTransferStop(
            reason = TransferForegroundStopReason.NOTIFICATION_HIDDEN,
            hasInFlightTransfers = hasForegroundTransferWork(),
            keepConnected = false,
        )
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
        CompanionConnectionPreferences.setTransferRetryRequired(this, plan.retryRequired)
        CompanionConnectionPreferences.setTransferActive(this, false)
        handler.removeCallbacks(visibilityWatchdog)
        if (promoted) stopForeground(STOP_FOREGROUND_REMOVE)
        promoted = false
        stopping = true
        stopSelf()
    }

    private fun transferStopPlan(reason: TransferForegroundStopReason): TransferForegroundStopPlan =
        CompanionServicePolicy.planTransferStop(
            reason = reason,
            hasInFlightTransfers = hasForegroundTransferWork(),
            keepConnected = CompanionConnectionPreferences.keepConnected(this),
        )

    private fun applyStopState(plan: TransferForegroundStopPlan) {
        if (plan.cancelOutstanding) {
            TransferForegroundOwnershipRuntime.coordinator.clear()
            supervisor.cancelOutboundTransfersForForegroundStop()
        }
        if (plan.pauseTransport) supervisor.pauseConnection()
        CompanionConnectionPreferences.setTransferRetryRequired(this, plan.retryRequired)
        CompanionConnectionPreferences.setTransferActive(this, false)
    }

    private fun hasForegroundTransferWork(): Boolean =
        TransferForegroundOwnershipRuntime.coordinator.hasForegroundWork(
            supervisor::hasInFlightOutboundTransfers,
        )

    companion object {
        const val ACTION_BEGIN = "dev.hyphen.android.action.BEGIN_USER_TRANSFER"
        const val ACTION_END = "dev.hyphen.android.action.END_USER_TRANSFER"
        const val ACTION_ABORT = "dev.hyphen.android.action.ABORT_USER_TRANSFER"
        private const val VISIBILITY_CHECK_MILLIS = 60_000L
    }
}
