package dev.hyphen.android

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.hyphen.android.discovery.AndroidNsdBackend
import dev.hyphen.android.discovery.DiscoveryEvent
import dev.hyphen.android.discovery.DiscoveryManager
import dev.hyphen.android.discovery.HandlerScheduler

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

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 96, 48, 48)
                addView(button)
                addView(ScrollView(this@MainActivity).apply { addView(log) })
            }
        )
    }

    private fun startWindow() {
        val m = DiscoveryManager(
            backend = AndroidNsdBackend(applicationContext),
            scheduler = HandlerScheduler(),
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
