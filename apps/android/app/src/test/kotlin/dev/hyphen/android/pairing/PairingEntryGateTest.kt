package dev.hyphen.android.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread

class PairingEntryGateTest {
    private val qr =
        "hyphen://pair?v=0&ep=192.168.1.20:48273&" +
            "fp=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&" +
            "n=AAAAAAAAAAAAAAAAAAAAAA"

    @Test
    fun `only browsable view intents containing valid pairing payloads are accepted`() {
        val accepted = PairingIntentRouter.route("android.intent.action.VIEW", qr)
        val wrongAction = PairingIntentRouter.route("android.intent.action.SEND", qr)
        val wrongScheme = PairingIntentRouter.route("android.intent.action.VIEW", "https://example.com")

        assertTrue(accepted is PairingIntentRoute.Accepted)
        assertEquals(PairingIntentRejectReason.WRONG_ACTION, (wrongAction as PairingIntentRoute.Rejected).reason)
        assertEquals(PairingIntentRejectReason.INVALID_PAYLOAD, (wrongScheme as PairingIntentRoute.Rejected).reason)
    }

    @Test
    fun `two activity entry owners share one token-scoped provisional attempt`() {
        val coordinator = PairingAttemptCoordinator()
        val firstEntryOwner = coordinator
        val secondEntryOwner = coordinator
        val firstSocket = SSLSocketFactory.getDefault().createSocket() as SSLSocket
        val secondSocket = SSLSocketFactory.getDefault().createSocket() as SSLSocket

        val firstToken = firstEntryOwner.tryBegin()!!
        assertNull(secondEntryOwner.tryBegin())
        assertTrue(firstEntryOwner.claimSocket(firstToken, firstSocket))
        assertFalse(secondEntryOwner.claimSocket(firstToken, secondSocket))
        assertTrue(firstEntryOwner.claimCommit(firstToken))
        assertFalse(secondEntryOwner.claimCommit(firstToken))

        assertTrue(firstEntryOwner.cancel(firstToken))
        assertTrue(firstSocket.isClosed)
        assertFalse(secondSocket.isClosed)
        secondSocket.close()
    }

    @Test
    fun `stale attempt token cannot finish or close a newer attempt`() {
        val coordinator = PairingAttemptCoordinator()
        val staleToken = coordinator.tryBegin()!!
        assertTrue(coordinator.cancel(staleToken))
        val currentToken = coordinator.tryBegin()!!
        val currentSocket = SSLSocketFactory.getDefault().createSocket() as SSLSocket
        assertTrue(coordinator.claimSocket(currentToken, currentSocket))

        assertFalse(coordinator.cancel(staleToken))
        assertFalse(coordinator.complete(staleToken))
        assertFalse(coordinator.claimCommit(staleToken))
        assertTrue(coordinator.isActive(currentToken))
        assertFalse(currentSocket.isClosed)

        assertTrue(coordinator.cancel(currentToken))
        assertTrue(currentSocket.isClosed)
    }

    @Test
    fun `cancellation after wire acceptance but before local commit stores neither trust nor route`() {
        val coordinator = PairingAttemptCoordinator()
        val token = coordinator.tryBegin()!!
        assertTrue(coordinator.claimCommit(token))
        val wireAccepted = CountDownLatch(1)
        val allowLocalCommit = CountDownLatch(1)
        val trustStored = AtomicBoolean(false)
        val routeStored = AtomicBoolean(false)
        val localCommitSucceeded = AtomicBoolean(false)

        val worker = thread(name = "pairing-local-commit-race") {
            wireAccepted.countDown()
            assertTrue(allowLocalCommit.await(5, TimeUnit.SECONDS))
            localCommitSucceeded.set(
                coordinator.runIfActive(token) {
                    trustStored.set(true)
                    routeStored.set(true)
                    Unit
                } != null,
            )
        }

        assertTrue(wireAccepted.await(5, TimeUnit.SECONDS))
        assertTrue(coordinator.cancel(token))
        allowLocalCommit.countDown()
        worker.join(5_000)

        assertFalse(worker.isAlive)
        assertFalse(localCommitSucceeded.get())
        assertFalse(trustStored.get())
        assertFalse(routeStored.get())
    }

    @Test
    fun `configuration recreation handoff is memory only and consumed once`() {
        var now = 100L
        val handoff = PairingRecreationHandoff(nowNanos = { now }, ttlNanos = 10L)
        val payload = (PairingIntentRouter.route("android.intent.action.VIEW", qr) as PairingIntentRoute.Accepted).payload

        handoff.offer(PairingRecreationDecision.Restart(payload))

        assertEquals(PairingRecreationDecision.Restart(payload), handoff.take())
        assertNull(handoff.take())
    }

