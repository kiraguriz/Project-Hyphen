package dev.hyphen.android.discovery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class RecordingBackendCallbacks : BackendCallbacks {
    override fun onStartFailed(errorCode: Int) = Unit
    override fun onStopFailed(errorCode: Int) = Unit
    override fun onServiceFound(name: String) = Unit
    override fun onServiceLost(name: String) = Unit
    override fun onResolved(name: String, host: String, port: Int) = Unit
    override fun onResolveFailed(name: String, errorCode: Int) = Unit
    override fun onStopped() = Unit
}

class AndroidNsdBackendStateTest {

    @Test
    fun `clear advances generation so old listener cannot repopulate services`() {
        val state = AndroidNsdBackendState<String, String>()
        val callbacks = RecordingBackendCallbacks()
        val generation = state.start(callbacks)
        state.activateListenerIfCurrent(generation, "listener")

        val clearDelivery = state.clearIfActive(generation, clearServices = true)

        assertNotNull(clearDelivery)
        assertTrue(state.shouldDeliverClearedCallback(clearDelivery!!))
        assertNull(state.currentListener())
        assertNull(state.recordServiceFound(generation, "Mac", "old-service"))
        assertNull(state.recordServiceLost(generation, "Mac"))
        assertNull(state.lookupService("Mac").service)
    }

    @Test
    fun `queued clear callback is skipped after a replacement start`() {
        val state = AndroidNsdBackendState<String, String>()
        val generation = state.start(RecordingBackendCallbacks())

        val clearDelivery = state.clearIfActive(generation, clearServices = true)!!
        val replacementGeneration = state.start(RecordingBackendCallbacks())

        assertFalse(state.shouldDeliverClearedCallback(clearDelivery))
        assertNotNull(state.activeCallback(replacementGeneration))
    }

    @Test
    fun `queued service callback is skipped after a replacement start`() {
        val state = AndroidNsdBackendState<String, String>()
        val generation = state.start(RecordingBackendCallbacks())

        val serviceDelivery = state.recordServiceFound(generation, "Mac", "old-service")!!
        state.start(RecordingBackendCallbacks())

        assertFalse(state.shouldDeliverActiveCallback(serviceDelivery))
        assertNull(state.lookupService("Mac").service)
    }

    @Test
    fun `old resolve generation is rejected after start failure cleanup`() {
        val state = AndroidNsdBackendState<String, String>()
        val generation = state.start(RecordingBackendCallbacks())
        state.recordServiceFound(generation, "Mac", "old-service")
        val oldLookup = state.lookupService("Mac")

        state.clearIfActive(generation, clearServices = true)

        assertNull(state.activeCallback(oldLookup.generation))
        assertNull(state.lookupService("Mac").service)
    }

    @Test
    fun `old resolve generation is rejected after replacement start`() {
        val state = AndroidNsdBackendState<String, String>()
        val generation = state.start(RecordingBackendCallbacks())
        state.recordServiceFound(generation, "Mac", "old-service")
        val oldLookup = state.lookupService("Mac")

        val replacementGeneration = state.start(RecordingBackendCallbacks())

        assertNull(state.activeCallback(oldLookup.generation))
        assertNotNull(state.activeCallback(replacementGeneration))
        assertNull(state.lookupService("Mac").service)
    }
}
