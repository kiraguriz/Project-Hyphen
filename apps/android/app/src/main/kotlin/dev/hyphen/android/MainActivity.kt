package dev.hyphen.android

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import dev.hyphen.android.companion.AssociationController
import dev.hyphen.android.companion.AssociationAttemptCompletion
import dev.hyphen.android.companion.AssociationAttemptRuntime
import dev.hyphen.android.companion.AssociationAttemptToken
import dev.hyphen.android.companion.AssociationEvent
import dev.hyphen.android.companion.AssociationRemovalRequest
import dev.hyphen.android.companion.CdmAssociationBackend
import dev.hyphen.android.companion.CompanionAssociationBindings
import dev.hyphen.android.companion.SharedPreferencesAssociationBindingStorage
import dev.hyphen.android.diagnostics.DiagnosticsRuntime
import dev.hyphen.android.diagnostics.RedactedDiagnosticsExporter
import dev.hyphen.android.discovery.AndroidMulticastLockHandle
import dev.hyphen.android.discovery.AndroidNsdBackend
import dev.hyphen.android.discovery.DiscoveryEvent
import dev.hyphen.android.discovery.DiscoveryFailure
import dev.hyphen.android.discovery.DiscoveryManager
import dev.hyphen.android.discovery.DiscoveredService
import dev.hyphen.android.discovery.HandlerScheduler
import dev.hyphen.android.discovery.ScopedMulticastLock
import dev.hyphen.android.notifications.HyphenNotificationListenerRuntime
import dev.hyphen.android.notifications.NotificationCapabilityGate
import dev.hyphen.android.notifications.NotificationAccessController
import dev.hyphen.android.notifications.NotificationDismissRequestHandler
import dev.hyphen.android.notifications.NotificationPrivacyMode
import dev.hyphen.android.notifications.NotificationPrivacyPolicyHandler
import dev.hyphen.android.notifications.NotificationProtocol
import dev.hyphen.android.notifications.NotificationReplyRequestHandler
import dev.hyphen.android.notifications.ProtocolSessionNotificationOutbox
import dev.hyphen.android.pairing.EndpointParser
import dev.hyphen.android.pairing.PairingAttemptRuntime
import dev.hyphen.android.pairing.PairingAttemptToken
import dev.hyphen.android.pairing.PairingCommit
import dev.hyphen.android.pairing.PairingIntentRoute
import dev.hyphen.android.pairing.PairingIntentRouter
import dev.hyphen.android.pairing.PairingTranscript
import dev.hyphen.android.pairing.PairingWireProtocol
import dev.hyphen.android.pairing.ParseResult
import dev.hyphen.android.pairing.ParsedEndpoint
import dev.hyphen.android.pairing.SasConfirmationGate
import dev.hyphen.android.service.CompanionConnectionPreferences
import dev.hyphen.android.service.CompanionConnectionServiceController
import dev.hyphen.android.service.CompanionServiceStartResult
import dev.hyphen.android.session.ConnectionSupervisor
import dev.hyphen.android.text.ProtocolSessionTextLinkOutbox
import dev.hyphen.android.text.TextLinkConfirmationRequest
import dev.hyphen.android.text.TextLinkKind
import dev.hyphen.android.text.TextLinkMessage
import dev.hyphen.android.text.TextLinkReceiver
import dev.hyphen.android.text.TextLinkSender
import dev.hyphen.android.transfer.ProtocolSessionTransferOutbox
import dev.hyphen.android.transfer.TransferCancel
import dev.hyphen.android.transfer.TransferCompleted
import dev.hyphen.android.transfer.TransferEvent
import dev.hyphen.android.transfer.FileTransferStorage
import dev.hyphen.android.transfer.TransferCheckpointStore
import dev.hyphen.android.transfer.ReceivedFileDelivery
import dev.hyphen.android.transfer.StreamTransferByteSource
import dev.hyphen.android.transfer.TransferProgress
import dev.hyphen.android.transfer.TransferProtocol
import dev.hyphen.android.transfer.TransferReceiver
import dev.hyphen.android.transfer.TransferResumeInfo
import dev.hyphen.android.transfer.TransferSender
import dev.hyphen.android.transport.AndroidKeystoreTlsIdentity
import dev.hyphen.android.transport.Envelope
import dev.hyphen.android.transport.HeartbeatMonitor
import dev.hyphen.android.transport.Json
import dev.hyphen.android.transport.ProtocolSession
import dev.hyphen.android.transport.SessionHandshake
import dev.hyphen.android.transport.TlsClient
import dev.hyphen.android.transport.Ulid
import dev.hyphen.android.trust.AndroidTrustStores
import dev.hyphen.android.trust.TrustedPeer
import dev.hyphen.android.ui.ActivityEvent
import dev.hyphen.android.ui.ConnectionState
import dev.hyphen.android.ui.HyphenActions
import dev.hyphen.android.ui.HyphenHome
import dev.hyphen.android.ui.HyphenUiModel
import dev.hyphen.android.ui.TextDirection
import dev.hyphen.android.ui.TextKind
import dev.hyphen.android.ui.TransferDirection
import dev.hyphen.android.ui.theme.HyphenTheme
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocket

// Compose host + controller (frontend UX plan Track B). The UI is Jetpack
// Compose bound to `HyphenUiModel` (the Phase-0 observable model); this Activity
// keeps the backend wiring (discovery, pairing/SAS, session, transfer, text,
// notifications, diagnostics) and feeds the model structured `ActivityEvent`s.
// The previous hand-built View hierarchy and its hardcoded timeline / fake
// "已连接 · 延迟 18ms" summary are gone — the timeline and connection header now
// render real state.
class MainActivity : ComponentActivity() {

    private enum class PendingNotificationAction { ENABLE_COMPANION, PICK_FILE }

    private var manager: DiscoveryManager? = null
    private var discoveryActive = false
    private val discoveredServices = linkedMapOf<String, DiscoveredService>()
    private val pairingAttemptToken = AtomicReference<PairingAttemptToken?>()
    private var consumedPairingIntentToken: String? = null
    private var pendingNotificationAction: PendingNotificationAction? = null
    private val supervisor by lazy { ConnectionSupervisor.getInstance(this) }
    private val textReceiver = TextLinkReceiver()
    private val diagnosticLogs = DiagnosticsRuntime.forActivityActions()
    private val associationController by lazy {
        AssociationController(CdmAssociationBackend(applicationContext), ::renderCdm)
    }
    private val associationBindings by lazy {
        CompanionAssociationBindings(
            SharedPreferencesAssociationBindingStorage(applicationContext),
        )
    }
    private val associationApprovalListener: () -> Unit = { launchPendingAssociationApproval() }
    private var lastTransferProgress: TransferProgress? = null
    // Empty until events arrive; the empty log card renders the localized
    // R.string.log_empty placeholder instead of a hardcoded seed line.
    private val logBuffer = BoundedLineBuffer(MAX_LOG_LINES)
    private val workerLock = Any()
    private val activeWorkers = mutableSetOf<Thread>()
    @Volatile
    private var activityDestroyed = false
    private val model = HyphenUiModel()
    private val checkpointStore by lazy {
        TransferCheckpointStore(TransferCheckpointStore.defaultRoot(applicationContext))
    }
    private val transferReceiver by lazy {
        TransferReceiver(
            FileTransferStorage(
                File(cacheDir, "transfers"),
                isCheckpointActive = { fileId -> checkpointStore.load(fileId) != null },
            ),
            checkpointStore = checkpointStore,
        ) { progress ->
            updateLastTransferProgress(progress)
            append(transferProgressLine(progress))
            emit(transferProgressEvent(progress, TransferDirection.INCOMING))
        }
    }
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = pendingNotificationAction
            pendingNotificationAction = null
            if (!granted) {
                append("foreground notification permission denied")
                showCompanionNotificationSettings()
                return@registerForActivityResult
            }
            when (action) {
                PendingNotificationAction.ENABLE_COMPANION -> enableCompanionConnection()
                PendingNotificationAction.PICK_FILE -> enableCompanionForFilePicker()
                null -> Unit
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consumedPairingIntentToken = savedInstanceState?.getString(STATE_CONSUMED_PAIRING_TOKEN)
        supervisor.configureTransferCheckpoints(checkpointStore)
        supervisor.addListener(sessionListener)
        supervisor.replayStateTo(sessionListener)
        bindTransferPersistenceIfConnected()
        refreshPairingState()
        model.setBetaDiagnostics(betaDiagnosticsEnabled())
        model.updateNotificationPrivacyLabel(notificationPrivacyStatus(HyphenNotificationListenerRuntime.notificationPrivacyMode()))
        model.setNotificationAccess(NotificationAccessController.forContext(this).status().enabled)
        model.setCompanionConnection(CompanionConnectionServiceController.keepConnected(this))
        model.setLog(logBuffer.snapshot())
        setContent {
            HyphenTheme {
                HyphenHome(model = model, actions = buildActions())
            }
        }
        reconcilePersistentConnection()
        if (CompanionConnectionPreferences.transferRetryRequired(this)) {
            append("transfer stopped: reselect the file to retry; no restorable content source is retained")
        }
        consumePairingIntent(intent)
    }

