package dev.hyphen.android.notifications

import dev.hyphen.android.transport.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class NotificationListenerLifecycleTest {

    @Before
    fun resetRuntime() {
        HyphenNotificationListenerRuntime.resetForTests()
    }

    @Test
    fun `listener starts disconnected`() {
        val lifecycle = NotificationListenerLifecycle()

        assertEquals(NotificationListenerConnectionState.DISCONNECTED, lifecycle.state)
        assertEquals(emptyList<NotificationListenerLifecycleEvent>(), lifecycle.events)
    }

    @Test
    fun `connected and disconnected callbacks update state and event log`() {
        val lifecycle = NotificationListenerLifecycle()

        lifecycle.onConnected()
        lifecycle.onDisconnected()

        assertEquals(NotificationListenerConnectionState.DISCONNECTED, lifecycle.state)
        assertEquals(
            listOf(
                NotificationListenerLifecycleEvent.CONNECTED,
                NotificationListenerLifecycleEvent.DISCONNECTED,
            ),
            lifecycle.events,
        )
    }

    @Test
    fun `destroy always leaves listener disconnected`() {
        val lifecycle = NotificationListenerLifecycle()

        lifecycle.onConnected()
        lifecycle.onDestroyed()

        assertEquals(NotificationListenerConnectionState.DISCONNECTED, lifecycle.state)
        assertEquals(
            listOf(
                NotificationListenerLifecycleEvent.CONNECTED,
                NotificationListenerLifecycleEvent.DESTROYED,
            ),
            lifecycle.events,
        )
    }

    @Test
    fun `dispatch queue does not block caller behind a slow send`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondRan = AtomicBoolean(false)
        val queue = NotificationDispatchQueue(capacity = 1)

        assertTrue(queue.submit {
            started.countDown()
            release.await(3, TimeUnit.SECONDS)
        })
        assertTrue(started.await(1, TimeUnit.SECONDS))
        assertTrue(queue.submit { secondRan.set(true) })

        release.countDown()
        Thread.sleep(50)
        assertTrue(secondRan.get())
        queue.shutdown()
    }

    @Test
    fun `dispatch queue drops oldest best-effort backlog without blocking caller`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val firstQueuedRan = CountDownLatch(1)
        val secondQueuedRan = CountDownLatch(1)
        val queue = NotificationDispatchQueue(capacity = 1)

        assertTrue(queue.submit {
            started.countDown()
            release.await(3, TimeUnit.SECONDS)
        })
        assertTrue(started.await(1, TimeUnit.SECONDS))
        assertTrue(queue.submit { firstQueuedRan.countDown() })

        val submitter = Executors.newSingleThreadExecutor()
        val submitted = submitter.submit<Boolean> {
            queue.submit { secondQueuedRan.countDown() }
        }
        assertTrue(submitted.get(100, TimeUnit.MILLISECONDS))
        assertFalse(firstQueuedRan.await(100, TimeUnit.MILLISECONDS))

        release.countDown()
        assertTrue(secondQueuedRan.await(1, TimeUnit.SECONDS))
        assertFalse(firstQueuedRan.await(100, TimeUnit.MILLISECONDS))
        assertEquals(1, queue.droppedCount())
        queue.shutdown()
        submitter.shutdownNow()
    }

    @Test
    fun `dispatch queue runs the removal sweep and coalesces repeated pokes`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sweeps = AtomicInteger(0)
        val queue = NotificationDispatchQueue(capacity = 4)
        queue.setRemovalSweep { sweeps.incrementAndGet() }

        // Occupy the single worker so the pokes pile up before it can drain them.
        assertTrue(queue.submit {
            started.countDown()
            release.await(3, TimeUnit.SECONDS)
        })
        assertTrue(started.await(1, TimeUnit.SECONDS))
        repeat(10) { assertTrue(queue.requestRemovalSweep()) }

        release.countDown()
        Thread.sleep(150)
        // Ten pokes collapse into a single sweep run (worker was busy the whole time).
        assertTrue(sweeps.get() in 1..2)
        queue.shutdown()
    }

    @Test
    fun `dispatch queue drains already accepted task after shutdown`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val queuedRan = CountDownLatch(1)
        val queue = NotificationDispatchQueue(capacity = 2)

        assertTrue(queue.submit {
            started.countDown()
            while (release.count > 0) {
                try {
                    release.await(3, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                }
            }
        })
        assertTrue(started.await(1, TimeUnit.SECONDS))
        assertTrue(queue.submit { queuedRan.countDown() })

        queue.shutdown()
        assertFalse(queuedRan.await(50, TimeUnit.MILLISECONDS))
        release.countDown()

        assertTrue(queuedRan.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun `dispatch queue rejects submit after shutdown`() {
        val shouldNotRun = CountDownLatch(1)
        val queue = NotificationDispatchQueue(capacity = 1)

        queue.shutdown()

        assertFalse(queue.submit { shouldNotRun.countDown() })
        assertFalse(shouldNotRun.await(100, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `runtime notification callbacks return promptly while dispatch is blocked`() {
        val sendStarted = CountDownLatch(1)
        val releaseSend = CountDownLatch(1)
        val outbox = BlockingAllOutbox(sendStarted, releaseSend)
        HyphenNotificationListenerRuntime.onConnected()
        HyphenNotificationListenerRuntime.bindNotificationOutbox(outbox)

        assertTrue(HyphenNotificationListenerRuntime.onNotificationPosted(payload("0|com.chat|1|thread-123|10101", "first")))
        assertTrue(sendStarted.await(1, TimeUnit.SECONDS))

        val submitter = Executors.newSingleThreadExecutor()
        val posted = submitter.submit<Boolean> {
            HyphenNotificationListenerRuntime.onNotificationPosted(payload("0|com.chat|2|thread-123|10101", "second"))
        }
        val removed = submitter.submit<Boolean> {
            HyphenNotificationListenerRuntime.onNotificationRemoved("0|com.chat|2|thread-123|10101")
        }

        assertTrue(posted.get(100, TimeUnit.MILLISECONDS))
        assertTrue(removed.get(100, TimeUnit.MILLISECONDS))
        releaseSend.countDown()
        submitter.shutdownNow()
    }

    @Test
    fun `removal sweep delivers every distinct removal without losing any`() {
        val keys = (0 until 50).map { "0|com.chat|$it|thread-123|10101" }
        HyphenNotificationListenerRuntime.onConnected(keys.map { payload(it, "body-$it") })
        val outbox = RecordingNotificationOutbox()
        HyphenNotificationListenerRuntime.bindNotificationOutbox(outbox)
        assertTrue(waitForEnvelopeCount(outbox, keys.size))

        keys.forEach { HyphenNotificationListenerRuntime.onNotificationRemoved(it) }

        assertTrue(waitForEnvelopeCount(outbox, keys.size * 2))
        val removed = outbox.envelopes.count { it.type == NotificationProtocol.TYPE_REMOVED }
        assertEquals(keys.size, removed)
        keys.forEach { assertFalse(HyphenNotificationListenerRuntime.isNotificationActive(it)) }
    }

    @Test
    fun `runtime coalesces repeated removals of the same sbnKey into one send`() {
        val key = "0|com.chat|7|thread-123|10101"
        HyphenNotificationListenerRuntime.onConnected(listOf(payload(key, "active")))
        val removedStarted = CountDownLatch(1)
        val releaseRemoved = CountDownLatch(1)
        val outbox = BlockingRemovedOutbox(removedStarted, releaseRemoved)
        HyphenNotificationListenerRuntime.bindNotificationOutbox(outbox)
        assertTrue(waitForBlockingEnvelopeCount(outbox, 1))

        // First removal triggers the sweep and blocks mid-send, so the key stays in
        // the set; the next four removals see it already pending and coalesce.
        repeat(5) { HyphenNotificationListenerRuntime.onNotificationRemoved(key) }
        assertTrue(removedStarted.await(1, TimeUnit.SECONDS))
        assertTrue(HyphenNotificationListenerRuntime.removalCoalescedCount() >= 4)

        releaseRemoved.countDown()
        Thread.sleep(150)
        assertEquals(1, outbox.envelopes.count { it.type == NotificationProtocol.TYPE_REMOVED })
        assertFalse(HyphenNotificationListenerRuntime.isNotificationActive(key))
    }

    @Test
    fun `negotiated privacy session fail-closes active snapshot until policy arrives`() {
        HyphenNotificationListenerRuntime.onConnected(
            listOf(payload("0|com.chat|7|thread-123|10101", "secret active body")),
        )
        val outbox = RecordingNotificationOutbox()

        HyphenNotificationListenerRuntime.bindNotificationOutbox(
            outbox = outbox,
            requireRemotePrivacyPolicy = true,
        )

        assertTrue(waitForEnvelopeCount(outbox, 1))
        assertTrue(HyphenNotificationListenerRuntime.isPrivacyPolicyAwaitingRemote())
        val sent = outbox.envelopes.single()
        assertEquals(NotificationProtocol.TYPE_POSTED, sent.type)
        assertFalse(sent.payload.entries.containsKey("text"))
        assertFalse(sent.payload.entries.containsKey("title"))
        assertFalse(sent.payload.encode().contains("secret active body"))
    }

    @Test
    fun `remote privacy policy unlocks hideBody snapshot refresh after fail-closed bind`() {
        HyphenNotificationListenerRuntime.onConnected(
            listOf(payload("0|com.chat|7|thread-123|10101", "secret active body")),
        )
        val outbox = RecordingNotificationOutbox()
        HyphenNotificationListenerRuntime.bindNotificationOutbox(
            outbox = outbox,
            requireRemotePrivacyPolicy = true,
        )
        assertTrue(waitForEnvelopeCount(outbox, 1))

        HyphenNotificationListenerRuntime.setNotificationPrivacyPolicy(
            NotificationPrivacyPolicy(defaultMode = NotificationPrivacyMode.HIDE_BODY),
        )
        assertTrue(waitForEnvelopeCount(outbox, 2))
        assertFalse(HyphenNotificationListenerRuntime.isPrivacyPolicyAwaitingRemote())

        val refreshed = outbox.envelopes[1]
        assertEquals(NotificationProtocol.TYPE_UPDATED, refreshed.type)
        assertEquals(Json.Str("Example"), refreshed.payload["title"])
        assertFalse(refreshed.payload.entries.containsKey("text"))
        assertFalse(refreshed.payload.encode().contains("secret active body"))
    }

    @Test
    fun `local privacy mode is ignored while remote policy is pending`() {
        HyphenNotificationListenerRuntime.onConnected(
            listOf(payload("0|com.chat|7|thread-123|10101", "secret active body")),
        )
        val outbox = RecordingNotificationOutbox()
        HyphenNotificationListenerRuntime.bindNotificationOutbox(
            outbox = outbox,
            requireRemotePrivacyPolicy = true,
        )
        assertTrue(waitForEnvelopeCount(outbox, 1))

        HyphenNotificationListenerRuntime.setNotificationPrivacyMode(NotificationPrivacyMode.SHOW_FULL)
        Thread.sleep(100)
        assertEquals(1, outbox.envelopes.size)
        assertTrue(HyphenNotificationListenerRuntime.isPrivacyPolicyAwaitingRemote())
        assertFalse(outbox.envelopes.single().payload.encode().contains("secret active body"))
    }

    @Test
    fun `rebind after remote policy keeps negotiated filtering instead of fail-closed reset`() {
        HyphenNotificationListenerRuntime.onConnected(
            listOf(payload("0|com.chat|7|thread-123|10101", "secret active body")),
        )
        HyphenNotificationListenerRuntime.setNotificationPrivacyPolicy(
            NotificationPrivacyPolicy(defaultMode = NotificationPrivacyMode.HIDE_BODY),
        )

        val secondOutbox = RecordingNotificationOutbox()
        HyphenNotificationListenerRuntime.bindNotificationOutbox(
            outbox = secondOutbox,
            requireRemotePrivacyPolicy = true,
        )

        assertTrue(waitForEnvelopeCount(secondOutbox, 1))
        assertFalse(HyphenNotificationListenerRuntime.isPrivacyPolicyAwaitingRemote())
        val sent = secondOutbox.envelopes.single()
        assertEquals(Json.Str("Example"), sent.payload["title"])
        assertFalse(sent.payload.entries.containsKey("text"))
    }

    @Test
    fun `rebind with negotiated privacy fail-closes snapshot again until policy arrives`() {
        HyphenNotificationListenerRuntime.onConnected(
            listOf(payload("0|com.chat|7|thread-123|10101", "secret active body")),
        )
        val firstOutbox = RecordingNotificationOutbox()
        HyphenNotificationListenerRuntime.bindNotificationOutbox(
            outbox = firstOutbox,
            requireRemotePrivacyPolicy = true,
        )
        assertTrue(waitForEnvelopeCount(firstOutbox, 1))

        val secondOutbox = RecordingNotificationOutbox()
        HyphenNotificationListenerRuntime.bindNotificationOutbox(
            outbox = secondOutbox,
            requireRemotePrivacyPolicy = true,
        )
        assertTrue(waitForEnvelopeCount(secondOutbox, 1))
        assertTrue(HyphenNotificationListenerRuntime.isPrivacyPolicyAwaitingRemote())
        assertFalse(secondOutbox.envelopes.single().payload.encode().contains("secret active body"))
    }

    @Test
    fun `legacy outbox bind without negotiated privacy keeps full active snapshot`() {
        HyphenNotificationListenerRuntime.onConnected(
            listOf(payload("0|com.chat|7|thread-123|10101", "active before bind")),
        )
        val outbox = RecordingNotificationOutbox()

        HyphenNotificationListenerRuntime.bindNotificationOutbox(outbox)

        assertTrue(waitForEnvelopeCount(outbox, 1))
        val sent = outbox.envelopes.single()
        assertEquals(NotificationProtocol.TYPE_POSTED, sent.type)
        assertEquals(Json.Str("0|com.chat|7|thread-123|10101"), sent.payload["sbnKey"])
        assertEquals(Json.Str("active before bind"), sent.payload["text"])
        assertTrue(HyphenNotificationListenerRuntime.isNotificationActive("0|com.chat|7|thread-123|10101"))
    }

    @Test
    fun `hidden body mode strips runtime active snapshot when outbox binds and rebinds`() {
        HyphenNotificationListenerRuntime.setNotificationPrivacyMode(NotificationPrivacyMode.HIDE_BODY)
        HyphenNotificationListenerRuntime.onConnected(
            listOf(payload("0|com.chat|7|thread-123|10101", "secret active body")),
        )
        val firstOutbox = RecordingNotificationOutbox()
        HyphenNotificationListenerRuntime.bindNotificationOutbox(firstOutbox)
        assertTrue(waitForEnvelopeCount(firstOutbox, 1))

        val secondOutbox = RecordingNotificationOutbox()
        HyphenNotificationListenerRuntime.bindNotificationOutbox(secondOutbox)

        assertTrue(waitForEnvelopeCount(secondOutbox, 1))
        listOf(firstOutbox.envelopes.single(), secondOutbox.envelopes.single()).forEach { sent ->
            assertEquals(NotificationProtocol.TYPE_POSTED, sent.type)
            assertEquals(Json.Str("Example"), sent.payload["title"])
            assertFalse(sent.payload.entries.containsKey("text"))
            assertFalse(sent.payload.encode().contains("secret active body"))
        }
    }

    @Test
    fun `rebind preserves active state and removed event goes to new outbox`() {
        HyphenNotificationListenerRuntime.onConnected(
            listOf(payload("0|com.chat|7|thread-123|10101", "active")),
        )
        val firstOutbox = RecordingNotificationOutbox()
        HyphenNotificationListenerRuntime.bindNotificationOutbox(firstOutbox)
        assertTrue(waitForEnvelopeCount(firstOutbox, 1))

        val secondOutbox = RecordingNotificationOutbox()
        HyphenNotificationListenerRuntime.bindNotificationOutbox(secondOutbox)
        assertTrue(waitForEnvelopeCount(secondOutbox, 1))
        HyphenNotificationListenerRuntime.onNotificationRemoved("0|com.chat|7|thread-123|10101")

        assertTrue(waitForEnvelopeCount(secondOutbox, 2))
        assertEquals(NotificationProtocol.TYPE_POSTED, secondOutbox.envelopes[0].type)
        assertEquals(NotificationProtocol.TYPE_REMOVED, secondOutbox.envelopes[1].type)
        assertEquals(Json.Str("0|com.chat|7|thread-123|10101"), secondOutbox.envelopes[1].payload["sbnKey"])
        assertFalse(HyphenNotificationListenerRuntime.isNotificationActive("0|com.chat|7|thread-123|10101"))
    }

    @Test
    fun `pending removed notification is replayed as removal during rebind`() {
        HyphenNotificationListenerRuntime.onConnected(
            listOf(payload("0|com.chat|7|thread-123|10101", "active")),
        )
        val removedStarted = CountDownLatch(1)
        val releaseRemoved = CountDownLatch(1)
        val firstOutbox = BlockingRemovedOutbox(removedStarted, releaseRemoved)
        HyphenNotificationListenerRuntime.bindNotificationOutbox(firstOutbox)
        assertTrue(waitForBlockingEnvelopeCount(firstOutbox, 1))

        assertTrue(HyphenNotificationListenerRuntime.onNotificationRemoved("0|com.chat|7|thread-123|10101"))
        assertTrue(removedStarted.await(1, TimeUnit.SECONDS))
        assertFalse(HyphenNotificationListenerRuntime.isNotificationActive("0|com.chat|7|thread-123|10101"))

        val secondOutbox = RecordingNotificationOutbox()
        HyphenNotificationListenerRuntime.bindNotificationOutbox(secondOutbox)
        assertTrue(waitForEnvelopeCount(secondOutbox, 1))
        releaseRemoved.countDown()

        assertTrue(waitForBlockingEnvelopeCount(firstOutbox, 2))
        assertEquals(NotificationProtocol.TYPE_REMOVED, firstOutbox.envelopes[1].type)
        assertEquals(NotificationProtocol.TYPE_REMOVED, secondOutbox.envelopes.single().type)
        assertEquals(Json.Str("0|com.chat|7|thread-123|10101"), secondOutbox.envelopes.single().payload["sbnKey"])
        assertFalse(HyphenNotificationListenerRuntime.isNotificationActive("0|com.chat|7|thread-123|10101"))
    }

    @Test
    fun `failed removed send remains pending and flushes to rebound outbox`() {
        HyphenNotificationListenerRuntime.onConnected(
            listOf(payload("0|com.chat|7|thread-123|10101", "active")),
        )
        val removedAttempted = CountDownLatch(1)
        val firstOutbox = FailingRemovedOutbox(removedAttempted)
        HyphenNotificationListenerRuntime.bindNotificationOutbox(firstOutbox)
        assertTrue(waitForFailingEnvelopeCount(firstOutbox, 1))

        assertTrue(HyphenNotificationListenerRuntime.onNotificationRemoved("0|com.chat|7|thread-123|10101"))
        assertTrue(removedAttempted.await(1, TimeUnit.SECONDS))

        val secondOutbox = RecordingNotificationOutbox()
        HyphenNotificationListenerRuntime.bindNotificationOutbox(secondOutbox)

        assertTrue(waitForEnvelopeCount(secondOutbox, 1))
        assertEquals(NotificationProtocol.TYPE_REMOVED, secondOutbox.envelopes.single().type)
        assertEquals(Json.Str("0|com.chat|7|thread-123|10101"), secondOutbox.envelopes.single().payload["sbnKey"])
        assertFalse(HyphenNotificationListenerRuntime.isNotificationActive("0|com.chat|7|thread-123|10101"))
    }

    @Test
    fun `removed notification updates local active state when no outbox can accept send`() {
        HyphenNotificationListenerRuntime.onConnected(
            listOf(payload("0|com.chat|7|thread-123|10101", "active")),
        )
        val firstOutbox = RecordingNotificationOutbox()
        HyphenNotificationListenerRuntime.bindNotificationOutbox(firstOutbox)
        assertTrue(waitForEnvelopeCount(firstOutbox, 1))
        HyphenNotificationListenerRuntime.clearNotificationOutbox()

        assertFalse(HyphenNotificationListenerRuntime.onNotificationRemoved("0|com.chat|7|thread-123|10101"))
        assertFalse(HyphenNotificationListenerRuntime.isNotificationActive("0|com.chat|7|thread-123|10101"))

        val secondOutbox = RecordingNotificationOutbox()
        HyphenNotificationListenerRuntime.bindNotificationOutbox(secondOutbox)
        assertTrue(waitForEnvelopeCount(secondOutbox, 1))
        assertEquals(NotificationProtocol.TYPE_REMOVED, secondOutbox.envelopes.single().type)
    }

    @Test
    fun `disconnect and destroy clear active notification keys`() {
        HyphenNotificationListenerRuntime.onConnected(
            listOf(payload("0|com.chat|7|thread-123|10101", "active")),
        )
        assertTrue(HyphenNotificationListenerRuntime.isNotificationActive("0|com.chat|7|thread-123|10101"))

        HyphenNotificationListenerRuntime.onDisconnected()
        assertFalse(HyphenNotificationListenerRuntime.isNotificationActive("0|com.chat|7|thread-123|10101"))

        HyphenNotificationListenerRuntime.onConnected(
            listOf(payload("0|com.chat|7|thread-123|10101", "active")),
        )
        HyphenNotificationListenerRuntime.onDestroyed()
        assertFalse(HyphenNotificationListenerRuntime.isNotificationActive("0|com.chat|7|thread-123|10101"))
    }

    private fun waitForEnvelopeCount(outbox: RecordingNotificationOutbox, expected: Int): Boolean {
        repeat(20) {
            if (outbox.envelopes.size >= expected) return true
            Thread.sleep(25)
        }
        return outbox.envelopes.size >= expected
    }

    private fun waitForBlockingEnvelopeCount(outbox: BlockingRemovedOutbox, expected: Int): Boolean {
        repeat(20) {
            if (outbox.envelopes.size >= expected) return true
            Thread.sleep(25)
        }
        return outbox.envelopes.size >= expected
    }

    private fun waitForFailingEnvelopeCount(outbox: FailingRemovedOutbox, expected: Int): Boolean {
        repeat(20) {
            if (outbox.envelopes.size >= expected) return true
            Thread.sleep(25)
        }
        return outbox.envelopes.size >= expected
    }

    private fun payload(sbnKey: String, text: String): NormalizedNotificationPayload =
        NormalizedNotificationPayload(
            sbnKey = sbnKey,
            packageName = "com.example",
            title = "Example",
            text = text,
            category = "msg",
            isClearable = true,
        )

    private class BlockingRemovedOutbox(
        private val removedStarted: CountDownLatch,
        private val releaseRemoved: CountDownLatch,
    ) : NotificationOutbox {
        val envelopes = java.util.Collections.synchronizedList(mutableListOf<SentNotificationEnvelope>())

        override fun send(
            type: String,
            capability: String,
            requiresAck: Boolean,
            payload: Json.Obj,
        ): String {
            if (type == NotificationProtocol.TYPE_REMOVED) {
                removedStarted.countDown()
                releaseRemoved.await(3, TimeUnit.SECONDS)
            }
            envelopes += SentNotificationEnvelope(type, capability, requiresAck, payload)
            return "01JZ0000000000000000000000"
        }
    }

    private class BlockingAllOutbox(
        private val sendStarted: CountDownLatch,
        private val releaseSend: CountDownLatch,
    ) : NotificationOutbox {
        override fun send(
            type: String,
            capability: String,
            requiresAck: Boolean,
            payload: Json.Obj,
        ): String {
            sendStarted.countDown()
            releaseSend.await(3, TimeUnit.SECONDS)
            return "01JZ0000000000000000000000"
        }
    }

    private class FailingRemovedOutbox(
        private val removedAttempted: CountDownLatch,
    ) : NotificationOutbox {
        val envelopes = java.util.Collections.synchronizedList(mutableListOf<SentNotificationEnvelope>())

        override fun send(
            type: String,
            capability: String,
            requiresAck: Boolean,
            payload: Json.Obj,
        ): String {
            if (type == NotificationProtocol.TYPE_REMOVED) {
                removedAttempted.countDown()
                throw IllegalStateException("closed outbox")
            }
            envelopes += SentNotificationEnvelope(type, capability, requiresAck, payload)
            return "01JZ0000000000000000000000"
        }
    }
}
