package dev.hyphen.android.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import dev.hyphen.android.session.ConnectionSupervisor
import dev.hyphen.android.transfer.TransferCheckpointStore
import dev.hyphen.android.ui.ActivityEvent
import dev.hyphen.android.ui.ConnectionState

class HyphenConnectionService : Service(), ConnectionSupervisor.Listener {
    private lateinit var supervisor: ConnectionSupervisor
    private val handler = Handler(Looper.getMainLooper())
    private var notificationState = CompanionNotificationState.CONNECTING
    private var promoted = false
    private var destroyed = false

    private val visibilityWatchdog = object : Runnable {
        override fun run() {
            if (!CompanionForegroundNotifications.canDisplay(this@HyphenConnectionService)) {
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
        supervisor.configureTransferCheckpoints(
            TransferCheckpointStore(TransferCheckpointStore.defaultRoot(applicationContext)),
        )
        supervisor.addListener(this)
        // Capture a pre-existing singleton state for notification rendering;
        // no background transport is resumed before onStartCommand accepts
        // the explicit persistent-link action and promotes this service.
        supervisor.replayStateTo(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            TransferForegroundOwnershipRuntime.coordinator.clear()
            supervisor.cancelOutboundTransfersForForegroundStop()
            CompanionConnectionPreferences.setKeepConnected(this, false)
            CompanionConnectionPreferences.setTransferRetryRequired(this, false)
            CompanionConnectionPreferences.setTransferActive(this, false)
            supervisor.pauseConnection()
            stopService(Intent(this, HyphenTransferService::class.java))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!CompanionConnectionPreferences.keepConnected(this)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!CompanionForegroundNotifications.canDisplay(this)) {
            stopForInvisibleNotification()
            return START_NOT_STICKY
        }

        promote(notificationState)
        if (!supervisor.resumeConnection()) {
            CompanionConnectionPreferences.setKeepConnected(this, false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        supervisor.replayStateTo(this)
        handler.removeCallbacks(visibilityWatchdog)
        handler.postDelayed(visibilityWatchdog, VISIBILITY_CHECK_MILLIS)
        return START_STICKY
    }

    override fun onActivityEvent(event: ActivityEvent) {
        if (event !is ActivityEvent.ConnectionChanged) return
        handler.post {
            if (destroyed) return@post
            handleConnectionChanged(event)
        }
    }

    private fun handleConnectionChanged(event: ActivityEvent.ConnectionChanged) {
        notificationState = when (event.state) {
            ConnectionState.CONNECTED -> CompanionNotificationState.CONNECTED
            ConnectionState.DEGRADED -> CompanionNotificationState.DEGRADED
            ConnectionState.SUSPENDED,
            ConnectionState.SLEEPING,
            -> CompanionNotificationState.PAUSED
            else -> CompanionNotificationState.CONNECTING
        }
        if (promoted) promote(notificationState)
    }

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacks(visibilityWatchdog)
        supervisor.removeListener(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun promote(state: CompanionNotificationState) {
        startForeground(
            CompanionForegroundNotifications.CONNECTION_NOTIFICATION_ID,
            CompanionForegroundNotifications.connection(this, state),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        promoted = true
    }

    private fun stopForInvisibleNotification() {
        TransferForegroundOwnershipRuntime.coordinator.clear()
        supervisor.cancelOutboundTransfersForForegroundStop()
        CompanionConnectionPreferences.setKeepConnected(this, false)
        if (CompanionConnectionPreferences.transferActive(this)) {
            CompanionConnectionPreferences.setTransferActive(this, false)
            CompanionConnectionPreferences.setTransferRetryRequired(this, true)
            stopService(Intent(this, HyphenTransferService::class.java))
        }
        supervisor.pauseConnection()
        if (promoted) stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val ACTION_ENABLE = "dev.hyphen.android.action.ENABLE_COMPANION_LINK"
        const val ACTION_DISCONNECT = "dev.hyphen.android.action.DISCONNECT_COMPANION_LINK"
        private const val VISIBILITY_CHECK_MILLIS = 60_000L
    }
}
