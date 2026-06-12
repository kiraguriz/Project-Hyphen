package dev.hyphen.android

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.hyphen.android.companion.AssociationController
import dev.hyphen.android.companion.AssociationEvent
import dev.hyphen.android.companion.CdmAssociationBackend
import dev.hyphen.android.discovery.AndroidMulticastLockHandle
import dev.hyphen.android.discovery.AndroidNsdBackend
import dev.hyphen.android.discovery.DiscoveryEvent
import dev.hyphen.android.discovery.DiscoveryManager
import dev.hyphen.android.discovery.HandlerScheduler
import dev.hyphen.android.discovery.ScopedMulticastLock
import dev.hyphen.android.notifications.HyphenNotificationListenerRuntime
import dev.hyphen.android.notifications.NotificationAccessController
import dev.hyphen.android.pairing.EndpointConnectProbe
import dev.hyphen.android.pairing.EndpointParser
import dev.hyphen.android.pairing.PairingTranscript
import dev.hyphen.android.pairing.ParseResult
import dev.hyphen.android.pairing.ParsedEndpoint
import dev.hyphen.android.pairing.SasConfirmationGate
import dev.hyphen.android.transport.AndroidKeystoreTlsIdentity
import dev.hyphen.android.transport.TlsClient
import dev.hyphen.android.trust.AndroidTrustStores
import javax.net.ssl.SSLSocket

// Plain-view debug surface for the M1 PoCs; Compose arrives with the first
// real UI task (plan §7.2). One tap runs one discovery window (HYP-M1-004).
class MainActivity : Activity() {

    private var manager: DiscoveryManager? = null
    private lateinit var log: TextView
    private lateinit var button: Button

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
     * The provisional socket closes after the decision — steady-state
     * sessions arrive with M2-012/013.
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
                runOnUiThread { presentSasDialog(gate, socket) }
            } catch (e: Exception) {
                runOnUiThread { append("pairing failed: ${e.message}") }
            }
        }.start()
    }

    private fun presentSasDialog(gate: SasConfirmationGate, socket: SSLSocket) {
        fun closeSocket() = Thread { runCatching { socket.close() } }.start()
        AlertDialog.Builder(this)
            .setTitle("Confirm pairing code")
            .setMessage("Code: ${gate.sas}\n\nTrust this Mac only if it shows the same code.")
            .setPositiveButton("Codes match — trust") { _, _ ->
                gate.confirm()
                append("paired — fingerprint pinned (${gate.sas})")
                closeSocket()
            }
            .setNegativeButton("Reject") { _, _ ->
                gate.reject()
                append("pairing rejected — nothing stored")
                closeSocket()
            }
            .setCancelable(false)
            .show()
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
        manager?.stop()
    }
}