    private fun buildActions(): HyphenActions =
        HyphenActions(
            onFindMac = { startWindow() },
            onShowQrHelp = { showQrHelp() },
            onManualConnect = { probeManualEndpoint(it) },
            onToggleCompanionConnection = { toggleCompanionConnection() },
            onCreateAssociation = { createAssociationForCurrentPeer() },
            onListAssociations = { showAssociations() },
            onManagePeers = { showPeerManagement() },
            onCheckNotificationAccess = {
                append(notificationAccessLine())
                model.setNotificationAccess(NotificationAccessController.forContext(this).status().enabled)
            },
            onOpenNotificationMirror = { showNotificationAccessOnboarding() },
            onCycleNotificationPrivacy = { cycleNotificationPrivacy() },
            onSendText = { sendTextLink(it) },
            onSendFile = { pickFileToSend() },
            onCancelTransfer = { cancelActiveTransfer() },
            onToggleBetaDiagnostics = { toggleBetaDiagnostics() },
            onPreviewDiagnostics = { previewDiagnostics() },
            onExportDiagnostics = { exportDiagnostics() },
            onDeleteDiagnostics = { deleteDiagnostics() },
        )

    /** Push the current pairing identity into the model (launch / forget / reset). */
    private fun refreshPairingState() {
        val peer = runCatching {
            AndroidTrustStores.openDefault(applicationContext).allPeers().firstOrNull()
        }.getOrNull()
        val name = peer?.displayName?.ifBlank { getString(R.string.conn_paired_fallback) }
        emit(ActivityEvent.PeerChanged(isPaired = peer != null, peerName = name))
    }

    private val sessionListener = object : ConnectionSupervisor.Listener {
        override fun onLog(line: String) = append(line)

        override fun onActivityEvent(event: ActivityEvent) {
            if (event is ActivityEvent.ConnectionChanged) {
                when (event.state) {
                    // Re-bind on every (re)connect, including supervisor-internal
                    // reconnects, so the singleton receiver is re-armed and its
                    // persisted checkpoints are restored.
                    ConnectionState.CONNECTED -> bindTransferPersistenceIfConnected()
                    ConnectionState.SUSPENDED -> transferReceiver.recycleSession()
                    else -> Unit
                }
            }
            emit(event)
        }

        override fun onEnvelope(envelope: Envelope, state: ConnectionSupervisor.ActiveState) {
            handleSessionEnvelope(envelope, state)
        }

        override fun onTransferProgress(progress: TransferProgress, outgoing: Boolean) {
            append(transferProgressLine(progress))
            emit(transferProgressEvent(progress, if (outgoing) TransferDirection.OUTGOING else TransferDirection.INCOMING))
            if (outgoing && progress.isComplete) {
                CompanionConnectionServiceController.endUserTransfer(applicationContext)
            }
        }
    }

    private data class ActiveStateSnapshot(
        val session: ProtocolSession?,
        val capabilities: SessionHandshake.NegotiatedCapabilities?,
        val transferSender: TransferSender?,
        val lastTransferProgress: TransferProgress?,
    )

    private fun activeStateSnapshot(): ActiveStateSnapshot {
        val state = supervisor.activeState()
        return ActiveStateSnapshot(
            session = state.session,
            capabilities = state.capabilities,
            transferSender = state.transferSender,
            lastTransferProgress = state.lastTransferProgress,
        )
    }

    private fun updateLastTransferProgress(progress: TransferProgress?) {
        supervisor.updateLastTransferProgress(progress)
    }

    private fun launchWorker(name: String, work: () -> Unit): Boolean {
        val worker = Thread({
            try {
                work()
            } finally {
                synchronized(workerLock) {
                    activeWorkers.remove(Thread.currentThread())
                }
            }
        }, name)
        val shouldStart = synchronized(workerLock) {
            if (activityDestroyed) {
                false
            } else {
                activeWorkers.add(worker)
                true
            }
        }
        if (!shouldStart) return false
        worker.start()
        return true
    }

