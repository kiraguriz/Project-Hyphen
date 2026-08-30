package dev.hyphen.android.companion

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssociationAttemptCoordinatorTest {
    private val peer = ByteArray(32) { 0x33 }

    @Test
    fun `activity recreation replays one platform-live approval without creating a second attempt`() {
        val coordinator = AssociationAttemptCoordinator<String>()
        var firstActivityNotifications = 0
        var secondActivityNotifications = 0
        val firstListener = { firstActivityNotifications += 1 }
        val secondListener = { secondActivityNotifications += 1 }
        coordinator.attachApprovalListener(firstListener)
        val token = coordinator.tryBegin(peer)!!

        assertTrue(coordinator.offerApproval(token, "intent-sender"))
        assertEquals(1, firstActivityNotifications)
        coordinator.detachApprovalListener(firstListener)
        coordinator.attachApprovalListener(secondListener)

        assertEquals(1, secondActivityNotifications)
        assertNull(coordinator.tryBegin(peer))
        val pending = coordinator.claimPendingApproval()!!
        assertTrue(pending.token === token)
        assertEquals("intent-sender", pending.approval)
        assertNull(coordinator.claimPendingApproval())
    }

    @Test
    fun `destroyed activity token cannot claim or consume a replacement attempt`() {
        val coordinator = AssociationAttemptCoordinator<String>()
        val stale = coordinator.tryBegin(peer)!!
        assertTrue(coordinator.cancel(stale))
        val currentPeer = ByteArray(32) { 0x44 }
        val current = coordinator.tryBegin(currentPeer)!!

        assertFalse(coordinator.offerApproval(stale, "stale"))
        assertTrue(coordinator.complete(stale, 7) is AssociationAttemptCompletion.Stale)
        assertTrue(coordinator.isActive(current))
        val completion = coordinator.complete(current, 8) as AssociationAttemptCompletion.First
        assertArrayEquals(currentPeer, completion.peerFingerprint)
    }

    @Test
    fun `duplicate associated callback for the same id is distinguished from a stale orphan`() {
        val coordinator = AssociationAttemptCoordinator<String>()
        val token = coordinator.tryBegin(peer)!!

        val first = coordinator.complete(token, 7) as AssociationAttemptCompletion.First
        assertArrayEquals(peer, first.peerFingerprint)
        assertTrue(coordinator.complete(token, 7) is AssociationAttemptCompletion.DuplicateSame)
        assertTrue(coordinator.complete(token, 8) is AssociationAttemptCompletion.Stale)
        assertFalse(coordinator.isActive(token))
    }

    @Test
    fun `approval launch failure abandons the unusable request and permits a fresh attempt`() {
        val coordinator = AssociationAttemptCoordinator<String>()
        val token = coordinator.tryBegin(peer)!!
        assertTrue(coordinator.offerApproval(token, "broken-intent-sender"))
        assertTrue(coordinator.claimPendingApproval()!!.token === token)

        assertTrue(coordinator.abandonAfterApprovalLaunchFailure(token))

        assertFalse(coordinator.isActive(token))
        assertTrue(coordinator.tryBegin(peer) != null)
    }
}
