package dev.hyphen.android.discovery

import android.content.Context
import android.net.wifi.WifiManager

/** Underlying platform lock; the real impl wraps WifiManager.MulticastLock. */
interface MulticastLockHandle {
    fun acquire()
    fun release()
}

enum class LockTransition { ACQUIRED, RELEASED }

/**
 * Holds the multicast lock only while a discovery window is open
 * (HYP-M1-005, plan §7.6). Idempotent in both directions: double-acquire
 * holds once; double-release is a no-op — the platform lock throws a
 * RuntimeException on over-release, which must never reach callers.
 * Transitions are reported for the local diagnostics log.
 */
class ScopedMulticastLock(
    private val handle: MulticastLockHandle,
    private val onTransition: (LockTransition) -> Unit = {},
) : DiscoveryLock {

    var isHeld: Boolean = false
        private set

    override fun acquire() {
        if (isHeld) return
        handle.acquire()
        isHeld = true
        onTransition(LockTransition.ACQUIRED)
    }

    override fun release() {
        if (!isHeld) return
        isHeld = false
        handle.release()
        onTransition(LockTransition.RELEASED)
    }
}

/** WifiManager-backed handle. Requires CHANGE_WIFI_MULTICAST_STATE. */
class AndroidMulticastLockHandle(context: Context) : MulticastLockHandle {

    private val lock =
        (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createMulticastLock(TAG)
            // One release frees the lock regardless of acquire count;
            // ScopedMulticastLock already guarantees balanced calls.
            .apply { setReferenceCounted(false) }

    override fun acquire() = lock.acquire()

    override fun release() = lock.release()

    private companion object {
        const val TAG = "hyphen-discovery"
    }
}
