package dev.hyphen.android

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.hyphen.android.companion.AssociationController
import dev.hyphen.android.companion.AssociationEvent
import dev.hyphen.android.companion.CdmAssociationBackend
import dev.hyphen.android.diagnostics.DiagnosticProtocolSessionListener
import dev.hyphen.android.diagnostics.LocalStructuredLogStore
import dev.hyphen.android.diagnostics.RedactedDiagnosticsExporter
import dev.hyphen.android.discovery.AndroidMulticastLockHandle
import dev.hyphen.android.discovery.AndroidNsdBackend
import dev.hyphen.android.discovery.DiscoveryEvent
import dev.hyphen.android.discovery.DiscoveryManager
import dev.hyphen.android.discovery.HandlerScheduler
import dev.hyphen.android.discovery.ScopedMulticastLock
import dev.hyphen.android.notifications.HyphenNotificationListenerRuntime
import dev.hyphen.android.notifications.NotificationAccessController
import dev.hyphen.android.notifications.NotificationDismissRequestHandler
import dev.hyphen.android.notifications.NotificationPrivacyMode
import dev.hyphen.android.notifications.ProtocolSessionNotificationOutbox
import dev.hyphen.android.pairing.EndpointConnectProbe
import dev.hyphen.android.pairing.EndpointParser
import dev.hyphen.android.pairing.PairingTranscript
import dev.hyphen.android.pairing.ParseResult
import dev.hyphen.android.pairing.ParsedEndpoint
import dev.hyphen.android.pairing.SasConfirmationGate
import dev.hyphen.android.text.ProtocolSessionTextLinkOutbox
import dev.hyphen.android.text.TextLinkConfirmationRequest
import dev.hyphen.android.text.TextLinkKind
import dev.hyphen.android.text.TextLinkMessage
import dev.hyphen.android.text.TextLinkReceiver
import dev.hyphen.android.text.TextLinkSender
import dev.hyphen.android.transport.AndroidKeystoreTlsIdentity
import dev.hyphen.android.transport.Envelope
import dev.hyphen.android.transport.HeartbeatMonitor
import dev.hyphen.android.transport.ProtocolSession
import dev.hyphen.android.transport.SessionHandshake
import dev.hyphen.android.transport.TlsClient
import dev.hyphen.android.trust.AndroidTrustStores
import javax.net.ssl.SSLSocket

// Plain-view debug surface for the M1 PoCs; Compose arrives with the first
// real UI task (plan §7.2). One tap runs one discovery window (HYP-M1-004).
class MainActivity : Activity() {

    private var manager: DiscoveryManager? = null
    private lateinit var log: TextView
    private lateinit var button: Button
    private var activeSession: ProtocolSession? = null
    private var resumeToken: String? = null
    private var lastSessionId: String? = null
    private val textReceiver = TextLinkReceiver()
    private val diagnosticLogs = LocalStructuredLogStore()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        log = TextView(this).apply {
            text = "Hyphen pre-alpha — NSD discovery PoC (HYP-M1-004)\n"
            textSize = 14f
        }
        button = Button(this).apply {
            text = "Start discovery window (20s)"
            setOnClickListener { startWindow() }
        }

        // Manual-endpoint fallback path (HYP-M1-006): works with mDNS
        // disabled or the LAN permission denied. A pasted hyphen://pair
        // payload takes the QR parse path (HYP-M2-010) — same code a
        // camera scan will feed once the scanner-library decision lands.
        val endpointInput = EditText(this).apply {
            hint = "host:port or hyphen://pair payload"
        }
        val connectButton = Button(this).apply {
            text = "Test manual endpoint / QR payload"
            setOnClickListener { probeManualEndpoint(endpointInput.text.toString()) }
        }

        // CDM self-managed association PoC (HYP-M1-007).
        val cdm = AssociationController(CdmAssociationBackend(this), ::renderCdm)
        val associateButton = Button(this).apply {
            text = "CDM associate (self-managed)"
            setOnClickListener { cdm.associate("Hyphen Mac") }
        }
        val listButton = Button(this).apply {
            text = "CDM list + disassociate all"
            setOnClickListener {
                val ids = cdm.associations()
                append("associations: $ids")
                ids.forEach(cdm::disassociate)
            }
        }

