package dev.hyphen.android.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper

/** Real [Scheduler] on the main looper. */
class HandlerScheduler : Scheduler {
    private val handler = Handler(Looper.getMainLooper())

    override fun schedule(delayMillis: Long, action: () -> Unit): Any {
        val r = Runnable(action)
        handler.postDelayed(r, delayMillis)
        return r
    }

    override fun cancel(token: Any) {
        handler.removeCallbacks(token as Runnable)
    }
}

/**
 * NsdManager-backed [NsdBackend] (HYP-M1-004 PoC). Callbacks are hopped to
 * the main thread because NsdManager invokes listeners on an internal one.
 */
class AndroidNsdBackend(context: Context) : NsdBackend {

    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())

    private var callbacks: BackendCallbacks? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val foundServices = mutableMapOf<String, NsdServiceInfo>()

    override fun startDiscovery(serviceType: String, callbacks: BackendCallbacks) {
        this.callbacks = callbacks
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(type: String, errorCode: Int) {
                discoveryListener = null
                post { it.onStartFailed(errorCode) }
            }

            override fun onStopDiscoveryFailed(type: String, errorCode: Int) {
                discoveryListener = null
                post { it.onStopped() }
            }

            override fun onDiscoveryStarted(type: String) = Unit

            override fun onDiscoveryStopped(type: String) {
                discoveryListener = null
                post { it.onStopped() }
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                foundServices[info.serviceName] = info
                post { it.onServiceFound(info.serviceName) }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                foundServices.remove(info.serviceName)
                post { it.onServiceLost(info.serviceName) }
            }
        }
        discoveryListener = listener
        nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    override fun stopDiscovery() {
        discoveryListener?.let { nsd.stopServiceDiscovery(it) }
    }

    override fun resolve(serviceName: String) {
        val info = foundServices[serviceName]
        if (info == null) {
            post { it.onResolveFailed(serviceName, RESOLVE_ERROR_UNKNOWN_SERVICE) }
            return
        }
        // resolveService is deprecated from API 34 in favor of
        // registerServiceInfoCallback; acceptable for this PoC and still
        // functional. Revisit when core-discovery becomes a module (M2).
        @Suppress("DEPRECATION")
        nsd.resolveService(
            info,
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) {
                    post { it.onResolveFailed(failed.serviceName, errorCode) }
                }

                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    val host = resolved.host?.hostAddress
                    if (host == null) {
                        post { it.onResolveFailed(resolved.serviceName, RESOLVE_ERROR_NO_HOST) }
                    } else {
                        post { it.onResolved(resolved.serviceName, host, resolved.port) }
                    }
                }
            },
        )
    }

    private fun post(block: (BackendCallbacks) -> Unit) {
        val cb = callbacks ?: return
        main.post { block(cb) }
    }

    private companion object {
        const val RESOLVE_ERROR_UNKNOWN_SERVICE = -1
        const val RESOLVE_ERROR_NO_HOST = -2
    }
}
