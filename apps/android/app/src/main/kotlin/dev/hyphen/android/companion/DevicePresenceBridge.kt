package dev.hyphen.android.companion

/**
 * In-process bridge between the system-instantiated
 * [HyphenCompanionDeviceService] and adapter-level [PresenceSource]
 * consumers (HYP-M1-009). The service cannot be constructed by us, so a
 * process-wide registry is the seam: association ids map to Hyphen peer
 * ids, presence dispatches route to whoever observes that peer.
 */
object DevicePresenceBridge {

    private val peerByAssociation = mutableMapOf<Int, PeerId>()
    private val observers = mutableMapOf<PeerId, (PresenceEvent) -> Unit>()

    /** Call after a successful association (id ↔ peer binding). */
    @Synchronized
    fun bind(associationId: Int, peerId: PeerId) {
        peerByAssociation[associationId] = peerId
    }

    @Synchronized
    fun unbind(associationId: Int) {
        peerByAssociation.remove(associationId)
    }

    @Synchronized
    fun register(peerId: PeerId, onEvent: (PresenceEvent) -> Unit) {
        observers[peerId] = onEvent
    }

    @Synchronized
    fun unregister(peerId: PeerId) {
        observers.remove(peerId)
    }

    /** Entry point for the CompanionDeviceService. Unknown ids are ignored. */
    fun dispatch(associationId: Int, appeared: Boolean) {
        val (peer, observer) = synchronized(this) {
            val peer = peerByAssociation[associationId] ?: return
            val observer = observers[peer] ?: return
            peer to observer
        }
        observer(if (appeared) PresenceEvent.Appeared(peer) else PresenceEvent.Disappeared(peer))
    }

    /** Test hook. */
    @Synchronized
    fun reset() {
        peerByAssociation.clear()
        observers.clear()
    }
}

/** [PresenceSource] backed by the bridge; feed for [Api36PresenceAdapter]. */
class BridgePresenceSource : PresenceSource {
    override fun startObserving(peerId: PeerId, onEvent: (PresenceEvent) -> Unit) {
        DevicePresenceBridge.register(peerId, onEvent)
    }

    override fun stopObserving(peerId: PeerId) {
        DevicePresenceBridge.unregister(peerId)
    }
}
