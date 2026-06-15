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

    private val stateLock = Any()
    private val state = AndroidNsdBackendState<NsdServiceInfo, NsdManager.DiscoveryListener>()

    override fun startDiscovery(serviceType: String, callbacks: BackendCallbacks) {
        val generation = synchronized(stateLock) {
            state.start(callbacks)
        }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(type: String, errorCode: Int) {
                postAndClearIfActive(generation, clearServices = true) {
                    it.onStartFailed(errorCode)
                }
            }

            override fun onStopDiscoveryFailed(type: String, errorCode: Int) {
                postIfActive(generation) { it.onStopFailed(errorCode) }
            }

            override fun onDiscoveryStarted(type: String) = Unit

            override fun onDiscoveryStopped(type: String) {
                postAndClearIfActive(generation, clearServices = true) {
                    it.onStopped()
                }
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                val delivery = synchronized(stateLock) {
                    state.recordServiceFound(generation, info.serviceName, info)
                } ?: return
                postIfStillActive(delivery) { it.onServiceFound(info.serviceName) }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                val delivery = synchronized(stateLock) {
                    state.recordServiceLost(generation, info.serviceName)
                } ?: return
                postIfStillActive(delivery) { it.onServiceLost(info.serviceName) }
            }
        }
        synchronized(stateLock) {
            state.activateListenerIfCurrent(generation, listener)
        }
        nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    override fun stopDiscovery() {
        val listener = synchronized(stateLock) { state.currentListener() } ?: return
        nsd.stopServiceDiscovery(listener)
    }

    override fun resolve(serviceName: String) {
        val lookup = synchronized(stateLock) { state.lookupService(serviceName) }
        if (lookup.service == null) {
            postIfActive(lookup.generation) {
                it.onResolveFailed(serviceName, RESOLVE_ERROR_UNKNOWN_SERVICE)
            }
            return
        }
        // resolveService is deprecated from API 34 in favor of
        // registerServiceInfoCallback; acceptable for this PoC and still
        // functional. Revisit when core-discovery becomes a module (M2).
        @Suppress("DEPRECATION")
        nsd.resolveService(
            lookup.service,
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) {
                    postIfActive(lookup.generation) {
                        it.onResolveFailed(failed.serviceName, errorCode)
                    }
                }

                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    val host = resolved.host?.hostAddress
                    if (host == null) {
                        postIfActive(lookup.generation) {
                            it.onResolveFailed(resolved.serviceName, RESOLVE_ERROR_NO_HOST)
                        }
                    } else {
                        postIfActive(lookup.generation) {
                            it.onResolved(resolved.serviceName, host, resolved.port)
                        }
                    }
                }
            },
        )
    }

    private fun postIfActive(generation: Long, block: (BackendCallbacks) -> Unit) {
        val delivery = synchronized(stateLock) {
            state.activeCallback(generation)
        } ?: return
        postIfStillActive(delivery, block)
    }

    private fun postAndClearIfActive(
        generation: Long,
        clearServices: Boolean,
        block: (BackendCallbacks) -> Unit,
    ) {
        val delivery = synchronized(stateLock) {
            state.clearIfActive(generation, clearServices)
        } ?: return
        main.post {
            if (synchronized(stateLock) { state.shouldDeliverClearedCallback(delivery) }) {
                block(delivery.callbacks)
            }
        }
    }

    private fun postIfStillActive(
        delivery: AndroidNsdBackendState.CallbackDelivery,
        block: (BackendCallbacks) -> Unit,
    ) {
        main.post {
            if (synchronized(stateLock) { state.shouldDeliverActiveCallback(delivery) }) {
                block(delivery.callbacks)
            }
        }
    }

    private companion object {
        const val RESOLVE_ERROR_UNKNOWN_SERVICE = -1
        const val RESOLVE_ERROR_NO_HOST = -2
    }
}

internal class AndroidNsdBackendState<ServiceInfo : Any, Listener : Any> {
    data class CallbackDelivery(
        val callbacks: BackendCallbacks,
        val generation: Long,
    )

    data class ServiceLookup<ServiceInfo>(
        val generation: Long,
        val service: ServiceInfo?,
    )

    private var callbacks: BackendCallbacks? = null
    private var listener: Listener? = null
    private var generation = 0L
    private val services = mutableMapOf<String, ServiceInfo>()

    fun start(callbacks: BackendCallbacks): Long {
        generation += 1
        this.callbacks = callbacks
        listener = null
        services.clear()
        return generation
    }

    fun activateListenerIfCurrent(expectedGeneration: Long, listener: Listener) {
        if (generation == expectedGeneration) {
            this.listener = listener
        }
    }

    fun currentListener(): Listener? = listener

    fun activeCallback(expectedGeneration: Long): CallbackDelivery? {
        val activeCallbacks = callbacks ?: return null
        return if (generation == expectedGeneration) {
            CallbackDelivery(activeCallbacks, expectedGeneration)
        } else {
            null
        }
    }

    fun recordServiceFound(
        expectedGeneration: Long,
        serviceName: String,
        service: ServiceInfo,
    ): CallbackDelivery? {
        val delivery = activeCallback(expectedGeneration) ?: return null
        services[serviceName] = service
        return delivery
    }

    fun recordServiceLost(expectedGeneration: Long, serviceName: String): CallbackDelivery? {
        val delivery = activeCallback(expectedGeneration) ?: return null
        services.remove(serviceName)
        return delivery
    }

    fun lookupService(serviceName: String): ServiceLookup<ServiceInfo> =
        ServiceLookup(generation, services[serviceName])

    fun clearIfActive(expectedGeneration: Long, clearServices: Boolean): CallbackDelivery? {
        val activeCallbacks = callbacks ?: return null
        if (generation != expectedGeneration) return null
        generation += 1
        callbacks = null
        listener = null
        if (clearServices) {
            services.clear()
        }
        return CallbackDelivery(activeCallbacks, generation)
    }

    fun shouldDeliverActiveCallback(delivery: CallbackDelivery): Boolean =
        generation == delivery.generation && callbacks === delivery.callbacks

    fun shouldDeliverClearedCallback(delivery: CallbackDelivery): Boolean =
        generation == delivery.generation && callbacks == null
}
