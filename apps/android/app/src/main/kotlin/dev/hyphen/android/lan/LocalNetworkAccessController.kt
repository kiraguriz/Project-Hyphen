package dev.hyphen.android.lan

/** Permission state for local-network access (ADR-0003 §2). */
enum class LanPermissionState { GRANTED, DENIED, UNKNOWN }

/** Everything the UI needs to render the LAN permission situation. */
data class LanAccessStatus(
    val state: LanPermissionState,
    /** True when the app should explain itself before re-requesting. */
    val shouldShowRationale: Boolean,
    /** False on SDKs where the platform does not gate LAN access at all. */
    val platformGated: Boolean,
)

/** Capabilities whose availability depends on the permission state. */
enum class LanCapability { MDNS_DISCOVERY, LAN_CONNECT, QR_MANUAL_PAIRING }

/**
 * Platform facts the controller consumes. The real implementation wraps
 * Context/Activity permission APIs; tests use fakes. Keeping android.*
 * out of this package keeps the logic JVM-testable.
 */
interface LanPermissionProbe {
    fun sdkInt(): Int
    fun isPermissionGranted(): Boolean
    fun shouldShowRationale(): Boolean
}

/**
 * Persists whether we have ever requested the permission. Android cannot
 * distinguish "never asked" from "permanently denied" (both report
 * granted=false, rationale=false), so the controller must remember.
 */
interface PermissionRequestTracker {
    fun hasRequested(): Boolean
    fun markRequested()
}

/**
 * Single gate for every LAN operation (ADR-0003 §2): discovery, `.local`
 * resolution, LAN listeners/connections, multicast. Denied is a supported
 * mode — QR/manual pairing must remain available in every state.
 */
class LocalNetworkAccessController(
    private val probe: LanPermissionProbe,
    private val tracker: PermissionRequestTracker,
) {

    fun status(): LanAccessStatus {
        if (probe.sdkInt() < LOCAL_NET_PERMISSION_SDK) {
            // Legacy platforms do not gate LAN access; report granted but
            // not platform-gated so callers can tell the difference.
            return LanAccessStatus(
                state = LanPermissionState.GRANTED,
                shouldShowRationale = false,
                platformGated = false,
            )
        }
        if (probe.isPermissionGranted()) {
            return LanAccessStatus(
                state = LanPermissionState.GRANTED,
                shouldShowRationale = false,
                platformGated = true,
            )
        }
        if (probe.shouldShowRationale()) {
            // Denied at least once, still re-askable with an explanation.
            return LanAccessStatus(
                state = LanPermissionState.DENIED,
                shouldShowRationale = true,
                platformGated = true,
            )
        }
        return if (tracker.hasRequested()) {
            // Asked before, no rationale allowed: permanently denied;
            // recovery is the system settings deep link.
            LanAccessStatus(
                state = LanPermissionState.DENIED,
                shouldShowRationale = false,
                platformGated = true,
            )
        } else {
            LanAccessStatus(
                state = LanPermissionState.UNKNOWN,
                shouldShowRationale = false,
                platformGated = true,
            )
        }
    }

    /**
     * What the product may attempt right now. QR/manual pairing is always
     * present — the deny-tolerant invariant from ADR-0003.
     */
    fun availableCapabilities(): Set<LanCapability> {
        val base = mutableSetOf(LanCapability.QR_MANUAL_PAIRING)
        if (status().state == LanPermissionState.GRANTED) {
            base += LanCapability.MDNS_DISCOVERY
            base += LanCapability.LAN_CONNECT
        }
        return base
    }

    /** Call from the permission-result callback; state is re-derived from the probe. */
    fun onPermissionResult() {
        tracker.markRequested()
    }

    companion object {
        /** Android 17 / SDK 37 introduces ACCESS_LOCAL_NETWORK (ADR-0003 §1). */
        const val LOCAL_NET_PERMISSION_SDK = 37
    }
}
