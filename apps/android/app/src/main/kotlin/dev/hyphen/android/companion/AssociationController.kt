package dev.hyphen.android.companion

import android.app.Activity

fun interface AssociationApprovalRequest {
    fun launch(activity: Activity)
}

/**
 * CDM association PoC (HYP-M1-007, plan §7.4). Hyphen uses *self-managed*
 * associations (API 33+): the app manages the LAN-TLS connection itself and
 * CDM only records system relationship metadata.
 * API 26–32 gets no CDM association in v1 — QR-only pairing with
 * conservative background behavior (ADR-0003); a BT-filter legacy spike
 * stays an open question for the M1 device session (HYP-M1-009 log).
 *
 * CDM never creates network connections and is never a trust source;
 * the Hyphen trust store decides peers (plan §7.4).
 */
sealed class AssociationEvent {
    /** System approval dialog is ready; launch it from the current Activity. */
    data class PendingUserApproval(val approval: AssociationApprovalRequest) : AssociationEvent()
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
    fun onPendingApproval(approval: AssociationApprovalRequest)
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
    private fun supported(): Boolean = backend.sdkInt() >= SELF_MANAGED_MIN_SDK

    fun associate(
        displayName: String,
        requestEvents: (AssociationEvent) -> Unit = onEvent,
    ) {
        if (!supported()) {
            requestEvents(AssociationEvent.UnsupportedOnThisSdk)
            return
        }
        val callbacks = object : CdmCallbacks {
            override fun onPendingApproval(approval: AssociationApprovalRequest) {
                requestEvents(AssociationEvent.PendingUserApproval(approval))
            }

            override fun onAssociated(associationId: Int, displayName: String?) {
                requestEvents(AssociationEvent.Associated(associationId, displayName))
            }

            override fun onFailure(message: String) {
                requestEvents(AssociationEvent.Failed(message))
            }
        }
        try {
            backend.requestSelfManagedAssociation(displayName, callbacks)
        } catch (error: RuntimeException) {
            requestEvents(
                AssociationEvent.Failed(
                    error.message ?: "CDM association request failed",
                ),
            )
        }
    }

    fun prepareDisassociation(associationId: Int): AssociationRemovalRequest? {
        if (!supported()) {
            onEvent(AssociationEvent.UnsupportedOnThisSdk)
            return null
        }
        val associationIds = listAssociationIdsOrReport() ?: return null
        if (associationId !in associationIds) {
            onEvent(AssociationEvent.Failed("association $associationId is no longer present"))
            return null
        }
        return AssociationRemovalRequest(associationId, ::removeAssociation)
    }

    fun associations(): List<Int> =
        if (supported()) listAssociationIdsOrReport() ?: emptyList() else emptyList()

    /** Null means the platform list failed; callers must not erase bindings. */
    fun associationIdsForReconciliation(): Set<Int>? =
        if (!supported()) {
            emptySet()
        } else {
            try {
                backend.listAssociationIds().toSet()
            } catch (_: RuntimeException) {
                null
            }
        }

    /** Cleans up a just-created association that lost its trusted peer. */
    fun removeUnboundAssociation(associationId: Int) {
        if (!supported()) return
        removeAssociation(associationId)
    }

    private fun listAssociationIdsOrReport(): List<Int>? =
        try {
            backend.listAssociationIds()
        } catch (error: RuntimeException) {
            onEvent(
                AssociationEvent.Failed(
                    error.message ?: "CDM association list failed",
                ),
            )
            null
        }

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