    private fun postToUi(onDropped: (() -> Unit)? = null, action: () -> Unit): Boolean {
        fun drop(): Boolean {
            onDropped?.invoke()
            return false
        }
        if (activityDestroyed) return drop()
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (activityDestroyed) return drop()
            action()
            return true
        }
        runOnUiThread {
            if (activityDestroyed) {
                onDropped?.invoke()
            } else {
                action()
            }
        }
        return true
    }

    /** Emit a structured activity event into the observable model (on the UI thread). */
    private fun emit(event: ActivityEvent) {
        postToUi { model.apply(event) }
    }

    override fun onResume() {
        super.onResume()
        AssociationAttemptRuntime.coordinator.attachApprovalListener(associationApprovalListener)
        append(notificationAccessLine())
        model.setNotificationAccess(NotificationAccessController.forContext(this).status().enabled)
        reconcileAssociationBindings()
        reconcilePersistentConnection(silent = true)
    }

    override fun onPause() {
        AssociationAttemptRuntime.coordinator.detachApprovalListener(associationApprovalListener)
        super.onPause()
    }

    private fun reconcilePersistentConnection(silent: Boolean = false) {
        val result = CompanionConnectionServiceController.startPersistentLinkIfEnabled(this)
        model.setCompanionConnection(CompanionConnectionServiceController.keepConnected(this))
        if (!silent && result !in setOf(CompanionServiceStartResult.STARTED, CompanionServiceStartResult.DISABLED)) {
            append("companion foreground link not started: $result")
        }
    }

    private fun toggleCompanionConnection() {
        if (CompanionConnectionServiceController.keepConnected(this)) {
            CompanionConnectionServiceController.disconnect(this)
            model.setCompanionConnection(false)
            append("companion foreground link disabled by user")
            return
        }
        if (currentTrustedPeer() == null) {
            showCompanionRequiresPeer()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dlg_companion_title)
            .setMessage(R.string.dlg_companion_msg)
            .setPositiveButton(R.string.dlg_companion_enable) { _, _ ->
                requestForegroundNotification(PendingNotificationAction.ENABLE_COMPANION)
            }
            .setNegativeButton(R.string.dlg_cancel, null)
            .show()
    }

    private fun requestForegroundNotification(action: PendingNotificationAction) {
        if (CompanionConnectionServiceController.notificationPermissionRequired(this)) {
            pendingNotificationAction = action
            if (Build.VERSION.SDK_INT >= 33) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            return
        }
        when (action) {
            PendingNotificationAction.ENABLE_COMPANION -> enableCompanionConnection()
            PendingNotificationAction.PICK_FILE -> enableCompanionForFilePicker()
        }
    }

    private fun enableCompanionConnection() {
        if (currentTrustedPeer() == null) {
            model.setCompanionConnection(false)
            showCompanionRequiresPeer()
            return
        }
        when (val result = CompanionConnectionServiceController.enablePersistentLink(this)) {
            CompanionServiceStartResult.STARTED -> {
                model.setCompanionConnection(true)
                append("companion foreground link enabled")
            }
            CompanionServiceStartResult.NOTIFICATION_PERMISSION_REQUIRED,
            CompanionServiceStartResult.NOTIFICATIONS_DISABLED,
            -> showCompanionNotificationSettings()
            else -> {
                model.setCompanionConnection(false)
                append("companion foreground link failed: $result")
            }
        }
    }

    private fun showCompanionRequiresPeer() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dlg_assoc_requires_peer_title)
            .setMessage(R.string.dlg_companion_requires_peer_msg)
            .setPositiveButton(R.string.dlg_ok, null)
            .show()
        append("companion foreground link requires one current trusted Mac")
    }

    private fun enableCompanionForFilePicker() {
        if (activeStateSnapshot().transferSender == null || currentTrustedPeer() == null) {
            append("transfer/send: trusted Mac session ended before file selection")
            return
        }
        if (CompanionConnectionServiceController.keepConnected(this)) {
            if (CompanionConnectionServiceController.notificationsVisible(this)) {
                openFilePicker()
            } else {
                showCompanionNotificationSettings()
            }
            return
        }
        when (val result = CompanionConnectionServiceController.enablePersistentLink(this)) {
            CompanionServiceStartResult.STARTED -> {
                model.setCompanionConnection(true)
                append("companion foreground link enabled for file selection and transfer")
                openFilePicker()
            }
            CompanionServiceStartResult.NOTIFICATION_PERMISSION_REQUIRED,
            CompanionServiceStartResult.NOTIFICATIONS_DISABLED,
            -> showCompanionNotificationSettings()
            else -> append("transfer/send: companion foreground link failed ($result)")
        }
    }

    private fun showCompanionNotificationSettings() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dlg_companion_settings_title)
            .setMessage(R.string.dlg_companion_settings_msg)
            .setPositiveButton(R.string.dlg_open_app_notification_settings) { _, _ ->
                runCatching {
                    startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
                    )
                }.onFailure { append("notification settings unavailable: ${it.message}") }
            }
            .setNegativeButton(R.string.dlg_cancel, null)
            .show()
    }

    private fun showQrHelp() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dlg_qr_help_title)
            .setMessage(R.string.dlg_qr_help_msg)
            .setPositiveButton(R.string.dlg_ok, null)
            .show()
    }

    private fun renderCdm(event: AssociationEvent) = renderCdm(null, event)

    private fun renderCdm(
        attempt: AssociationAttemptToken?,
        event: AssociationEvent,
    ) {
        when (event) {
            is AssociationEvent.PendingUserApproval -> {
                if (attempt == null ||
                    !AssociationAttemptRuntime.coordinator.offerApproval(attempt, event.approval)
                ) {
                    append("cdm: ignored stale system approval callback")
                    return
                }
                append("cdm: system approval is ready for the visible activity")
            }
            is AssociationEvent.Associated -> {
                val completion = if (attempt == null) {
                    AssociationAttemptCompletion.Stale
                } else {
                    AssociationAttemptRuntime.coordinator.complete(attempt, event.associationId)
                }
                val fingerprint = when (completion) {
                    is AssociationAttemptCompletion.First -> completion.peerFingerprint
                    AssociationAttemptCompletion.DuplicateSame -> {
                        append("cdm duplicate associated callback ignored: id=${event.associationId}")
                        return
                    }
                    AssociationAttemptCompletion.Stale -> {
                        associationController.removeUnboundAssociation(event.associationId)
                        append("cdm associated but not bound: stale platform request")
                        return
                    }
                }
                if (!isStillTrusted(fingerprint)) {
                    associationController.removeUnboundAssociation(event.associationId)
                    append("cdm associated but not bound: trusted peer changed during approval")
                    return
                }
                runCatching {
                    associationBindings.bind(fingerprint, event.associationId)
                }.onSuccess { displacedAssociationId ->
                    append("cdm associated with trusted peer: id=${event.associationId} name=${event.displayName}")
                    if (displacedAssociationId != null) {
                        associationController.removeUnboundAssociation(displacedAssociationId)
                        append("cdm replaced association cleaned: id=$displacedAssociationId")
                    }
                }.onFailure {
                    associationController.removeUnboundAssociation(event.associationId)
                    append("cdm association binding failed: ${it.message}")
                }
            }
            is AssociationEvent.Removed -> {
                runCatching { associationBindings.removeAssociation(event.associationId) }
                    .onFailure { append("cdm binding cleanup failed: ${it.message}") }
                append("cdm removed: id=${event.associationId}")
            }
            is AssociationEvent.Failed -> {
                attempt?.let(::cancelAssociationAttempt)
                append("cdm failed: ${event.message}")
            }
            AssociationEvent.UnsupportedOnThisSdk -> {
                attempt?.let(::cancelAssociationAttempt)
                append("cdm: self-managed association needs API 33+ (QR-only on this device)")
            }
        }
    }

    private fun launchPendingAssociationApproval() {
        val pending = AssociationAttemptRuntime.coordinator.claimPendingApproval() ?: return
        append("cdm: launching system approval dialog")
        postToUi(
            onDropped = {
                AssociationAttemptRuntime.coordinator.releaseApprovalClaim(pending.token)
            },
        ) {
            if (!AssociationAttemptRuntime.coordinator.isActive(pending.token)) {
                return@postToUi
            }
            runCatching { pending.approval.launch(this) }
                .onFailure {
                    AssociationAttemptRuntime.coordinator
                        .abandonAfterApprovalLaunchFailure(pending.token)
                    append("cdm approval dialog failed: ${it.message}")
                }
        }
    }

    private fun createAssociationForCurrentPeer() {
        val trustedPeer = currentTrustedPeer()
        if (trustedPeer == null) {
            AlertDialog.Builder(this)
                .setTitle(R.string.dlg_assoc_requires_peer_title)
                .setMessage(R.string.dlg_assoc_requires_peer_msg)
                .setPositiveButton(R.string.dlg_ok, null)
                .show()
            append("cdm: pair exactly one trusted Mac before creating a system association")
            return
        }
        val attempt = AssociationAttemptRuntime.coordinator.tryBegin(trustedPeer.spkiFingerprint)
        if (attempt == null) {
            append("cdm: association approval is already pending")
            return
        }
        associationController.associate(trustedPeer.displayName.ifBlank { "Hyphen Mac" }) { event ->
            renderCdm(attempt, event)
        }
    }

    private fun cancelAssociationAttempt(attempt: AssociationAttemptToken) {
        AssociationAttemptRuntime.coordinator.cancel(attempt)
    }

    private fun currentTrustedPeer(): TrustedPeer? = runCatching {
        val store = AndroidTrustStores.openDefault(applicationContext)
        val activeFingerprint = supervisor.currentPeerFingerprint()
        if (activeFingerprint != null) {
            store.get(activeFingerprint)
        } else {
            store.allPeers().singleOrNull()
        }
    }.getOrNull()

    private fun isStillTrusted(fingerprint: ByteArray): Boolean =
        runCatching {
            AndroidTrustStores.openDefault(applicationContext).get(fingerprint) != null
        }.getOrDefault(false)

    private fun showAssociations() {
        val existingIds = associationController.associationIdsForReconciliation()
        if (existingIds == null) {
            append("associations: system list unavailable; durable bindings were preserved")
            return
        }
        val ids = existingIds.sorted()
        reconcileAssociationBindings(existingIds)
        append("associations: $ids")
        if (ids.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.dlg_assoc_title)
                .setMessage(R.string.dlg_assoc_empty_msg)
                .setPositiveButton(R.string.dlg_ok, null)
                .show()
            return
        }

        val labels = ids.map { getString(R.string.dlg_assoc_item, it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.dlg_assoc_title)
            .setItems(labels) { _, which -> confirmRemoveAssociation(ids[which]) }
            .setNegativeButton(R.string.dlg_close, null)
            .show()
    }

    private fun reconcileAssociationBindings(
        existingIds: Set<Int>? = associationController.associationIdsForReconciliation(),
    ) {
        if (existingIds == null) {
            append("cdm binding reconciliation skipped: system list unavailable")
            return
        }
        runCatching { associationBindings.reconcile(existingIds) }
            .onSuccess { removed ->
                if (removed.isNotEmpty()) append("cdm bindings reconciled: removed=$removed")
            }
            .onFailure { append("cdm binding reconciliation failed: ${it.message}") }
    }

    private fun confirmRemoveAssociation(associationId: Int) {
        val request = associationController.prepareDisassociation(associationId) ?: return
        showAssociationRemovalConfirmation(request)
    }

    private fun showAssociationRemovalConfirmation(request: AssociationRemovalRequest) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dlg_assoc_remove_title, request.associationId))
            .setMessage(R.string.dlg_assoc_remove_msg)
            .setPositiveButton(R.string.dlg_assoc_remove_confirm) { _, _ -> request.confirm() }
            .setNegativeButton(R.string.dlg_cancel) { _, _ ->
                request.cancel()
                append("cdm removal cancelled: id=${request.associationId}")
            }
            .setOnCancelListener {
                request.cancel()
                append("cdm removal cancelled: id=${request.associationId}")
            }
            .show()
    }

    private fun showPeerManagement() {
        try {
            val store = AndroidTrustStores.openDefault(applicationContext)
            val peers = store.allPeers()
            if (peers.isEmpty()) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.dlg_peers_title)
                    .setMessage(R.string.dlg_peers_empty_msg)
                    .setPositiveButton(R.string.dlg_ok, null)
                    .show()
                return
            }
            AlertDialog.Builder(this)
                .setTitle(R.string.dlg_peers_title)
                .setItems(peers.map(::peerLabel).toTypedArray()) { _, which ->
                    confirmForgetPeer(peers[which])
                }
                .setMessage(R.string.dlg_peers_list_msg)
                .setPositiveButton(R.string.dlg_peers_reset_all) { _, _ -> confirmResetPeers(peers.size) }
                .setNegativeButton(R.string.dlg_close, null)
                .show()
        } catch (e: Exception) {
            append("peer management failed: ${e.message}")
        }
    }

    private fun confirmForgetPeer(peer: TrustedPeer) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dlg_forget_title, peer.displayName.ifBlank { getString(R.string.peer_unnamed) }))
            .setMessage(getString(R.string.dlg_forget_msg, fingerprintPrefix(peer.spkiFingerprint)))
            .setPositiveButton(R.string.dlg_forget_confirm) { _, _ ->
                try {
                    supervisor.invalidateTransferCheckpoints(peer.spkiFingerprint, transferReceiver)
                    associationBindings.removePeer(peer.spkiFingerprint)?.let { associationId ->
                        associationController.removeUnboundAssociation(associationId)
                    }
                    val removed = AndroidTrustStores.openDefault(applicationContext).remove(peer.spkiFingerprint)
                    stopCurrentSessionAfterTrustChange()
                    append("peer forgotten: ${peer.displayName.ifBlank { "unnamed" }} removed=$removed")
                    refreshPairingState()
                } catch (e: Exception) {
                    append("peer forget failed: ${e.message}")
                }
            }
            .setNegativeButton(R.string.dlg_cancel, null)
            .show()
    }

    private fun confirmResetPeers(count: Int) {
        AlertDialog.Builder(this)
            .setTitle(R.string.dlg_reset_title)
            .setMessage(getString(R.string.dlg_reset_msg, count))
            .setPositiveButton(R.string.dlg_reset_confirm) { _, _ ->
                try {
                    supervisor.invalidateAllTransferCheckpoints(transferReceiver)
                    associationBindings.clear().forEach { associationId ->
                        associationController.removeUnboundAssociation(associationId)
                    }
                    AndroidTrustStores.openDefault(applicationContext).removeAll()
                    stopCurrentSessionAfterTrustChange()
                    append("paired devices reset: $count removed")
                    refreshPairingState()
                } catch (e: Exception) {
                    append("peer reset failed: ${e.message}")
                }
            }
            .setNegativeButton(R.string.dlg_cancel, null)
            .show()
    }

    private fun stopCurrentSessionAfterTrustChange() {
        supervisor.stopAfterTrustChange()
        CompanionConnectionServiceController.stopServicesAfterTrustChange(this)
        model.setCompanionConnection(false)
    }

    private fun bindTransferPersistenceIfConnected() {
        if (supervisor.activeState().session == null) return
        supervisor.bindTransferPersistence(transferReceiver).forEach(::handleCompletedIncomingTransfer)
    }

    private fun handleCompletedIncomingTransfer(completed: TransferCompleted) {
        updateLastTransferProgress(null)
        try {
            val delivered = deliverIncomingFile(completed)
            transferReceiver.markDeliverySucceeded(completed.manifest.fileId)
            val line = transferCompletedLine(completed, delivered)
            val manifest = completed.manifest
            append(line)
            emit(ActivityEvent.TransferCompleted(
                fileId = manifest.fileId,
                filename = manifest.filename,
                sizeBytes = manifest.sizeBytes,
                direction = TransferDirection.INCOMING,
                verified = true,
                atMillis = System.currentTimeMillis(),
            ))
        } catch (e: Exception) {
            runCatching { transferReceiver.markDeliveryFailed(completed.manifest.fileId) }
            append(
                "transfer received but save failed; verified source queued for retry: ${e.message}",
            )
        }
    }

    private fun peerLabel(peer: TrustedPeer): String =
        "${peer.displayName.ifBlank { getString(R.string.peer_unnamed) }} (${fingerprintPrefix(peer.spkiFingerprint)})"

    private fun fingerprintPrefix(fingerprint: ByteArray): String =
        fingerprint.take(6).joinToString("") { "%02x".format(it) }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A fresh external delivery is a fresh user action even when the QR
        // contents match an earlier attempt. Recreation still dedupes through
        // the saved digest below.
        consumedPairingIntentToken = null
        consumePairingIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        consumedPairingIntentToken?.let { outState.putString(STATE_CONSUMED_PAIRING_TOKEN, it) }
        super.onSaveInstanceState(outState)
    }

    private fun consumePairingIntent(source: Intent?) {
        val data = source?.dataString ?: return
        val token = pairingIntentToken(data)
        if (token == consumedPairingIntentToken) return
        consumedPairingIntentToken = token
        when (val route = PairingIntentRouter.route(source.action, data)) {
            is PairingIntentRoute.Accepted -> {
                append("pairing link accepted from user-visible QR handoff")
                startPairing(route.payload)
            }
            is PairingIntentRoute.Rejected ->
                append("pairing link rejected: ${route.reason}")
        }
    }

    private fun pairingIntentToken(data: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(data.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun probeManualEndpoint(raw: String) {
        val isQr = raw.trim().startsWith("hyphen://")
        val result = if (isQr) EndpointParser.parseQr(raw) else EndpointParser.parseManual(raw)
        when (val parsed = result) {
            is ParseResult.Rejected -> append("endpoint rejected: ${parsed.reason}")
            is ParseResult.Ok -> when (val endpoint = parsed.endpoint) {
                is ParsedEndpoint.QrPayload -> {
                    val fpHead = endpoint.decodedFingerprint()?.take(4)?.joinToString("") { "%02x".format(it) } ?: "—"
                    append("qr parsed: v=${endpoint.version} dn=${endpoint.deviceName ?: "—"} fp=$fpHead… nonce ok")
                    startPairing(endpoint)
                }
                is ParsedEndpoint.Manual -> {
                    if (endpoint.nonceB64 == null) {
                        append("manual pairing needs nonce: use host:port?n=<nonce> from the Mac pairing screen")
                        return
                    }
                    val qr = ParsedEndpoint.QrPayload(
                        version = 0,
                        host = endpoint.host,
                        port = endpoint.port,
                        spkiFingerprintB64 = null,
                        nonceB64 = endpoint.nonceB64,
                        deviceName = null,
                    )
                    append("manual pairing: provisional TLS to ${endpoint.host}:${endpoint.port} …")
                    startPairing(qr)
                }
            }
        }
    }

    /**
     * Android side of the SAS pairing flow (HYP-M2-011, protocol v0 §5):
     * provisional TLS connect pinning the QR's fingerprint, compute the
     * SAS, and write trust only through the gate when the user confirms.
     * Once trusted, the same socket becomes the first steady-state
     * ProtocolSession so M3 debug features can send over it.
     */
    private fun startPairing(qr: ParsedEndpoint.QrPayload) {
        val attempt = PairingAttemptRuntime.coordinator.tryBegin()
        if (attempt == null) {
            append("pairing already in progress")
            return
        }
        pairingAttemptToken.set(attempt)
        append("pairing: provisional TLS to ${qr.host}:${qr.port} …")
        val launched = launchWorker("hyphen-pairing") {
            var socket: SSLSocket? = null
            try {
                val identity = AndroidKeystoreTlsIdentity.getOrCreate()
                val pinnedFp = qr.decodedFingerprint()
                var observedMacFp: ByteArray? = pinnedFp
                socket = TlsClient.connect(
                    host = qr.host,
                    port = qr.port,
                    identity = identity,
                    isTrusted = { fp ->
                        if (pinnedFp != null) {
                            fp.contentEquals(pinnedFp)
                        } else {
                            observedMacFp = fp.copyOf()
                            true
                        }
                    },
                )
                val connectedSocket = socket
                if (!PairingAttemptRuntime.coordinator.claimSocket(attempt, connectedSocket)) {
                    throw InterruptedException("pairing attempt no longer active")
                }
                val macFp = observedMacFp
                    ?: throw IllegalStateException("server fingerprint missing after provisional TLS")
                val wire = PairingWireProtocol.runInitiator(
                    socket = socket,
                    nonce = qr.decodedNonce(),
                    macSpkiFingerprint = macFp,
                    androidSpkiFingerprint = identity.spkiFingerprint,
                    device = PairingWireProtocol.WireDeviceInfo(
                        kind = "android",
                        appVersion = "0.0.1",
                        deviceName = Build.MODEL,
                    ),
                )
                val gate = SasConfirmationGate(
                    transcript = wire.transcript,
                    peerFingerprint = macFp,
                    peerDisplayName = qr.deviceName ?: "Mac",
                    trustStore = AndroidTrustStores.openDefault(applicationContext),
                )
                val handoffSocket = socket
                socket = null
                postToUi(onDropped = {
                    cancelPairingAttempt(attempt, handoffSocket)
                }) {
                    presentSasDialog(attempt, qr, gate, wire.confirm, handoffSocket, macFp)
                }
            } catch (e: Exception) {
                cancelPairingAttempt(attempt, socket)
                append("pairing failed: ${e.message}")
            }
        }
        if (!launched) cancelPairingAttempt(attempt)
    }

    private fun presentSasDialog(
        attempt: PairingAttemptToken,
        qr: ParsedEndpoint.QrPayload,
        gate: SasConfirmationGate,
        confirm: PairingWireProtocol.PairingConfirmExchange,
        socket: SSLSocket,
        macFp: ByteArray,
    ) {
        fun finalizePairing(localAccepted: Boolean) {
            val launched = launchWorker("hyphen-pairing-commit") {
                val outcome = runCatching {
                    check(PairingAttemptRuntime.coordinator.claimCommit(attempt)) {
                        "pairing attempt no longer owns the SAS decision"
                    }
                    PairingCommit.exchange(confirm, localAccepted)
                }.getOrElse { error ->
                    cancelPairingAttempt(attempt, socket)
                    append("pairing confirmation failed: ${error.message}")
                    return@launchWorker
                }
                when (outcome) {
                    PairingCommit.Outcome.TRUSTED -> {
                        runCatching {
                            check(PairingAttemptRuntime.coordinator.runIfActive(attempt) {
                                check(
                                    PairingCommit.commitTrust(gate, outcome) { peer ->
                                        check(supervisor.commitPairingEndpoint(qr, peer)) {
                                            "trusted peer route could not be persisted"
                                        }
                                    } ==
                                        PairingCommit.Outcome.TRUSTED,
                                ) { "SAS trust gate rejected an accepted pairing" }
                                Unit
                            } != null) { "pairing attempt no longer active" }
                            append("paired — fingerprint pinned (${gate.sas})")
                            emit(ActivityEvent.PairingNote(getString(R.string.pairing_note_paired), System.currentTimeMillis()))
                            startSteadySession(attempt, qr, socket)
                        }.onFailure { error ->
                            cancelPairingAttempt(attempt, socket)
                            append("pairing activation failed: ${error.message}")
                        }
                    }
                    PairingCommit.Outcome.REJECTED -> {
                        PairingAttemptRuntime.coordinator.runIfActive(attempt) {
                            PairingCommit.commitTrust(gate, outcome)
                        }
                        cancelPairingAttempt(attempt, socket)
                        append("pairing rejected — nothing stored")
                    }
                    PairingCommit.Outcome.INCOMPLETE -> {
                        PairingAttemptRuntime.coordinator.runIfActive(attempt) {
                            PairingCommit.commitTrust(gate, outcome)
                        }
                        cancelPairingAttempt(attempt, socket)
                        append("pairing incomplete — nothing stored")
                    }
                }
            }
            if (!launched) {
                cancelPairingAttempt(attempt, socket)
            }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dlg_sas_title)
            .setMessage(getString(R.string.dlg_sas_msg, gate.sas))
            .setPositiveButton(R.string.dlg_sas_confirm) { _, _ ->
                finalizePairing(localAccepted = true)
            }
            .setNegativeButton(R.string.dlg_sas_reject) { _, _ ->
                finalizePairing(localAccepted = false)
            }
            .setCancelable(false)
            .show()
    }

    private fun startSteadySession(
        attempt: PairingAttemptToken,
        qr: ParsedEndpoint.QrPayload,
        socket: SSLSocket,
    ) {
        val launched = launchWorker("hyphen-steady-session") {
            try {
                val device = SessionHandshake.DeviceInfo(
                    kind = "android",
                    appVersion = "0.0.1",
                    deviceName = Build.MODEL,
                )
                val handshake = SessionHandshake.initiate(
                    socket = socket,
                    device = device,
                    resumeToken = null,
                    previousSessionId = null,
                )
                val peerName = handshake.peerDevice?.deviceName ?: qr.deviceName ?: "Mac"
                check(supervisor.adoptPairedSession(socket, handshake, peerName)) {
                    "trust changed before paired session activation"
                }
                check(PairingAttemptRuntime.coordinator.releaseSocket(attempt, socket)) {
                    "pairing attempt lost socket ownership before session activation"
                }
                bindTransferPersistenceIfConnected()
                postToUi {
                    emit(ActivityEvent.PairingNote(getString(R.string.pairing_note_connected, peerName), System.currentTimeMillis()))
                    if (!CompanionConnectionServiceController.keepConnected(this)) {
                        toggleCompanionConnection()
                    }
                }
            } catch (e: Exception) {
                cancelPairingAttempt(attempt, socket)
                append("session failed: ${e.message}")
            } finally {
                completePairingAttempt(attempt)
            }
        }
        if (!launched) {
            cancelPairingAttempt(attempt, socket)
        }
    }

    private fun cancelPairingAttempt(attempt: PairingAttemptToken, socket: SSLSocket? = null) {
        PairingAttemptRuntime.coordinator.cancel(attempt, socket)
        pairingAttemptToken.compareAndSet(attempt, null)
    }

    private fun completePairingAttempt(attempt: PairingAttemptToken) {
        PairingAttemptRuntime.coordinator.complete(attempt)
        pairingAttemptToken.compareAndSet(attempt, null)
    }

    private fun handleSessionEnvelope(
        envelope: Envelope,
        state: ConnectionSupervisor.ActiveState = supervisor.activeState(),
    ) {
        val session = state.session
        try {
            if (envelope.type == Envelope.TYPE_ERROR) {
                append(peerErrorLine(envelope))
                return
            }
            val expectedCapability = expectedCapability(envelope.type)
            if (session != null && expectedCapability != null && envelope.capability != expectedCapability) {
                val id = sendProtocolError(
                    session = session,
                    regarding = envelope,
                    code = "plugin/unsupported-capability",
                    message = "Message type was sent under the wrong capability.",
                )
                append("unsupported capability reported: $id (${envelope.capability})")
                return
            }
            if (session != null && !isNegotiatedCapability(state.capabilities, envelope.capability)) {
                val id = sendProtocolError(
                    session = session,
                    regarding = envelope,
                    code = "plugin/unsupported-capability",
                    message = "Capability is not negotiated for this session.",
                )
                append("unsupported capability reported: $id (${envelope.capability})")
                return
            }
            if (session != null && !isNegotiatedNotificationOption(state.capabilities, envelope.type)) {
                val id = sendProtocolError(
                    session = session,
                    regarding = envelope,
                    code = "plugin/unsupported-capability",
                    message = "Notification action is not negotiated for this session.",
                )
                append("unsupported notification option reported: $id (${envelope.type})")
                return
            }
            if (session != null) {
                val dismissResultId = NotificationDismissRequestHandler(
                    canceller = HyphenNotificationListenerRuntime.notificationCanceller(),
                    isActiveNotification = HyphenNotificationListenerRuntime::isNotificationActive,
                    outbox = ProtocolSessionNotificationOutbox(session),
                ).handle(envelope)
                if (dismissResultId != null) {
                    append("notification dismiss result sent: $dismissResultId")
                    emit(ActivityEvent.NotificationActionPerformed(getString(R.string.notif_action_name), getString(R.string.notif_action_cleared), System.currentTimeMillis()))
                    return
                }
                val replyResultId = NotificationReplyRequestHandler(
                    replier = HyphenNotificationListenerRuntime.notificationReplier(),
                    outbox = ProtocolSessionNotificationOutbox(session),
                ).handle(envelope)
                if (replyResultId != null) {
                    append("notification reply result sent: $replyResultId")
                    emit(ActivityEvent.NotificationActionPerformed(getString(R.string.notif_action_name), getString(R.string.notif_action_replied), System.currentTimeMillis()))
                    return
                }
                if (envelope.type == NotificationProtocol.TYPE_PRIVACY_POLICY) {
                    val policy = NotificationPrivacyPolicyHandler.parse(envelope.payload)
                    HyphenNotificationListenerRuntime.setNotificationPrivacyPolicy(policy)
                    append(
                        "notification privacy policy applied: default=${policy.defaultMode.wire}, " +
                            "${policy.perPackageModes.size} app overrides",
                    )
                    return
                }
            }
            if (envelope.capability == TransferProtocol.CAPABILITY) {
                if (envelope.type == TransferProtocol.TYPE_RESUME_INFO) {
                    val info = TransferResumeInfo.fromJson(envelope.payload)
                    try {
                        state.transferSender?.handleResumeInfo(info)
                        append("transfer resume continued: ${info.fileId}")
                    } catch (e: Exception) {
                        append("transfer resume info ignored: ${e.message}")
                    }
                    return
                }
                when (val event = transferReceiver.handle(envelope)) {
                    is TransferEvent.Completed -> {
                        handleCompletedIncomingTransfer(event.completed)
                    }
                    is TransferEvent.ResumeRequested -> {
                        val sender = state.transferSender
                        if (session != null && sender != null) {
                            val id = sender.sendResumeInfo(event.info)
                            append(
                                if (id != null) {
                                    "transfer resume info sent: $id (${event.info.fileId})"
                                } else {
                                    "transfer resume info queued: ${event.info.fileId}"
                                },
                            )
                        } else {
                            append("transfer resume requested without active session")
                        }
                    }
                    is TransferEvent.Cancelled -> {
                        updateLastTransferProgress(null)
                        append("transfer cancelled: ${event.cancel.fileId}")
                        emit(ActivityEvent.TransferCancelled(event.cancel.fileId, System.currentTimeMillis()))
                    }
                    TransferEvent.Ignored -> Unit
                }
                return
            }
            val request = textReceiver.handle(envelope)
            if (request != null) {
                postToUi { presentTextLinkConfirmation(request) }
                return
            }
        } catch (e: IllegalArgumentException) {
            session?.let {
                sendProtocolError(
                    session = it,
                    regarding = envelope,
                    code = "protocol/invalid-envelope",
                    message = "Envelope payload is invalid for its type.",
                )
            }
            append("session envelope rejected: ${e.message}")
            return
        }
        if (isPluginEnvelope(envelope)) {
            session?.let {
                val id = sendProtocolError(
                    session = it,
                    regarding = envelope,
                    code = "protocol/unknown-type",
                    message = "No handler is registered for this message type.",
                )
                append("unknown type reported: $id (${envelope.type})")
            }
        }
    }

    private fun isNegotiatedCapability(
        capabilities: SessionHandshake.NegotiatedCapabilities?,
        capability: String?,
    ): Boolean =
        capability == null || capabilities?.contains(capability) != false

    private fun isNegotiatedNotificationOption(
        capabilities: SessionHandshake.NegotiatedCapabilities?,
        type: String,
    ): Boolean =
        NotificationCapabilityGate.allowsInboundRequest(type, capabilities)

    private fun isPluginEnvelope(envelope: Envelope): Boolean =
        envelope.type !in setOf(Envelope.TYPE_ACK, Envelope.TYPE_HEARTBEAT, Envelope.TYPE_HELLO, Envelope.TYPE_ERROR)

    private fun expectedCapability(type: String): String? =
        when (type) {
            NotificationProtocol.TYPE_DISMISS_REQUEST,
            NotificationProtocol.TYPE_REPLY_REQUEST,
            NotificationProtocol.TYPE_PRIVACY_POLICY -> NotificationProtocol.CAPABILITY
            TransferProtocol.TYPE_MANIFEST,
            TransferProtocol.TYPE_CHUNK,
            TransferProtocol.TYPE_RESUME_REQUEST,
            TransferProtocol.TYPE_RESUME_INFO,
            TransferProtocol.TYPE_CANCEL -> TransferProtocol.CAPABILITY
            TextLinkMessage.TYPE_SEND -> TextLinkMessage.CAPABILITY
            else -> null
        }

    private fun sendProtocolError(
        session: ProtocolSession,
        regarding: Envelope,
        code: String,
        message: String,
        retryable: Boolean = false,
    ): String =
        session.send(
            type = Envelope.TYPE_ERROR,
            payload = Json.obj(
                "code" to Json.Str(code),
                "message" to Json.Str(message.take(256)),
                "regarding" to Json.Str(regarding.messageId),
                "retryable" to Json.Bool(retryable),
            ),
            requiresAck = false,
        )

    private fun peerErrorLine(envelope: Envelope): String {
        val code = (envelope.payload["code"] as? Json.Str)?.value ?: "unknown"
        val regarding = (envelope.payload["regarding"] as? Json.Str)?.value ?: "none"
        return "peer error: $code regarding $regarding"
    }

    private fun pickFileToSend() {
        if (activeStateSnapshot().transferSender == null) {
            append("transfer/send: no active Mac session")
            return
        }
        if (!CompanionConnectionServiceController.keepConnected(this) ||
            !CompanionConnectionServiceController.notificationsVisible(this)
        ) {
            AlertDialog.Builder(this)
                .setTitle(R.string.dlg_transfer_notification_title)
                .setMessage(R.string.dlg_transfer_notification_msg)
                .setPositiveButton(R.string.dlg_transfer_notification_continue) { _, _ ->
                    requestForegroundNotification(PendingNotificationAction.PICK_FILE)
                }
                .setNegativeButton(R.string.dlg_cancel, null)
                .show()
            return
        }
        openFilePicker()
    }

    private fun openFilePicker() {
        if (activeStateSnapshot().transferSender == null) {
            append("transfer/send: no active Mac session")
            return
        }
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent.createChooser(intent, "Send file to Mac"), REQUEST_PICK_FILE)
        } catch (e: ActivityNotFoundException) {
            append("transfer/send: no file picker available")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK_FILE) return
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            append("transfer/send cancelled")
            return
        }
        sendFile(uri)
    }

    /**
     * Outbound file send entry: feeds a content:// selection into the persistent
     * [activeTransferSender], populating its outbound registry so a later
     * resume.info/ack from the peer routes to a real transfer.
     */
    private fun sendFile(uri: Uri) {
        val state = activeStateSnapshot()
        val sender = state.transferSender
        if (sender == null) {
            append("transfer/send: no active Mac session")
            return
        }
        if (state.capabilities?.contains(TransferProtocol.CAPABILITY) != true) {
            append("transfer/send: peer did not negotiate transfer.v1")
            return
        }
        val meta = queryContentMetadata(uri)
        if (meta == null) {
            append("transfer/send: could not read selected file size")
            return
        }
        val source = StreamTransferByteSource(meta.sizeBytes) {
            contentResolver.openInputStream(uri) ?: throw java.io.IOException("could not open $uri")
        }
        val foregroundStart = CompanionConnectionServiceController.beginUserTransfer(this)
        val foregroundResult = foregroundStart.serviceResult
        val foregroundReservation = foregroundStart.reservation
        if (foregroundResult != CompanionServiceStartResult.STARTED || foregroundReservation == null) {
            append("transfer/send: foreground continuation unavailable ($foregroundResult)")
            if (foregroundResult == CompanionServiceStartResult.NOTIFICATION_PERMISSION_REQUIRED ||
                foregroundResult == CompanionServiceStartResult.NOTIFICATIONS_DISABLED
            ) {
                showCompanionNotificationSettings()
            }
            return
        }
        val fileId = "f_${Ulid.generate()}"
        val launched = launchWorker("hyphen-send-file") {
            var registrationTransferred = false
            try {
                val manifest = sender.sendSource(
                    filename = meta.filename,
                    mimeType = meta.mimeType,
                    source = source,
                    chunkSizeBytes = DEFAULT_TRANSFER_CHUNK_BYTES,
                    fileId = fileId,
                )
                if (!CompanionConnectionServiceController.completeUserTransferRegistration(
                        foregroundReservation,
                    )
                ) {
                    sender.cancel(manifest.fileId, discard = true)
                    throw IllegalStateException("foreground transfer ownership was revoked")
                }
                registrationTransferred = true
                append("transfer/send started: ${manifest.filename} (${manifest.sizeBytes} bytes)")
                // Surface the outgoing row immediately; chunk-ack progress follows.
                emit(ActivityEvent.TransferProgressed(
                    fileId = manifest.fileId,
                    filename = manifest.filename,
                    direction = TransferDirection.OUTGOING,
                    completedBytes = 0,
                    totalBytes = manifest.sizeBytes,
                    completedChunks = 0,
                    totalChunks = manifest.chunkCount,
                    complete = false,
                    atMillis = System.currentTimeMillis(),
                ))
            } catch (e: Exception) {
                sender.cancel(fileId, discard = true)
                if (registrationTransferred) {
                    CompanionConnectionServiceController.endUserTransfer(applicationContext)
                } else {
                    CompanionConnectionServiceController.endUserTransfer(
                        applicationContext,
                        foregroundReservation,
                    )
                }
                append("transfer/send failed: ${e.message}")
            }
        }
        if (!launched) {
            CompanionConnectionServiceController.endUserTransfer(this, foregroundReservation)
        }
    }

    private data class ContentMetadata(
        val filename: String,
        val mimeType: String,
        val sizeBytes: Long,
    )

    private fun queryContentMetadata(uri: Uri): ContentMetadata? {
        var name = uri.lastPathSegment ?: "file.bin"
        var size = -1L
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        }
        if (size < 0) return null
        val mime = contentResolver.getType(uri) ?: "application/octet-stream"
        return ContentMetadata(name, mime, size)
    }

    private fun cancelActiveTransfer() {
        val state = activeStateSnapshot()
        val progress = state.lastTransferProgress
        if (progress == null || progress.isComplete) {
            CompanionConnectionServiceController.endUserTransfer(this)
            append("transfer cancel: no active transfer")
            return
        }
        val sender = state.transferSender
        if (sender == null) {
            CompanionConnectionServiceController.endUserTransfer(this)
            append("transfer cancel: no active session")
            return
        }
        val id = sender.cancel(progress.fileId, discard = true)
        updateLastTransferProgress(null)
        if (!sender.hasInFlightOutboundTransfers()) {
            CompanionConnectionServiceController.endUserTransfer(this)
        }
        append("transfer cancel sent: $id (${progress.filename})")
        emit(ActivityEvent.TransferCancelled(progress.fileId, System.currentTimeMillis()))
    }

    private fun transferProgressEvent(progress: TransferProgress, direction: TransferDirection): ActivityEvent =
        ActivityEvent.TransferProgressed(
            fileId = progress.fileId,
            filename = progress.filename,
            direction = direction,
            completedBytes = progress.completedBytes,
            totalBytes = progress.totalBytes,
            completedChunks = progress.completedChunks,
            totalChunks = progress.totalChunks,
            complete = progress.isComplete,
            atMillis = System.currentTimeMillis(),
        )

    private fun transferProgressLine(progress: TransferProgress): String =
        "transfer ${progress.filename}: ${progress.completedBytes}/${progress.totalBytes} bytes " +
            "(${progress.completedChunks}/${progress.totalChunks})"

    private fun deliverIncomingFile(completed: TransferCompleted): File {
        // External app-private storage can live on a filesystem without hard
        // links. Prefer it, but retry atomically in internal storage when the
        // volume cannot satisfy ReceivedFileDelivery's no-partial/no-clobber
        // publication contract.
        val destinations = listOfNotNull(getExternalFilesDir(null), filesDir)
            .distinctBy { it.absolutePath }
            .map { File(it, "received") }
        return ReceivedFileDelivery(destinations) {
            append("transfer cleanup: verified source retained for age-based cleanup")
        }.deliver(completed)
    }

    private fun transferCompletedLine(completed: TransferCompleted, delivered: File): String =
        "transfer received: ${completed.manifest.filename} (${completed.manifest.sizeBytes} bytes) -> ${delivered.name}"

    private fun presentTextLinkConfirmation(request: TextLinkConfirmationRequest) {
        val isUrl = request.message.kind == TextLinkKind.URL
        AlertDialog.Builder(this)
            .setTitle(if (isUrl) R.string.dlg_link_title else R.string.dlg_text_title)
            .setMessage(request.message.value)
            .setPositiveButton(if (isUrl) R.string.dlg_link_open else R.string.dlg_text_copy) { _, _ ->
                textReceiver.resolve(request.messageId)
                if (isUrl) {
                    openConfirmedLink(request.message.value)
                } else {
                    copyConfirmedText(request.message.value)
                }
            }
            .setNegativeButton(R.string.dlg_cancel) { _, _ ->
                textReceiver.resolve(request.messageId)
                append("text/link declined")
            }
            .show()
    }

    private fun copyConfirmedText(value: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Hyphen text", value))
        append("text copied from Mac")
        emit(ActivityEvent.Text(TextKind.TEXT, TextDirection.RECEIVED, value, System.currentTimeMillis()))
    }

    private fun openConfirmedLink(value: String) {
        val uri = Uri.parse(value)
        if (!TextLinkMessage.isAllowedOpenUrl(value, uri.scheme)) {
            append("link open rejected: url must use http or https")
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
            append("link opened from Mac")
            emit(ActivityEvent.Text(TextKind.URL, TextDirection.RECEIVED, value, System.currentTimeMillis()))
        } catch (e: ActivityNotFoundException) {
            append("link open failed: ${e.message}")
        }
    }

    private fun sendTextLink(raw: String) {
        val state = activeStateSnapshot()
        val session = state.session
        if (session == null) {
            append("text/link: no active Mac session")
            return
        }
        if (state.capabilities?.contains(SessionHandshake.CAPABILITY_TEXT) != true) {
            append("text/link: peer did not negotiate text.v1")
            return
        }
        val message = try {
            TextLinkMessage.fromUserInput(raw)
        } catch (e: IllegalArgumentException) {
            append("text/link rejected: ${e.message}")
            return
        }
        launchWorker("hyphen-send-text-link") {
            try {
                val id = TextLinkSender(ProtocolSessionTextLinkOutbox(session)).send(message)
                append("text/link sent: $id")
                val kind = if (message.kind == TextLinkKind.URL) TextKind.URL else TextKind.TEXT
                emit(ActivityEvent.Text(kind, TextDirection.SENT, message.value, System.currentTimeMillis()))
            } catch (e: Exception) {
                append("text/link send failed: ${e.message}")
            }
        }
    }

    private fun showNotificationAccessOnboarding() {
        val status = NotificationAccessController.forContext(this).status()
        if (status.enabled) {
            append("notification access: already enabled (${status.componentName})")
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dlg_notif_onboard_title)
            .setMessage(R.string.dlg_notif_onboard_msg)
            .setPositiveButton(R.string.dlg_notif_open_settings) { _, _ -> openNotificationSettings() }
            .setNegativeButton(R.string.dlg_notif_not_now) { _, _ ->
                append("notification access: settings not opened")
            }
            .show()
    }

    private fun openNotificationSettings() {
        try {
            startActivity(NotificationAccessController.settingsIntent())
            append("notification access: opened system settings")
        } catch (e: ActivityNotFoundException) {
            append("notification access: settings unavailable (${e.message})")
        }
    }

    private fun notificationAccessLine(): String {
        val status = NotificationAccessController.forContext(this).status()
        return "notification access: ${if (status.enabled) "enabled" else "disabled"}; " +
            "listener=${HyphenNotificationListenerRuntime.state()}; " +
            "component=${status.componentName}"
    }

    private fun cycleNotificationPrivacy() {
        val next = when (HyphenNotificationListenerRuntime.notificationPrivacyMode()) {
            NotificationPrivacyMode.SHOW_FULL -> NotificationPrivacyMode.HIDE_BODY
            NotificationPrivacyMode.HIDE_BODY -> NotificationPrivacyMode.EXISTS_ONLY
            NotificationPrivacyMode.EXISTS_ONLY -> NotificationPrivacyMode.SHOW_FULL
        }
        HyphenNotificationListenerRuntime.setNotificationPrivacyMode(next)
        model.updateNotificationPrivacyLabel(notificationPrivacyStatus(next))
        append("notification privacy: ${notificationPrivacyStatus(next)}")
    }

    private fun notificationPrivacyStatus(mode: NotificationPrivacyMode): String =
        when (mode) {
            NotificationPrivacyMode.SHOW_FULL -> getString(R.string.privacy_full)
            NotificationPrivacyMode.HIDE_BODY -> getString(R.string.privacy_hide_body)
            NotificationPrivacyMode.EXISTS_ONLY -> getString(R.string.privacy_exists_only)
        }

    private fun toggleBetaDiagnostics() {
        if (betaDiagnosticsEnabled()) {
            setBetaDiagnosticsEnabled(false)
            append("beta diagnostics: disabled")
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dlg_beta_title)
            .setMessage(R.string.dlg_beta_msg)
            .setPositiveButton(R.string.dlg_beta_enable) { _, _ ->
                setBetaDiagnosticsEnabled(true)
                append("beta diagnostics: enabled")
            }
            .setNegativeButton(R.string.dlg_cancel) { _, _ ->
                append("beta diagnostics: unchanged (off)")
            }
            .show()
    }

    private fun setBetaDiagnosticsEnabled(enabled: Boolean) {
        getSharedPreferences(DIAGNOSTICS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_BETA_DIAGNOSTICS_ENABLED, enabled)
            .apply()
        model.setBetaDiagnostics(enabled)
    }

    private fun betaDiagnosticsEnabled(): Boolean =
        getSharedPreferences(DIAGNOSTICS_PREFS, Context.MODE_PRIVATE)
            .getBoolean(PREF_BETA_DIAGNOSTICS_ENABLED, false)

    private fun betaDiagnosticsStatus(): String =
        getString(if (betaDiagnosticsEnabled()) R.string.diag_beta_on else R.string.diag_beta_off)

    private fun previewDiagnostics() {
        val json = diagnosticsExporter().previewJson()
        AlertDialog.Builder(this)
            .setTitle(R.string.dlg_diag_preview_title)
            .setMessage(json)
            .setPositiveButton(R.string.dlg_ok, null)
            .show()
        append("diagnostics preview: ${diagnosticLogs.snapshot().size} event(s), beta ${betaDiagnosticsStatus()}")
    }

    private fun exportDiagnostics() {
        val json = diagnosticsExporter().exportText()
        val intent = Intent(Intent.ACTION_SEND)
            .setType("application/json")
            .putExtra(Intent.EXTRA_SUBJECT, "Hyphen diagnostics")
            .putExtra(Intent.EXTRA_TEXT, json)
        try {
            startActivity(Intent.createChooser(intent, "Export Hyphen diagnostics"))
            append("diagnostics export: chooser opened")
        } catch (e: ActivityNotFoundException) {
            append("diagnostics export failed: ${e.message}")
        }
    }

    private fun deleteDiagnostics() {
        val count = diagnosticLogs.snapshot().size
        diagnosticsExporter().deleteLocalDiagnostics()
        append("diagnostics deleted: $count event(s)")
    }

    private fun diagnosticsExporter(): RedactedDiagnosticsExporter =
        RedactedDiagnosticsExporter(
            logs = diagnosticLogs,
            appVersion = appVersionName(),
            sdkInt = Build.VERSION.SDK_INT,
            includeTraceIds = betaDiagnosticsEnabled(),
        )

    @Suppress("DEPRECATION")
    private fun appVersionName(): String =
        packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"

    private fun startWindow() {
        if (discoveryActive) {
            append("discovery already running")
            return
        }
        synchronized(discoveredServices) { discoveredServices.clear() }
        val m = DiscoveryManager(
            backend = AndroidNsdBackend(applicationContext),
            scheduler = HandlerScheduler(),
            lock = ScopedMulticastLock(AndroidMulticastLockHandle(applicationContext)) {
                append("multicast lock: $it")
            },
            onEvent = ::render,
        )
        manager = m
        if (m.start()) {
            discoveryActive = true
            append("discovery started (${DiscoveryManager.SERVICE_TYPE})")
        }
    }

    private fun render(event: DiscoveryEvent) {
        when (event) {
            is DiscoveryEvent.ServiceResolved -> {
                synchronized(discoveredServices) {
                    discoveredServices[event.service.name] = event.service
                }
                append("resolved: ${event.service.name} @ ${event.service.host}:${event.service.port}")
            }
            is DiscoveryEvent.ServiceLost -> {
                synchronized(discoveredServices) { discoveredServices.remove(event.name) }
                append("lost: ${event.name}")
            }
            is DiscoveryEvent.Failed -> {
                append("failure: ${event.reason}")
                // START_FAILED unwinds without stopDiscovery() and STOP_FAILED
                // replaces onStopped(), so neither emits WindowEnded — clear the
                // in-progress latch here too, or the "Find Mac" button stays a
                // silent no-op until the app restarts. RESOLVE_FAILED /
                // ALREADY_RUNNING leave a window running, so they don't reset it.
                if (event.reason == DiscoveryFailure.START_FAILED ||
                    event.reason == DiscoveryFailure.STOP_FAILED
                ) {
                    postToUi {
                        discoveryActive = false
                        showDiscoverySummary()
                    }
                }
            }
            is DiscoveryEvent.WindowEnded -> {
                append("window ended, resolved=${event.resolvedCount}")
                postToUi {
                    discoveryActive = false
                    showDiscoverySummary()
                }
            }
        }
    }

    private fun showDiscoverySummary() {
        val services = synchronized(discoveredServices) { discoveredServices.values.toList() }
        if (services.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.dlg_discovery_none_title)
                .setMessage(R.string.dlg_discovery_none_msg)
                .setPositiveButton(R.string.perm_btn_qr_help) { _, _ -> showQrHelp() }
                .setNegativeButton(R.string.dlg_close, null)
                .show()
            return
        }
        val names = services.joinToString("\n") { "• ${it.name}" }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dlg_discovery_found_title, services.size))
            .setMessage(getString(R.string.dlg_discovery_found_msg, names))
            .setPositiveButton(R.string.perm_btn_qr_help) { _, _ -> showQrHelp() }
            .setNegativeButton(R.string.dlg_close, null)
            .show()
    }

    private fun append(line: String) {
        postToUi {
            logBuffer.append(line)
            model.setLog(logBuffer.snapshot())
        }
    }

    override fun onDestroy() {
        val workers = synchronized(workerLock) {
            activityDestroyed = true
            activeWorkers.toList().also { activeWorkers.clear() }
        }
        workers.forEach { it.interrupt() }
        AssociationAttemptRuntime.coordinator.detachApprovalListener(associationApprovalListener)
        pairingAttemptToken.get()?.let { cancelPairingAttempt(it) }
        supervisor.removeListener(sessionListener)
        super.onDestroy()
        manager?.stop()
    }

    override fun onStop() {
        if (!isChangingConfigurations &&
            !CompanionConnectionServiceController.keepConnected(this)
        ) {
            pairingAttemptToken.get()?.let { cancelPairingAttempt(it) }
            supervisor.pauseConnection()
        }
        super.onStop()
    }

    private companion object {
        const val DIAGNOSTICS_PREFS = "dev.hyphen.android.diagnostics"
        const val PREF_BETA_DIAGNOSTICS_ENABLED = "beta_diagnostics_enabled"
        const val REQUEST_PICK_FILE = 4201
        const val DEFAULT_TRANSFER_CHUNK_BYTES = 256 * 1024
        const val MAX_LOG_LINES = 500
        const val STATE_CONSUMED_PAIRING_TOKEN = "consumed_pairing_token"
    }
}
