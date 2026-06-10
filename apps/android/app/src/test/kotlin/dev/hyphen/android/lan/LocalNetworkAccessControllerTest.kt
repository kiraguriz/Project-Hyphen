package dev.hyphen.android.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeProbe(
    var sdk: Int,
    var granted: Boolean = false,
    var rationale: Boolean = false,
) : LanPermissionProbe {
    override fun sdkInt() = sdk
    override fun isPermissionGranted() = granted
    override fun shouldShowRationale() = rationale
}

private class FakeTracker(var requested: Boolean = false) : PermissionRequestTracker {
    override fun hasRequested() = requested
    override fun markRequested() {
        requested = true
    }
}

class LocalNetworkAccessControllerTest {

    private fun controller(probe: FakeProbe, tracker: FakeTracker = FakeTracker()) =
        LocalNetworkAccessController(probe, tracker)

    @Test
    fun `pre SDK37 is granted and not platform gated`() {
        val status = controller(FakeProbe(sdk = 36)).status()
        assertEquals(LanPermissionState.GRANTED, status.state)
        assertFalse(status.platformGated)
        assertFalse(status.shouldShowRationale)
    }

    @Test
    fun `sdk37 granted exposes full capabilities`() {
        val c = controller(FakeProbe(sdk = 37, granted = true))
        assertEquals(LanPermissionState.GRANTED, c.status().state)
        assertTrue(c.status().platformGated)
        assertEquals(
            setOf(
                LanCapability.QR_MANUAL_PAIRING,
                LanCapability.MDNS_DISCOVERY,
                LanCapability.LAN_CONNECT,
            ),
            c.availableCapabilities(),
        )
    }

    @Test
    fun `sdk37 never requested is unknown`() {
        val status = controller(FakeProbe(sdk = 37)).status()
        assertEquals(LanPermissionState.UNKNOWN, status.state)
        assertFalse(status.shouldShowRationale)
        assertTrue(status.platformGated)
    }

    @Test
    fun `sdk37 denied once is denied with rationale`() {
        val status = controller(FakeProbe(sdk = 37, rationale = true)).status()
        assertEquals(LanPermissionState.DENIED, status.state)
        assertTrue(status.shouldShowRationale)
    }

    @Test
    fun `sdk37 requested and no rationale is permanently denied`() {
        val status =
            controller(FakeProbe(sdk = 37), FakeTracker(requested = true)).status()
        assertEquals(LanPermissionState.DENIED, status.state)
        assertFalse(status.shouldShowRationale)
    }

    @Test
    fun `qr manual pairing survives every state`() {
        val deniedForever = controller(FakeProbe(sdk = 37), FakeTracker(requested = true))
        val unknown = controller(FakeProbe(sdk = 37))
        val legacy = controller(FakeProbe(sdk = 26))
        listOf(deniedForever, unknown, legacy).forEach { c ->
            assertTrue(
                "QR/manual must be available in state ${c.status().state}",
                LanCapability.QR_MANUAL_PAIRING in c.availableCapabilities(),
            )
        }
    }

    @Test
    fun `denied without grant never exposes lan capabilities`() {
        val c = controller(FakeProbe(sdk = 37), FakeTracker(requested = true))
        assertFalse(LanCapability.MDNS_DISCOVERY in c.availableCapabilities())
        assertFalse(LanCapability.LAN_CONNECT in c.availableCapabilities())
    }

    @Test
    fun `permission result marks requested so later silence means permanent denial`() {
        val probe = FakeProbe(sdk = 37)
        val tracker = FakeTracker()
        val c = controller(probe, tracker)
        assertEquals(LanPermissionState.UNKNOWN, c.status().state)

        c.onPermissionResult() // user answered the system dialog (denied)
        assertTrue(tracker.requested)
        assertEquals(LanPermissionState.DENIED, c.status().state)

        probe.granted = true // user later granted via settings
        assertEquals(LanPermissionState.GRANTED, c.status().state)
    }
}
