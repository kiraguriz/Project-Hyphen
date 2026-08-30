package dev.hyphen.android.companion

/**
 * CDM association PoC (HYP-M1-007, plan §7.4). Hyphen uses *self-managed*
 * associations (API 33+): the app manages the LAN-TLS connection itself and
 * CDM only records the companion relationship for background resilience.
 * API 26–32 gets no CDM association in v1 — QR-only pairing with
 * conservative background behavior (ADR-0003); a BT-filter legacy spike
 * stays an open question for the M1 device session (HYP-M1-009 log).
 *
 * CDM never creates network connections and is never a trust source;
 * the Hyphen trust store decides peers (plan §7.4).
 */
sealed class AssociationEvent {
    /** System approval dialog is ready; invoke [launch] from the Activity. */
    data class PendingUserApproval(val launch: () -> Unit) : AssociationEvent()
    data class Associated(val associationId: Int, val displayName: String?) : AssociationEvent()
    data class Removed(val associationId: Int) : AssociationEvent()
    data class Failed(val message: String) : AssociationEvent()
    object UnsupportedOnThisSdk : AssociationEvent()
}

/** Platform CDM operations; the real impl wraps CompanionDeviceManager. */
interface CdmBackend {
    fun sdkInt(): Int
    fun requestSelfManagedAssociation(displayName: String, callbacks: CdmCallbacks)
    fun disassociate(associationId: Int)
    fun listAssociationIds(): List<Int>
}

interface CdmCallbacks {
    fun onPendingApproval(launch: () -> Unit)
    fun onAssociated(associationId: Int, displayName: String?)
    fun onFailure(message: String)
}

/**
 * One-shot user decision for a destructive CDM operation.
 *
 * Merely listing or selecting an association never removes it. The Activity
 * must present its own confirmation UI and then call exactly one of [confirm]
 * or [cancel]. Repeated/stale callbacks are harmless.
 */
class AssociationRemovalRequest internal constructor(
    val associationId: Int,
    private val onConfirm: (Int) -> Unit,
) {
    private var consumed = false

    @Synchronized
    fun confirm(): Boolean {
        if (consumed) return false
        consumed = true
        onConfirm(associationId)
        return true
    }

    @Synchronized
    fun cancel(): Boolean {
        if (consumed) return false
        consumed = true
        return true
    }
}

class AssociationController(
    private val backend: CdmBackend,
    private val onEvent: (AssociationEvent) -> Unit,
) {

    private val callbacks = object : CdmCallbacks {
        override fun onPendingApproval(launch: () -> Unit) {
            onEvent(AssociationEvent.PendingUserApproval(launch))
        }

        override fun onAssociated(associationId: Int, displayName: String?) {
            onEvent(AssociationEvent.Associated(associationId, displayName))
        }

        override fun onFailure(message: String) {
            onEvent(AssociationEvent.Failed(message))
        }
    }

    private fun supported(): Boolean = backend.sdkInt() >= SELF_MANAGED_MIN_SDK

    fun associate(displayName: String) {
        if (!supported()) {
            onEvent(AssociationEvent.UnsupportedOnThisSdk)
            return
        }
        backend.requestSelfManagedAssociation(displayName, callbacks)
    }

    fun prepareDisassociation(associationId: Int): AssociationRemovalRequest? {
        if (!supported()) {
            onEvent(AssociationEvent.UnsupportedOnThisSdk)
            return null
        }
        if (associationId !in backend.listAssociationIds()) {
            onEvent(AssociationEvent.Failed("association $associationId is no longer present"))
            return null
        }
        return AssociationRemovalRequest(associationId, ::removeAssociation)
    }

    fun associations(): List<Int> = if (supported()) backend.listAssociationIds() else emptyList()

    private fun removeAssociation(associationId: Int) {
        try {
            if (associationId !in backend.listAssociationIds()) {
                onEvent(AssociationEvent.Failed("association $associationId is no longer present"))
                return
            }
            backend.disassociate(associationId)
            onEvent(AssociationEvent.Removed(associationId))
        } catch (error: RuntimeException) {
            onEvent(
                AssociationEvent.Failed(
                    "association $associationId removal failed: ${error.message ?: "unknown error"}",
                ),
            )
        }
    }

    companion object {
        /** setSelfManaged(true) exists from API 33. */
        const val SELF_MANAGED_MIN_SDK = 33
    }
}
