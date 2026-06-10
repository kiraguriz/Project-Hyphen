package dev.hyphen.android

import android.app.Activity
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
import dev.hyphen.android.pairing.EndpointConnectProbe
import dev.hyphen.android.pairing.EndpointParser
import dev.hyphen.android.pairing.ParseResult

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
        // disabled or the LAN permission denied.
        val endpointInput = EditText(this).apply {
            hint = "Manual endpoint host:port"
        }
        val connectButton = Button(this).apply {
            text = "Test manual endpoint"
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

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 96, 48, 48)
                addView(button)
                addView(endpointInput)
                addView(connectButton)
                addView(associateButton)
                addView(listButton)
                addView(ScrollView(this@MainActivity).apply { addView(log) })
            }
        )
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
        when (val parsed = EndpointParser.parseManual(raw)) {
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
