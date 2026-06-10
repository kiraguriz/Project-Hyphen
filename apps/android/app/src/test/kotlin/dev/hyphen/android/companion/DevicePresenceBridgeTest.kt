package dev.hyphen.android.companion

import android.companion.DevicePresenceEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DevicePresenceBridgeTest {

    private val mac = PeerId("mac-fp-1")
    private val other = PeerId("mac-fp-2")
    private val seen = mutableListOf<PresenceEvent>()

    @Before
    fun setUp() {
        DevicePresenceBridge.reset()
    }

    @After
    fun tearDown() {
        DevicePresenceBridge.reset()
    }

    @Test
    fun `dispatch routes bound association to its observer`() {
        DevicePresenceBridge.bind(7, mac)
        DevicePresenceBridge.register(mac) { seen += it }

        DevicePresenceBridge.dispatch(7, appeared = true)
        DevicePresenceBridge.dispatch(7, appeared = false)

        assertEquals(
            listOf<PresenceEvent>(PresenceEvent.Appeared(mac), PresenceEvent.Disappeared(mac)),
            seen,
        )
    }

    @Test
    fun `unknown association and unobserved peer are ignored`() {
        DevicePresenceBridge.register(mac) { seen += it }
        DevicePresenceBridge.dispatch(99, appeared = true) // no binding

        DevicePresenceBridge.bind(7, other) // bound but unobserved peer
        DevicePresenceBridge.dispatch(7, appeared = true)

        assertTrue(seen.isEmpty())
    }

    @Test
    fun `unbind and unregister stop routing`() {
        DevicePresenceBridge.bind(7, mac)
        DevicePresenceBridge.register(mac) { seen += it }

        DevicePresenceBridge.unbind(7)
        DevicePresenceBridge.dispatch(7, appeared = true)
        assertTrue(seen.isEmpty())

        DevicePresenceBridge.bind(7, mac)
        DevicePresenceBridge.unregister(mac)
        DevicePresenceBridge.dispatch(7, appeared = true)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `bridge presence source feeds the api36 adapter end to end`() {
        val adapter = Api36PresenceAdapter(
            object : CdmBackend {
                override fun sdkInt() = 36
                override fun requestSelfManagedAssociation(
                    displayName: String,
                    callbacks: CdmCallbacks,
                ) = Unit

                override fun disassociate(associationId: Int) = Unit
                override fun listAssociationIds(): List<Int> = emptyList()
            },
            BridgePresenceSource(),
        )
        DevicePresenceBridge.bind(7, mac)
        val sub = adapter.observePresence(mac) { seen += it }

        DevicePresenceBridge.dispatch(7, appeared = true)
        DevicePresenceBridge.dispatch(7, appeared = true) // adapter dedupes
        DevicePresenceBridge.dispatch(7, appeared = false)

        assertEquals(
            listOf<PresenceEvent>(PresenceEvent.Appeared(mac), PresenceEvent.Disappeared(mac)),
            seen,
        )
        sub.cancel()
        DevicePresenceBridge.dispatch(7, appeared = true)
        assertEquals(2, seen.size)
    }

    @Test
    fun `presence event types map to appeared, disappeared, or ignored`() {
        assertEquals(true, presenceEventToAppeared(DevicePresenceEvent.EVENT_SELF_MANAGED_APPEARED))
        assertEquals(true, presenceEventToAppeared(DevicePresenceEvent.EVENT_BLE_APPEARED))
        assertEquals(true, presenceEventToAppeared(DevicePresenceEvent.EVENT_BT_CONNECTED))
        assertEquals(false, presenceEventToAppeared(DevicePresenceEvent.EVENT_SELF_MANAGED_DISAPPEARED))
        assertEquals(false, presenceEventToAppeared(DevicePresenceEvent.EVENT_BLE_DISAPPEARED))
        assertEquals(false, presenceEventToAppeared(DevicePresenceEvent.EVENT_BT_DISCONNECTED))
        assertNull(presenceEventToAppeared(-12345))
    }
}
