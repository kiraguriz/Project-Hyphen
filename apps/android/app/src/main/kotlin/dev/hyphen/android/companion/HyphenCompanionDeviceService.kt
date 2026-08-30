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
 * Compile-only API-shape artifact from HYP-M1-009. It is intentionally not
 * registered in the product manifest: public observation is BLE/BT-only and
 * third-party apps cannot report self-managed Wi-Fi/LAN presence. It provides
 * neither v1 lifecycle ownership nor background-resilience evidence.
 */
class HyphenCompanionDeviceService : CompanionDeviceService() {

    @TargetApi(36)
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        // Do not call super: the API 36 default routes to the deprecated
        // address-based callbacks, which Hyphen does not use.
        val appeared = presenceEventToAppeared(event.event) ?: return
        DevicePresenceBridge.dispatch(event.associationId, appeared)
    }

    // Pre-36 address/AssociationInfo callbacks do not provide a compatible
    // public Wi-Fi/LAN presence feed for Hyphen peers.
    @Deprecated("pre-API36 path; Hyphen peers are self-managed")
    override fun onDeviceAppeared(associationInfo: AssociationInfo) = Unit

    @Deprecated("pre-API36 path; Hyphen peers are self-managed")
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) = Unit
}

/** Compile-only API 36 BLE/BT observation shape; not a Hyphen LAN product path. */
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
