package dev.hyphen.android.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeTokenStoreTest {

    private val peerA = ByteArray(32) { 0xAA.toByte() }
    private val peerB = ByteArray(32) { 0xBB.toByte() }
    private var clock = 0L
    private val store = ResumeTokenStore(nowMs = { clock })

    @Test
    fun `token redeems once for the right peer`() {
        val token = store.issue("s_one", peerA)
        assertTrue("matches hello schema shape", Regex("^[A-Za-z0-9_-]{16,128}$").matches(token))
        assertEquals("s_one", store.redeem(token, peerA))
        assertNull("single-use: second redeem must fail", store.redeem(token, peerA))
    }

    @Test
    fun `expired token is refused`() {
        val token = store.issue("s_one", peerA)
        clock = ResumeTokenStore.DEFAULT_TTL_MS + 1
        assertNull(store.redeem(token, peerA))
    }

    @Test
    fun `wrong peer is refused and the token is still consumed`() {
        val token = store.issue("s_one", peerA)
        assertNull(store.redeem(token, peerB))
        assertNull("consumed on the failed attempt", store.redeem(token, peerA))
    }

    @Test
    fun `reissue invalidates the previous token for the session`() {
        val first = store.issue("s_one", peerA)
        val second = store.issue("s_one", peerA)
        assertNotEquals(first, second)
        assertNull(store.redeem(first, peerA))
        assertEquals("s_one", store.redeem(second, peerA))
    }

    @Test
    fun `trust revocation drops every token for the peer`() {
        store.issue("s_one", peerA)
        store.issue("s_two", peerB)
        store.invalidatePeer(peerA)
        assertEquals(1, store.liveCount())
    }
}
