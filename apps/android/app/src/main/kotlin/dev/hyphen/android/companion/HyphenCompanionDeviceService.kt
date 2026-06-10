package dev.hyphen.android.companion

import android.annotation.TargetApi
import android.companion.AssociationInfo
import android.companion.CompanionDeviceManager
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.os.Build

/**
 * Maps a [DevicePresenceEvent] type to appeared(true)/disappeared(false);
 * null for event types Hyphen does not consume. Pure for JVM testing.
 */
@TargetApi(36)
fun presenceEventToAppeared(eventType: Int): Boolean? = when (eventType) {
    DevicePresenceEvent.EVENT_BLE_APPEARED,
    DevicePresenceEvent.EVENT_BT_CONNECTED,
    DevicePresenceEvent.EVENT_SELF_MANAGED_APPEARED,
    -> true

    DevicePresenceEvent.EVENT_BLE_DISAPPEARED,
    DevicePresenceEvent.EVENT_BT_DISCONNECTED,
    DevicePresenceEvent.EVENT_SELF_MANAGED_DISAPPEARED,
    -> false

    else -> null
}

/**
 * System-bound companion service (HYP-M1-009 spike). While an observed
 * peer is present, the system keeps this service bound — that binding is
 * the background-resilience win CDM offers self-managed companions.
 * Findings and open questions: docs/spikes/api36-device-presence.md.
 */
class HyphenCompanionDeviceService : CompanionDeviceService() {

    @TargetApi(36)
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        // Do not call super: the API 36 default routes to the deprecated
        // address-based callbacks, which Hyphen does not use.
        val appeared = presenceEventToAppeared(event.event) ?: return
        DevicePresenceBridge.dispatch(event.associationId, appeared)
    }

    // Pre-36 address/AssociationInfo callbacks (31–35): self-managed
    // presence is app-driven there, so nothing arrives for Hyphen peers.
    @Deprecated("pre-API36 path; Hyphen peers are self-managed")
    override fun onDeviceAppeared(associationInfo: AssociationInfo) = Unit

    @Deprecated("pre-API36 path; Hyphen peers are self-managed")
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) = Unit
}

/** API 36+ start/stop of system presence observation for an association. */
class Api36PresenceObservation(context: Context) {

    private val cdm =
        context.getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager

    fun supported(): Boolean = Build.VERSION.SDK_INT >= 36

    @TargetApi(36)
    fun startObserving(associationId: Int) {
        if (!supported()) return
        cdm.startObservingDevicePresence(
            ObservingDevicePresenceRequest.Builder().setAssociationId(associationId).build()
        )
    }

    @TargetApi(36)
    fun stopObserving(associationId: Int) {
        if (!supported()) return
        cdm.stopObservingDevicePresence(
            ObservingDevicePresenceRequest.Builder().setAssociationId(associationId).build()
        )
    }
}