    @Test
    fun `abandoned configuration recreation handoff expires`() {
        var now = 100L
        val handoff = PairingRecreationHandoff(nowNanos = { now }, ttlNanos = 10L)
        val payload = (PairingIntentRouter.route("android.intent.action.VIEW", qr) as PairingIntentRoute.Accepted).payload

        handoff.offer(PairingRecreationDecision.Restart(payload))
        now = 111L

        assertNull(handoff.take())
    }

    @Test
    fun `process death during pairing requires fresh input instead of replaying launch intent`() {
        val payload = (PairingIntentRouter.route("android.intent.action.VIEW", qr) as PairingIntentRoute.Accepted).payload

        assertEquals(PairingRestoreAction.REQUIRE_FRESH_INPUT, pairingRestoreAction(true, null))
        assertEquals(PairingRestoreAction.CONSUME_LAUNCH_INTENT, pairingRestoreAction(false, null))
        assertEquals(
            PairingRestoreAction.RESTART_HANDOFF,
            pairingRestoreAction(true, PairingRecreationDecision.Restart(payload)),
        )
        assertEquals(
            PairingRestoreAction.SUPPRESS,
            pairingRestoreAction(true, PairingRecreationDecision.Suppress),
        )
        assertEquals(
            PairingRestoreAction.REQUIRE_FRESH_INPUT,
            pairingRestoreAction(true, PairingRecreationDecision.RequireFreshInput),
        )
    }

    @Test
    fun `recreation atomically suppresses restart after trust commit`() {
        val coordinator = PairingAttemptCoordinator()
        val payload = (PairingIntentRouter.route("android.intent.action.VIEW", qr) as PairingIntentRoute.Accepted).payload
        val token = coordinator.tryBegin()!!

        assertTrue(
            coordinator.runIfActive(token) {
                assertTrue(coordinator.markTrustCommitted(token))
                Unit
            } != null,
        )

        val retirement = coordinator.tryCancelForRecreation(token, payload)

        assertEquals(PairingRecreationDecision.Suppress, retirement.decision)
        assertFalse(coordinator.isActive(token))
    }

    @Test
    fun `recreation atomically retains only an uncommitted active payload`() {
        val coordinator = PairingAttemptCoordinator()
        val payload = (PairingIntentRouter.route("android.intent.action.VIEW", qr) as PairingIntentRoute.Accepted).payload
        val token = coordinator.tryBegin()!!
        val socket = SSLSocketFactory.getDefault().createSocket() as SSLSocket
        assertTrue(coordinator.claimSocket(token, socket))

        val retirement = coordinator.tryCancelForRecreation(token, payload)

        assertEquals(
            PairingRecreationDecision.Restart(payload),
            retirement.decision,
        )
        assertFalse(coordinator.isActive(token))
        assertFalse(socket.isClosed)

        retirement.closeSockets()

        assertTrue(socket.isClosed)
    }

    @Test
    fun `configuration teardown returns without waiting for a durable commit`() {
        val coordinator = PairingAttemptCoordinator()
        val payload = (PairingIntentRouter.route("android.intent.action.VIEW", qr) as PairingIntentRoute.Accepted).payload
        val token = coordinator.tryBegin()!!
        val commitEntered = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        val retirement = AtomicReference<PairingRecreationRetirement?>()

        val commitWorker = thread(name = "pairing-durable-commit") {
            coordinator.runIfActive(token) {
                commitEntered.countDown()
                assertTrue(releaseCommit.await(5, TimeUnit.SECONDS))
                assertTrue(coordinator.markTrustCommitted(token))
                Unit
            }
        }
        assertTrue(commitEntered.await(5, TimeUnit.SECONDS))

        val teardown = thread(name = "pairing-config-teardown") {
            retirement.set(coordinator.tryCancelForRecreation(token, payload))
        }
        teardown.join(1_000)
        val returnedWithoutCommit = !teardown.isAlive
        releaseCommit.countDown()
        commitWorker.join(5_000)
        teardown.join(5_000)

        assertTrue(returnedWithoutCommit)
        assertFalse(commitWorker.isAlive)
        assertFalse(teardown.isAlive)
        assertEquals(PairingRecreationDecision.RequireFreshInput, retirement.get()?.decision)
        assertTrue(coordinator.isRecreationRequested(token))
        assertTrue(coordinator.cancel(token))
    }

    @Test
    fun `lifecycle cancellation separates ownership retirement from socket close`() {
        val coordinator = PairingAttemptCoordinator()
        val token = coordinator.tryBegin()!!
        val socket = SSLSocketFactory.getDefault().createSocket() as SSLSocket
        assertTrue(coordinator.claimSocket(token, socket))

        coordinator.requestCancellation(token)

        assertTrue(coordinator.isCancellationRequested(token))
        assertFalse(socket.isClosed)

        val retirement = coordinator.retire(token)

        assertTrue(retirement.ended)
        assertFalse(socket.isClosed)

        retirement.closeSockets()

        assertTrue(socket.isClosed)
    }
}
