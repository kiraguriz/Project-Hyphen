package dev.hyphen.android.companion

import android.annotation.TargetApi
import android.app.Activity
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.os.Build

/**
 * CompanionDeviceManager-backed [CdmBackend] (HYP-M1-007). All entry points
 * are SDK-gated by [AssociationController]; the runtime checks here are
 * defense in depth for direct callers.
 */
class CdmAssociationBackend(private val activity: Activity) : CdmBackend {

    private val cdm: CompanionDeviceManager =
        activity.getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager

    override fun sdkInt(): Int = Build.VERSION.SDK_INT

    override fun requestSelfManagedAssociation(displayName: String, callbacks: CdmCallbacks) {
        if (Build.VERSION.SDK_INT < AssociationController.SELF_MANAGED_MIN_SDK) {
            callbacks.onFailure("self-managed CDM requires API 33+")
            return
        }
        requestApi33(displayName, callbacks)
    }

    @TargetApi(33)
    private fun requestApi33(displayName: String, callbacks: CdmCallbacks) {
        val request = AssociationRequest.Builder()
            .setSelfManaged(true)
            .setDisplayName(displayName)
            .build()

        cdm.associate(
            request,
            activity.mainExecutor,
            object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(intentSender: IntentSender) {
                    callbacks.onPendingApproval {
                        activity.startIntentSenderForResult(
                            intentSender, ASSOCIATION_REQUEST_CODE, null, 0, 0, 0,
                        )
                    }
                }

                override fun onAssociationCreated(associationInfo: AssociationInfo) {
                    callbacks.onAssociated(
                        associationInfo.id,
                        associationInfo.displayName?.toString(),
                    )
                }

                override fun onFailure(error: CharSequence?) {
                    callbacks.onFailure(error?.toString() ?: "unknown CDM failure")
                }
            },
        )
    }

    override fun disassociate(associationId: Int) {
        if (Build.VERSION.SDK_INT >= AssociationController.SELF_MANAGED_MIN_SDK) {
            cdm.disassociate(associationId)
        }
    }

    override fun listAssociationIds(): List<Int> =
        if (Build.VERSION.SDK_INT >= AssociationController.SELF_MANAGED_MIN_SDK) {
            cdm.myAssociations.map(AssociationInfo::getId)
        } else {
            emptyList()
        }

    companion object {
        const val ASSOCIATION_REQUEST_CODE = 4101
    }
}
