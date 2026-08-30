package dev.hyphen.android.companion

/**
 * Compile-spike bridge between [HyphenCompanionDeviceService] and a
 * [PresenceSource]. The service is not registered in the product manifest;
 * this seam exists only to keep the public BLE/BT API shape testable and must
 * not be interpreted as self-managed LAN presence or lifecycle evidence.
 */
object DevicePresenceBridge {

    private val peerByAssociation = mutableMapOf<Int, PeerId>()
    private val observers = mutableMapOf<PeerId, (PresenceEvent) -> Unit>()

    /** Compile-spike binding helper; product trust bindings use the durable index. */
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
