package dev.hyphen.android.session

import dev.hyphen.android.notifications.NotificationCanceller
import dev.hyphen.android.notifications.NotificationPrivacyMode
import dev.hyphen.android.notifications.NotificationPrivacyPolicy
import dev.hyphen.android.notifications.NotificationProtocol
import dev.hyphen.android.notifications.NotificationReplier
import dev.hyphen.android.notifications.NotificationReplyAttempt
import dev.hyphen.android.text.TextConfirmationQueue
import dev.hyphen.android.text.TextLinkKind
import dev.hyphen.android.text.TextLinkMessage
import dev.hyphen.android.transfer.FileTransferStorage
import dev.hyphen.android.transfer.TransferCancel
import dev.hyphen.android.transfer.TransferManifest
import dev.hyphen.android.transfer.TransferProtocol
import dev.hyphen.android.transfer.TransferReceiver
import dev.hyphen.android.transport.Envelope
import dev.hyphen.android.transport.Json
import dev.hyphen.android.transport.ProtocolSession
import dev.hyphen.android.transport.SessionHandshake
import dev.hyphen.android.ui.ActivityEvent
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProtocolEnvelopeOwnerTest {
    @get:Rule
    val temp = TemporaryFolder()

    private class FakeNotificationRuntime : NotificationRuntimeFacade {
        var policy: NotificationPrivacyPolicy? = null
        var cancelCount = 0
        var active = true

        override fun setPrivacyPolicy(policy: NotificationPrivacyPolicy) {
            this.policy = policy
        }

        override fun localPrivacyFloor(): NotificationPrivacyMode = NotificationPrivacyMode.HIDE_BODY

        override fun canceller(): NotificationCanceller = NotificationCanceller {
            cancelCount += 1
            true
        }

        override fun isActive(sbnKey: String): Boolean = active

        override fun replier(): NotificationReplier = NotificationReplier { _, _, _, _ ->
            NotificationReplyAttempt.Sent
        }
    }

    private class RecordingEvents : ProtocolOwnerEvents {
        val logs = mutableListOf<String>()
        val activities = mutableListOf<ActivityEvent>()
        val confirmations = mutableListOf<dev.hyphen.android.text.TextLinkConfirmationRequest>()
        var privacyApplied = 0

        override fun log(line: String) { logs += line }
        override fun activity(event: ActivityEvent) { activities += event }
        override fun textConfirmation(request: dev.hyphen.android.text.TextLinkConfirmationRequest) {
            confirmations += request
        }
        override fun privacyPolicyApplied() { privacyApplied += 1 }
    }

    private fun envelope(type: String, capability: String, payload: Json.Obj, id: String = "m_1") = Envelope(
        messageId = id,
        sessionId = "s_owner",
        type = type,
        capability = capability,
        seq = 2,
        sentAtUnixMs = 1,
        requiresAck = true,
        payload = payload,
    )

    private fun owner(
        runtime: FakeNotificationRuntime = FakeNotificationRuntime(),
        events: RecordingEvents = RecordingEvents(),
    ): Triple<DefaultProtocolEnvelopeOwner, FakeNotificationRuntime, RecordingEvents> {
        val receiver = TransferReceiver(FileTransferStorage(temp.newFolder()))
        return Triple(
            DefaultProtocolEnvelopeOwner(receiver, { it.file }, runtime, events),
            runtime,
            events,
        )
    }

    private fun activate(
        owner: DefaultProtocolEnvelopeOwner,
        epoch: ProtocolSessionEpoch,
        sent: MutableList<Pair<String, Json.Obj>>,
        send: ((String, Json.Obj) -> String)? = null,
    ) {
        owner.activate(
            ProtocolOwnerSession(
                epoch = epoch,
                capabilities = SessionHandshake.NegotiatedCapabilities.advertised(),
                transferSender = null,
                peerFingerprint = ByteArray(32) { 1 },
                sessionId = "s_owner",
                send = { type, _, _, payload ->
                    sent += type to payload
                    send?.invoke(type, payload) ?: "sent-${sent.size}"
                },
            ),
        )
    }

    @Test
    fun `privacy applies without Activity and flow controlled result retries after any ack`() {
        val (owner, runtime, events) = owner()
        val epoch = ProtocolSessionEpoch.of(7)
        val sent = mutableListOf<Pair<String, Json.Obj>>()
        var reject = true
        activate(owner, epoch, sent) { _, _ ->
            if (reject) throw ProtocolSession.SendRejected("protocol/flow-control", "full")
            "privacy-result"
        }

        owner.handleEnvelope(
            epoch,
            envelope(
                NotificationProtocol.TYPE_PRIVACY_POLICY,
                NotificationProtocol.CAPABILITY,
                Json.obj("defaultMode" to Json.Str("full")),
                "privacy-policy-1",
            ),
        )

        assertEquals(NotificationPrivacyMode.SHOW_FULL, runtime.policy?.defaultMode)
        assertEquals(1, events.privacyApplied)
        reject = false
        owner.handleAck(epoch, "unrelated-transfer-ack")
        assertEquals(NotificationProtocol.TYPE_PRIVACY_RESULT, sent.last().first)
        assertEquals(Json.Str("privacy-policy-1"), sent.last().second["regarding"])
    }

    @Test
    fun `early ack during send does not duplicate result and pumps the newer pending result`() {
        val retry = BoundedProtocolResultRetry()
        val epoch = ProtocolSessionEpoch.of(9)
        val sends = AtomicInteger()
        lateinit var session: ProtocolOwnerSession
        session = ProtocolOwnerSession(
            epoch,
            SessionHandshake.NegotiatedCapabilities.empty(),
            null,
            ByteArray(32),
            "s_early",
        ) { _, _, _, _ ->
            val id = "early-${sends.incrementAndGet()}"
            retry.onAck(epoch, id)
            id
        }
        retry.activate(session)

        retry.offer(epoch, PendingProtocolResult("result", "notifications.v1", Json.Obj.EMPTY))

        assertEquals(1, sends.get())
        assertFalse(retry.hasPendingOrInFlight())
    }

    @Test
    fun `newer privacy result survives timeout of older in flight result`() {
        val retry = BoundedProtocolResultRetry()
        val epoch = ProtocolSessionEpoch.of(10)
        val sentPayloads = mutableListOf<Json.Obj>()
        retry.activate(
            ProtocolOwnerSession(epoch, SessionHandshake.NegotiatedCapabilities.empty(), null, ByteArray(32), "s") {
                    _, _, _, payload ->
                sentPayloads += payload
                "id-${sentPayloads.size}"
            },
        )
        val old = PendingProtocolResult("result", "notifications.v1", Json.obj("v" to Json.Num("1")))
        val newer = PendingProtocolResult("result", "notifications.v1", Json.obj("v" to Json.Num("2")))
        retry.offer(epoch, old)
        retry.offer(epoch, newer)

        retry.onAckTimeout(epoch, "id-1")

        assertEquals(Json.Num("2"), sentPayloads.last()["v"])
    }

    @Test
    fun `result retry stops after three ack timeouts`() {
        val retry = BoundedProtocolResultRetry()
        val epoch = ProtocolSessionEpoch.of(16)
        val sends = AtomicInteger()
        retry.activate(
            ProtocolOwnerSession(epoch, SessionHandshake.NegotiatedCapabilities.empty(), null, ByteArray(32), "s") {
                    _, _, _, _ -> "timeout-${sends.incrementAndGet()}"
            },
        )
        retry.offer(epoch, PendingProtocolResult("result", "notifications.v1", Json.Obj.EMPTY))

        retry.onAckTimeout(epoch, "timeout-1")
        retry.onAckTimeout(epoch, "timeout-2")
        retry.onAckTimeout(epoch, "timeout-3")

        assertEquals(3, sends.get())
        assertFalse(retry.hasPendingOrInFlight())
    }

    @Test
    fun `action result fifo retains every accepted side effect result`() {
        val retry = BoundedProtocolResultRetry(latestWins = false, maxTotal = 3)
        val epoch = ProtocolSessionEpoch.of(17)
        val sentValues = mutableListOf<Long>()
        retry.activate(
            ProtocolOwnerSession(epoch, SessionHandshake.NegotiatedCapabilities.empty(), null, ByteArray(32), "s") {
                    _, _, _, payload ->
                sentValues.add(checkNotNull((payload["v"] as Json.Num).asLong()))
                "fifo-${sentValues.size}"
            },
        )
        repeat(3) { index ->
            assertTrue(
                retry.offer(
                    epoch,
                    PendingProtocolResult(
                        "action.result",
                        "notifications.v1",
                        Json.obj("v" to Json.Num((index + 1).toString())),
                    ),
                ),
            )
        }

        retry.onAck(epoch, "fifo-1")
        retry.onAck(epoch, "fifo-2")

        assertEquals(listOf(1L, 2L, 3L), sentValues)
    }

    @Test
    fun `retiring a session invalidates a reserved result without throwing`() {
        val retry = BoundedProtocolResultRetry(latestWins = false, maxTotal = 1)
        val epoch = ProtocolSessionEpoch.of(19)
        retry.activate(
            ProtocolOwnerSession(epoch, SessionHandshake.NegotiatedCapabilities.empty(), null, ByteArray(32), "s") {
                    _, _, _, _ -> "unused"
            },
        )

        assertTrue(retry.tryReserve(epoch))
        retry.retire(epoch)

        assertFalse(
            retry.offerReserved(
                epoch,
                PendingProtocolResult("action.result", "notifications.v1", Json.Obj.EMPTY),
            ),
        )
        assertFalse(retry.hasPendingOrInFlight())
    }

    @Test
    fun `bounded action queue refuses side effect before execution when saturated`() {
        val (owner, runtime, _) = owner()
        val epoch = ProtocolSessionEpoch.of(11)
        val sent = mutableListOf<Pair<String, Json.Obj>>()
        activate(owner, epoch, sent) { _, _ ->
            throw ProtocolSession.SendRejected("protocol/flow-control", "full")
        }
        repeat(33) { index ->
            owner.handleEnvelope(
                epoch,
                envelope(
                    NotificationProtocol.TYPE_DISMISS_REQUEST,
                    NotificationProtocol.CAPABILITY,
                    Json.obj("sbnKey" to Json.Str("key-$index")),
                    "dismiss-$index",
                ),
            )
        }

        assertEquals(32, runtime.cancelCount)
        val flowError = sent.last { it.first == Envelope.TYPE_ERROR }.second
        assertEquals(Json.Bool(true), flowError["retryable"])
    }

    @Test
    fun `failed notification action does not create a successful activity row`() {
        val (owner, runtime, events) = owner()
        runtime.active = false
        val epoch = ProtocolSessionEpoch.of(15)
        activate(owner, epoch, mutableListOf())

        owner.handleEnvelope(
            epoch,
            envelope(
                NotificationProtocol.TYPE_DISMISS_REQUEST,
                NotificationProtocol.CAPABILITY,
                Json.obj("sbnKey" to Json.Str("missing-key")),
                "dismiss-missing",
            ),
        )

        assertEquals(0, runtime.cancelCount)
        assertTrue(events.activities.none { it is ActivityEvent.NotificationActionPerformed })
        assertTrue(events.logs.any { it.contains("without clearing") })
    }

    @Test
    fun `transfer manifest chunk resume and cancel run without Activity`() {
        val (owner, _, events) = owner()
        val epoch = ProtocolSessionEpoch.of(12)
        val sent = mutableListOf<Pair<String, Json.Obj>>()
        activate(owner, epoch, sent)
        val bytes = byteArrayOf(1, 2, 3)
        val manifest = TransferManifest(
            fileId = "f_owner_path",
            filename = "owner.bin",
            sizeBytes = bytes.size.toLong(),
            mimeType = "application/octet-stream",
            sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
            chunkSizeBytes = 1024,
            chunkCount = 1,
        )
        owner.handleEnvelope(epoch, envelope(TransferProtocol.TYPE_MANIFEST, TransferProtocol.CAPABILITY, manifest.toJson()))
        owner.handleEnvelope(
            epoch,
            envelope(
                TransferProtocol.TYPE_CHUNK,
                TransferProtocol.CAPABILITY,
                dev.hyphen.android.transfer.TransferChunk(manifest.fileId, 0, bytes).toJson(),
                "chunk-1",
            ),
        )
        owner.handleEnvelope(
            epoch,
            envelope(
                TransferProtocol.TYPE_RESUME_REQUEST,
                TransferProtocol.CAPABILITY,
                Json.obj("fileId" to Json.Str(manifest.fileId)),
                "resume-1",
            ),
        )
        owner.handleEnvelope(
            epoch,
            envelope(
                TransferProtocol.TYPE_CANCEL,
                TransferProtocol.CAPABILITY,
                TransferCancel(manifest.fileId, true).toJson(),
                "cancel-1",
            ),
        )

        assertTrue(events.activities.any { it is ActivityEvent.TransferCompleted })
        assertTrue(events.activities.any { it is ActivityEvent.TransferCancelled })
    }

    @Test
    fun `text confirmation survives observer absence and stale epoch is rejected`() {
        val (owner, _, events) = owner()
        val first = ProtocolSessionEpoch.of(13)
        val second = ProtocolSessionEpoch.of(14)
        activate(owner, first, mutableListOf())
        val message = envelope(
            TextLinkMessage.TYPE_SEND,
            TextLinkMessage.CAPABILITY,
            Json.obj("kind" to Json.Str("text"), "value" to Json.Str("private body")),
            "text-1",
        )
        owner.handleEnvelope(first, message)

        assertEquals(1, owner.pendingTextConfirmations().size)
        val queue = TextConfirmationQueue()
        owner.pendingTextConfirmations().forEach(queue::enqueue)
        assertEquals(TextLinkKind.TEXT, queue.activateNext()?.message?.kind)

        owner.retire(first)
        activate(owner, second, mutableListOf())
        owner.handleEnvelope(first, message.copy(messageId = "stale-text"))
        assertTrue(owner.pendingTextConfirmations().isEmpty())
        assertEquals(1, events.confirmations.size)
    }

    @Test
    fun `text confirmation saturation rejects before enqueue with retryable flow control`() {
        val (owner, _, events) = owner()
        val epoch = ProtocolSessionEpoch.of(18)
        val sent = mutableListOf<Pair<String, Json.Obj>>()
        activate(owner, epoch, sent)
        repeat(65) { index ->
            owner.handleEnvelope(
                epoch,
                envelope(
                    TextLinkMessage.TYPE_SEND,
                    TextLinkMessage.CAPABILITY,
                    Json.obj("kind" to Json.Str("text"), "value" to Json.Str("body-$index")),
                    "text-$index",
                ),
            )
        }

        assertEquals(64, owner.pendingTextConfirmations().size)
        assertEquals(64, events.confirmations.size)
        val error = sent.last { it.first == Envelope.TYPE_ERROR }.second
        assertEquals(Json.Bool(true), error["retryable"])
    }
}
