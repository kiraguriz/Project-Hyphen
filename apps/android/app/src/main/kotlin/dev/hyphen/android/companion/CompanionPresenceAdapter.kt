package dev.hyphen.android.companion

/**
 * CDM presence layering (HYP-M1-008, plan §7.4). Presence events are
 * reconnect hints, never trust sources — the Hyphen trust store decides
 * peers. The plan sketches suspend/Flow signatures; this lands the same
 * abstraction callback-based because the project has no coroutines
 * dependency yet (migration noted for the M2 module split).
 */
data class PeerId(val value: String)

data class HyphenAssociationRequest(val peerId: PeerId, val displayName: String)

sealed class PresenceAssociationResult {
    data class Associated(val associationId: Int) : PresenceAssociationResult()

    /** User approval dialog required; invoke [launch] from an Activity. */
    data class PendingUserApproval(val launch: () -> Unit) : PresenceAssociationResult()
    data class Failed(val message: String) : PresenceAssociationResult()
    object Unsupported : PresenceAssociationResult()
}

sealed class PresenceEvent {
    data class Appeared(val peerId: PeerId) : PresenceEvent()
    data class Disappeared(val peerId: PeerId) : PresenceEvent()
}

fun interface PresenceListener {
    fun onPresenceEvent(event: PresenceEvent)
}

interface PresenceSubscription {
    fun cancel()
}

interface CompanionPresenceAdapter {
    fun associate(request: HyphenAssociationRequest, onResult: (PresenceAssociationResult) -> Unit)
    fun observePresence(peerId: PeerId, listener: PresenceListener): PresenceSubscription
    fun disassociate(peerId: PeerId, onResult: (Boolean) -> Unit)
}

/** Platform feed for API 36+ device-presence events (real impl: HYP-M1-009 spike). */
interface PresenceSource {
    fun startObserving(peerId: PeerId, onEvent: (PresenceEvent) -> Unit)
    fun stopObserving(peerId: PeerId)
}

object CompanionPresenceAdapterFactory {
    const val API36 = 36

    fun forSdk(
        sdkInt: Int,
        backend: CdmBackend,
        presenceSource: PresenceSource,
    ): CompanionPresenceAdapter =
        if (sdkInt >= API36) {
            Api36PresenceAdapter(backend, presenceSource)
        } else {
            LegacyPresenceAdapter(backend)
        }
}

/** Shared CDM-backed association/disassociation with peerId bookkeeping. */
abstract class CdmBackedPresenceAdapter(
    private val backend: CdmBackend,
) : CompanionPresenceAdapter {

    private val associationIds = mutableMapOf<PeerId, Int>()

    final override fun associate(
        request: HyphenAssociationRequest,
        onResult: (PresenceAssociationResult) -> Unit,
    ) {
        if (backend.sdkInt() < AssociationController.SELF_MANAGED_MIN_SDK) {
            onResult(PresenceAssociationResult.Unsupported)
            return
        }
        backend.requestSelfManagedAssociation(
            request.displayName,
            object : CdmCallbacks {
                override fun onPendingApproval(launch: () -> Unit) {
                    onResult(PresenceAssociationResult.PendingUserApproval(launch))
                }

                override fun onAssociated(associationId: Int, displayName: String?) {
                    associationIds[request.peerId] = associationId
                    onResult(PresenceAssociationResult.Associated(associationId))
                }

                override fun onFailure(message: String) {
                    onResult(PresenceAssociationResult.Failed(message))
                }
            },
        )
    }

    final override fun disassociate(peerId: PeerId, onResult: (Boolean) -> Unit) {
        val id = associationIds.remove(peerId)
        if (id == null || backend.sdkInt() < AssociationController.SELF_MANAGED_MIN_SDK) {
            onResult(false)
            return
        }
        backend.disassociate(id)
        onResult(true)
    }
}

/**
 * API 26–35. No platform presence feed in v1: legacy devices rely on
 * reconnect-on-use (conservative background model, ADR-0003). Association
 * still works where self-managed CDM exists (33–35).
 */
class LegacyPresenceAdapter(backend: CdmBackend) : CdmBackedPresenceAdapter(backend) {

    override fun observePresence(peerId: PeerId, listener: PresenceListener): PresenceSubscription =
        NoopSubscription

    private object NoopSubscription : PresenceSubscription {
        override fun cancel() = Unit
    }
}

/**
 * API 36+ (`ObservingDevicePresenceRequest` / `DevicePresenceEvent` path).
 * Consumes a [PresenceSource]; suppresses consecutive duplicate events per
 * peer so flapping radios do not turn into reconnect storms.
 */
class Api36PresenceAdapter(
    backend: CdmBackend,
    private val source: PresenceSource,
) : CdmBackedPresenceAdapter(backend) {

    override fun observePresence(peerId: PeerId, listener: PresenceListener): PresenceSubscription {
        var lastEvent: PresenceEvent? = null
        source.startObserving(peerId) { event ->
            if (event != lastEvent) {
                lastEvent = event
                listener.onPresenceEvent(event)
            }
        }
        return object : PresenceSubscription {
            private var cancelled = false
            override fun cancel() {
                if (cancelled) return
                cancelled = true
                source.stopObserving(peerId)
            }
        }
    }
}