        // Notification listener onboarding (HYP-M3-001): explicit user
        // action, rationale before system settings, no payload forwarding yet.
        val notificationStatusButton = Button(this).apply {
            text = "Check notification listener"
            setOnClickListener { append(notificationAccessLine()) }
        }
        val notificationSettingsButton = Button(this).apply {
            text = "Enable notification mirror"
            setOnClickListener { showNotificationAccessOnboarding() }
        }
        val notificationPrivacyButton = Button(this).apply {
            text = notificationPrivacyButtonText(HyphenNotificationListenerRuntime.notificationPrivacyMode())
            setOnClickListener {
                val next = when (HyphenNotificationListenerRuntime.notificationPrivacyMode()) {
                    NotificationPrivacyMode.SHOW_FULL -> NotificationPrivacyMode.HIDE_BODY
                    NotificationPrivacyMode.HIDE_BODY -> NotificationPrivacyMode.SHOW_FULL
                }
                HyphenNotificationListenerRuntime.setNotificationPrivacyMode(next)
                text = notificationPrivacyButtonText(next)
                append("notification privacy: ${notificationPrivacyStatus(next)}")
            }
        }

        val textInput = EditText(this).apply {
            hint = "Text or https:// link to send to Mac"
        }
        val sendTextButton = Button(this).apply {
            text = "Send text/link to Mac"
            setOnClickListener { sendTextLink(textInput.text.toString()) }
        }
        val previewDiagnosticsButton = Button(this).apply {
            text = "Preview diagnostics"
            setOnClickListener { previewDiagnostics() }
        }
        val exportDiagnosticsButton = Button(this).apply {
            text = "Export diagnostics"
            setOnClickListener { exportDiagnostics() }
        }
        val deleteDiagnosticsButton = Button(this).apply {
            text = "Delete diagnostics"
            setOnClickListener { deleteDiagnostics() }
        }

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 96, 48, 48)
                addView(button)
                addView(endpointInput)
                addView(connectButton)
                addView(associateButton)
                addView(listButton)
                addView(notificationStatusButton)
                addView(notificationSettingsButton)
                addView(notificationPrivacyButton)
                addView(textInput)
                addView(sendTextButton)
                addView(previewDiagnosticsButton)
                addView(exportDiagnosticsButton)
                addView(deleteDiagnosticsButton)
                addView(ScrollView(this@MainActivity).apply { addView(log) })
            }
        )
    }

    override fun onResume() {
        super.onResume()
        if (::log.isInitialized) append(notificationAccessLine())
    }

    private fun renderCdm(event: AssociationEvent) {
        when (event) {
            is AssociationEvent.PendingUserApproval -> {
                append("cdm: launching system approval dialog")
                event.launch()
            }
            is AssociationEvent.Associated ->
                append("cdm associated: id=${event.associationId} name=${event.displayName}")
            is AssociationEvent.Removed -> append("cdm removed: id=${event.associationId}")
            is AssociationEvent.Failed -> append("cdm failed: ${event.message}")
            AssociationEvent.UnsupportedOnThisSdk ->
                append("cdm: self-managed association needs API 33+ (QR-only on this device)")
        }
    }

    private fun probeManualEndpoint(raw: String) {
        val isQr = raw.trim().startsWith("hyphen://")
        val result = if (isQr) EndpointParser.parseQr(raw) else EndpointParser.parseManual(raw)
        if (isQr && result is ParseResult.Ok) {
            val qr = result.endpoint as ParsedEndpoint.QrPayload
            val fpHead = qr.decodedFingerprint().take(4).joinToString("") { "%02x".format(it) }
            append("qr parsed: v=${qr.version} dn=${qr.deviceName ?: "—"} fp=$fpHead… nonce ok")
            startPairing(qr)
            return
        }
        when (val parsed = result) {
            is ParseResult.Rejected -> append("endpoint rejected: ${parsed.reason}")
            is ParseResult.Ok -> {
                append("probing ${parsed.endpoint.host}:${parsed.endpoint.port} …")
                Thread {
                    val result = EndpointConnectProbe().probe(parsed.endpoint)
                    runOnUiThread {
                        when (result) {
                            is EndpointConnectProbe.Result.Connected ->
                                append("connected: ${result.host}:${result.port}")
                            is EndpointConnectProbe.Result.Failed ->
                                append("connect failed: ${result.reason}")
                        }
                    }
                }.start()
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
        append("pairing: provisional TLS to ${qr.host}:${qr.port} …")
        Thread {
            try {
                val identity = AndroidKeystoreTlsIdentity.getOrCreate()
                val macFp = qr.decodedFingerprint()
                val socket = TlsClient.connect(
                    host = qr.host,
                    port = qr.port,
                    identity = identity,
                    isTrusted = { it.contentEquals(macFp) },
                )
                val transcript = PairingTranscript.create(
                    nonce = qr.decodedNonce(),
                    macSpkiFingerprint = macFp,
                    androidSpkiFingerprint = identity.spkiFingerprint,
                    protocolVersion = PairingTranscript.PROTOCOL_VERSION,
                )!!
                val gate = SasConfirmationGate(
                    transcript = transcript,
                    peerFingerprint = macFp,
                    peerDisplayName = qr.deviceName ?: "Mac",
                    trustStore = AndroidTrustStores.openDefault(applicationContext),
                )
                runOnUiThread { presentSasDialog(qr, gate, socket) }
            } catch (e: Exception) {
                runOnUiThread { append("pairing failed: ${e.message}") }
            }
        }.start()
    }

    private fun presentSasDialog(qr: ParsedEndpoint.QrPayload, gate: SasConfirmationGate, socket: SSLSocket) {
        fun closeSocket() = Thread { runCatching { socket.close() } }.start()
        AlertDialog.Builder(this)
            .setTitle("Confirm pairing code")
            .setMessage("Code: ${gate.sas}\n\nTrust this Mac only if it shows the same code.")
            .setPositiveButton("Codes match — trust") { _, _ ->
                gate.confirm()
                append("paired — fingerprint pinned (${gate.sas})")
                startSteadySession(qr, socket)
            }
            .setNegativeButton("Reject") { _, _ ->
                gate.reject()
                append("pairing rejected — nothing stored")
                closeSocket()
            }
            .setCancelable(false)
            .show()
    }

    private fun startSteadySession(qr: ParsedEndpoint.QrPayload, socket: SSLSocket) {
        Thread {
            try {
                val device = SessionHandshake.DeviceInfo(
                    kind = "android",
                    appVersion = "0.0.1",
                    deviceName = Build.MODEL,
                )
                val handshake = SessionHandshake.initiate(
                    socket = socket,
                    device = device,
                    resumeToken = resumeToken,
                    previousSessionId = lastSessionId,
                )
                resumeToken = handshake.resumeToken
                lastSessionId = handshake.sessionId
                lateinit var session: ProtocolSession
                val listener = object : ProtocolSession.Listener {
                    override fun onLiveness(state: HeartbeatMonitor.State) {
                        runOnUiThread { append("session liveness: $state") }
                    }

                    override fun onProtocolError(code: String, detail: String) {
                        runOnUiThread { append("session protocol error: $code $detail") }
                    }

                    override fun onEnvelope(envelope: Envelope) {
                        handleSessionEnvelope(envelope)
                    }

                    override fun onClosed() {
                        if (activeSession === session) {
                            activeSession = null
                            HyphenNotificationListenerRuntime.clearNotificationOutbox()
                        }
                        runOnUiThread { append("Mac session closed") }
                    }
                }
                session = ProtocolSession(
                    socket = socket,
                    sessionId = handshake.sessionId,
                    config = ProtocolSession.Config(startingSeq = 1),
                    listener = DiagnosticProtocolSessionListener(diagnosticLogs, listener),
                )
                activeSession?.stop()
                activeSession = session
                HyphenNotificationListenerRuntime.bindNotificationOutbox(
                    ProtocolSessionNotificationOutbox(session),
                )
                session.start()
                runOnUiThread {
                    append("session connected to ${handshake.peerDevice?.deviceName ?: qr.deviceName ?: "Mac"}")
                }
            } catch (e: Exception) {
                runCatching { socket.close() }
                runOnUiThread { append("session failed: ${e.message}") }
            }
        }.start()
    }

    private fun handleSessionEnvelope(envelope: Envelope) {
        try {
            val session = activeSession
            if (session != null) {
                val dismissResultId = NotificationDismissRequestHandler(
                    canceller = HyphenNotificationListenerRuntime.notificationCanceller(),
                    outbox = ProtocolSessionNotificationOutbox(session),
                ).handle(envelope)
                if (dismissResultId != null) {
                    runOnUiThread { append("notification dismiss result sent: $dismissResultId") }
                    return
                }
            }
            val request = textReceiver.handle(envelope) ?: return
            runOnUiThread { presentTextLinkConfirmation(request) }
        } catch (e: IllegalArgumentException) {
            runOnUiThread { append("session envelope rejected: ${e.message}") }
        }
    }

    private fun presentTextLinkConfirmation(request: TextLinkConfirmationRequest) {
        val isUrl = request.message.kind == TextLinkKind.URL
        AlertDialog.Builder(this)
            .setTitle(if (isUrl) "Open link from Mac?" else "Copy text from Mac?")
            .setMessage(request.message.value)
            .setPositiveButton(if (isUrl) "Open" else "Copy") { _, _ ->
                if (isUrl) {
                    openConfirmedLink(request.message.value)
                } else {
                    copyConfirmedText(request.message.value)
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                append("text/link declined")
            }
            .show()
    }

    private fun copyConfirmedText(value: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Hyphen text", value))
        append("text copied from Mac")
    }

    private fun openConfirmedLink(value: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value)))
            append("link opened from Mac")
        } catch (e: ActivityNotFoundException) {
            append("link open failed: ${e.message}")
        }
    }

    private fun sendTextLink(raw: String) {
        val session = activeSession
        if (session == null) {
            append("text/link: no active Mac session")
            return
        }
        val trimmed = raw.trim()
        val message = try {
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                TextLinkMessage.url(trimmed)
            } else {
                TextLinkMessage.text(trimmed)
            }
        } catch (e: IllegalArgumentException) {
            append("text/link rejected: ${e.message}")
            return
        }
        Thread {
            try {
                val id = TextLinkSender(ProtocolSessionTextLinkOutbox(session)).send(message)
                runOnUiThread { append("text/link sent: $id") }
            } catch (e: Exception) {
                runOnUiThread { append("text/link send failed: ${e.message}") }
            }
        }.start()
    }

    private fun showNotificationAccessOnboarding() {
        val status = NotificationAccessController.forContext(this).status()
        if (status.enabled) {
            append("notification access: already enabled (${status.componentName})")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Enable notification mirror")
            .setMessage(
                "Hyphen can mirror Android notifications to a paired Mac over local TLS. " +
                    "It does not request SMS or Call Log access, does not store notification " +
                    "history, and does not use a cloud relay.",
            )
            .setPositiveButton("Open settings") { _, _ -> openNotificationSettings() }
            .setNegativeButton("Not now") { _, _ ->
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

    private fun notificationPrivacyButtonText(mode: NotificationPrivacyMode): String =
        "Notification privacy: ${notificationPrivacyStatus(mode)}"

    private fun notificationPrivacyStatus(mode: NotificationPrivacyMode): String =
        when (mode) {
            NotificationPrivacyMode.SHOW_FULL -> "full"
            NotificationPrivacyMode.HIDE_BODY -> "hidden body"
        }

    private fun previewDiagnostics() {
        val json = diagnosticsExporter().previewJson()
        AlertDialog.Builder(this)
            .setTitle("Diagnostics preview")
            .setMessage(json)
            .setPositiveButton("OK", null)
            .show()
        append("diagnostics preview: ${diagnosticLogs.snapshot().size} event(s)")
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
        )

    @Suppress("DEPRECATION")
    private fun appVersionName(): String =
        packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"

    private fun startWindow() {
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
            button.isEnabled = false
            append("discovery started (${DiscoveryManager.SERVICE_TYPE})")
        }
    }

    private fun render(event: DiscoveryEvent) {
        when (event) {
            is DiscoveryEvent.ServiceResolved ->
                append("resolved: ${event.service.name} @ ${event.service.host}:${event.service.port}")
            is DiscoveryEvent.ServiceLost -> append("lost: ${event.name}")
            is DiscoveryEvent.Failed -> append("failure: ${event.reason}")
            is DiscoveryEvent.WindowEnded -> {
                append("window ended, resolved=${event.resolvedCount}")
                button.isEnabled = true
            }
        }
    }

    private fun append(line: String) {
        log.text = "${log.text}$line\n"
    }

    override fun onDestroy() {
        super.onDestroy()
        HyphenNotificationListenerRuntime.clearNotificationOutbox()
        activeSession?.stop()
        activeSession = null
        manager?.stop()
    }
}
