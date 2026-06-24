package dev.hyphen.android.transport

import java.security.cert.X509Certificate
import javax.net.ssl.SSLSocket

/**
 * Hello exchange over an authenticated TLS socket (HYP-M2-013, protocol
 * v0 §4): the initiator sends `hello` (envelope sessionId null, payload
 * per capability.schema.json) and the responder replies `hello` carrying
 * the assigned sessionId in the envelope — the SAME id as before when a
 * valid resume token was presented (that equality IS the resume signal)
 * — plus a fresh single-use resume token for the next reconnect.
 *
 * v0 notes: handshake hellos travel with requiresAck=false (the reply is
 * the acknowledgment; doc-sync in M2-015), and seq restarts per
 * connection (hello=1, the session continues at 2).
 */
object SessionHandshake {

    const val HANDSHAKE_TIMEOUT_MS = 10_000
    const val CAPABILITY_NOTIFICATIONS = "notifications.v1"
    const val CAPABILITY_TRANSFER = "transfer.v1"
    const val CAPABILITY_TEXT = "text.v1"
    const val CAPABILITY_DIAGNOSTICS = "diagnostics.v1"

    private const val DEFAULT_TRANSFER_MAX_CHUNK_BYTES = 1_048_576
    private const val MIN_TRANSFER_MAX_CHUNK_BYTES = 1024
    private const val MAX_TRANSFER_MAX_CHUNK_BYTES = 2_097_152
    private val DEVICE_KINDS = setOf("android", "macos")
    private val APP_VERSION = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+([\\-+][0-9A-Za-z.\\-]+)?$")
    private val CAPABILITY_NAME = Regex("^[a-z][a-z0-9]*\\.v[0-9]+$")

    data class DeviceInfo(
        val kind: String, // "android" | "macos"
        val appVersion: String,
        val deviceName: String? = null,
    )

    data class Result(
        val sessionId: String,
        /** Token to present on the NEXT reconnect (null if peer issued none). */
        val resumeToken: String?,
        val resumed: Boolean,
        val peerDevice: DeviceInfo?,
        val negotiatedCapabilities: NegotiatedCapabilities,
    )

    data class NegotiatedCapabilities(
        private val entries: Map<String, Json.Obj>,
    ) {
        fun contains(name: String): Boolean = entries.containsKey(name)

        fun notificationReplyEnabled(): Boolean =
            entries[CAPABILITY_NOTIFICATIONS]?.string("reply", "off")?.let { it != "off" } == true

        fun notificationDismissEnabled(): Boolean =
            entries[CAPABILITY_NOTIFICATIONS]?.bool("dismiss", false) == true

        fun notificationPrivacyPolicyEnabled(): Boolean =
            entries[CAPABILITY_NOTIFICATIONS]?.bool("privacyPolicy", false) == true

        fun transferMaxChunkBytes(): Int? =
            ((entries[CAPABILITY_TRANSFER]?.get("maxChunkBytes") as? Json.Num)?.asLong())?.toInt()

        /** The negotiated text.v1 direction (null when text was not negotiated). */
        fun textDirection(): String? =
            (entries[CAPABILITY_TEXT]?.get("direction") as? Json.Str)?.value

        /**
         * Finalize text.v1 direction once device kinds are known (responder
         * side). Direction is expressed from the canonical android-endpoint
         * frame; the client adopts this verbatim. Drops the text capability
         * entirely when no compatible flow exists (review dim 05-02).
         */
        fun resolveTextDirection(
            localKind: String,
            localDir: String,
            peerKind: String?,
            peerDir: String?,
        ): NegotiatedCapabilities {
            if (!contains(CAPABILITY_TEXT)) return this
            val direction = negotiateTextDirection(
                localKind,
                localDir,
                peerKind ?: localKind,
                peerDir ?: "bidirectional",
            )
            val updated = LinkedHashMap(entries)
            if (direction == "none") {
                updated.remove(CAPABILITY_TEXT)
            } else {
                updated[CAPABILITY_TEXT] = Json.obj("direction" to Json.Str(direction))
            }
            return NegotiatedCapabilities(updated)
        }

        fun toJson(): Json.Obj = Json.Obj(entries)

        fun intersect(peer: NegotiatedCapabilities): NegotiatedCapabilities {
            val result = linkedMapOf<String, Json.Obj>()
            if (contains(CAPABILITY_NOTIFICATIONS) && peer.contains(CAPABILITY_NOTIFICATIONS)) {
                val local = entries.getValue(CAPABILITY_NOTIFICATIONS)
                val remote = peer.entries.getValue(CAPABILITY_NOTIFICATIONS)
                result[CAPABILITY_NOTIFICATIONS] = Json.obj(
                    "reply" to Json.Str(minReply(local.string("reply", "off"), remote.string("reply", "off"))),
                    "dismiss" to Json.Bool(local.bool("dismiss", false) && remote.bool("dismiss", false)),
                    "privacyPolicy" to Json.Bool(local.bool("privacyPolicy", false) && remote.bool("privacyPolicy", false)),
                )
            }
            if (contains(CAPABILITY_TRANSFER) && peer.contains(CAPABILITY_TRANSFER)) {
                val local = entries.getValue(CAPABILITY_TRANSFER)
                val remote = peer.entries.getValue(CAPABILITY_TRANSFER)
                result[CAPABILITY_TRANSFER] = Json.obj(
                    "resume" to Json.Bool(local.bool("resume", false) && remote.bool("resume", false)),
                    "maxChunkBytes" to Json.Num(
                        minOf(
                            local.int("maxChunkBytes", DEFAULT_TRANSFER_MAX_CHUNK_BYTES),
                            remote.int("maxChunkBytes", DEFAULT_TRANSFER_MAX_CHUNK_BYTES),
                        ).toString(),
                    ),
                )
            }
            if (contains(CAPABILITY_TEXT) && peer.contains(CAPABILITY_TEXT)) {
                // Carry the left operand's direction through instead of
                // fabricating bidirectional (review dim 05-02). The responder
                // finalizes the real direction via resolveTextDirection() once
                // device kinds are known; the client adopts the responder's
                // already-resolved value (its hello reply is the left operand).
                result[CAPABILITY_TEXT] = Json.obj(
                    "direction" to Json.Str(entries.getValue(CAPABILITY_TEXT).string("direction", "bidirectional")),
                )
            }
            if (contains(CAPABILITY_DIAGNOSTICS) && peer.contains(CAPABILITY_DIAGNOSTICS)) {
                val local = entries.getValue(CAPABILITY_DIAGNOSTICS)
                val remote = peer.entries.getValue(CAPABILITY_DIAGNOSTICS)
                result[CAPABILITY_DIAGNOSTICS] = Json.obj(
                    "redactedExport" to Json.Bool(local.bool("redactedExport", false) && remote.bool("redactedExport", false)),
                )
            }
            return NegotiatedCapabilities(result)
        }

        private fun Json.Obj.bool(key: String, default: Boolean): Boolean =
            (this[key] as? Json.Bool)?.value ?: default

        private fun Json.Obj.string(key: String, default: String): String =
            (this[key] as? Json.Str)?.value ?: default

        private fun Json.Obj.int(key: String, default: Int): Int =
            ((this[key] as? Json.Num)?.asLong())?.toInt() ?: default

        private fun minReply(left: String, right: String): String {
            val rank = mapOf("off" to 0, "beta" to 1, "on" to 2)
            val minRank = minOf(rank.getValue(left), rank.getValue(right))
            return rank.entries.first { it.value == minRank }.key
        }

        companion object {
            fun advertised(maxTransferChunkBytes: Int = DEFAULT_TRANSFER_MAX_CHUNK_BYTES): NegotiatedCapabilities {
                require(maxTransferChunkBytes in MIN_TRANSFER_MAX_CHUNK_BYTES..MAX_TRANSFER_MAX_CHUNK_BYTES) {
                    "transfer maxChunkBytes out of range"
                }
                return NegotiatedCapabilities(
                    linkedMapOf(
                        CAPABILITY_NOTIFICATIONS to Json.obj(
                            "reply" to Json.Str("beta"),
                            "dismiss" to Json.Bool(true),
                            "privacyPolicy" to Json.Bool(true),
                        ),
                        CAPABILITY_TRANSFER to Json.obj(
                            "resume" to Json.Bool(true),
                            "maxChunkBytes" to Json.Num(maxTransferChunkBytes.toString()),
                        ),
                        CAPABILITY_TEXT to Json.obj("direction" to Json.Str("bidirectional")),
                        CAPABILITY_DIAGNOSTICS to Json.obj("redactedExport" to Json.Bool(true)),
                    ),
                )
            }

            fun empty(): NegotiatedCapabilities = NegotiatedCapabilities(emptyMap())

            /**
             * Negotiate text.v1 direction from the canonical android-endpoint
             * frame. Each advertised `direction` is the advertiser's own
             * perspective (what it will do); v0 assumes exactly one android +
             * one macos peer, so the android device is the frame subject
             * (fallback: the first argument when neither is android). Returns
             * `bidirectional` / `send-only` / `receive-only` / `none`.
             */
            fun negotiateTextDirection(kindA: String, dirA: String, kindB: String, dirB: String): String {
                val aIsSubject = !(kindB == "android" && kindA != "android")
                val subjectDir = if (aIsSubject) dirA else dirB
                val otherDir = if (aIsSubject) dirB else dirA
                fun sends(d: String) = d == "bidirectional" || d == "send-only"
                fun receives(d: String) = d == "bidirectional" || d == "receive-only"
                val subjectSends = sends(subjectDir) && receives(otherDir)
                val subjectReceives = sends(otherDir) && receives(subjectDir)
                return when {
                    subjectSends && subjectReceives -> "bidirectional"
                    subjectSends -> "send-only"
                    subjectReceives -> "receive-only"
                    else -> "none"
                }
            }
        }
    }

    class HandshakeException(val code: String, message: String) : Exception(message)

    /** Client side: returns once the responder's hello arrives. */
    fun initiate(
        socket: SSLSocket,
        device: DeviceInfo,
        resumeToken: String?,
        previousSessionId: String?,
    ): Result {
        socket.soTimeout = HANDSHAKE_TIMEOUT_MS
        val hello = Envelope(
            messageId = Ulid.generate(),
            sessionId = null,
            type = Envelope.TYPE_HELLO,
            seq = 1,
            sentAtUnixMs = System.currentTimeMillis(),
            requiresAck = false,
            payload = helloPayload(device, resumeToken),
        )
        FrameIO.write(socket.outputStream, hello.encode())

        val reply = readHello(socket)
        socket.soTimeout = 0 // the session layer owns read timeouts from here
        val sessionId = reply.sessionId
            ?: throw HandshakeException("protocol/invalid-envelope", "responder hello carried no sessionId")
        return Result(
            sessionId = sessionId,
            resumeToken = (reply.payload["resumeToken"] as? Json.Str)?.value,
            resumed = sessionId == previousSessionId,
            peerDevice = deviceOf(reply.payload),
            negotiatedCapabilities = capabilitiesOf(reply.payload).intersect(NegotiatedCapabilities.advertised()),
        )
    }

    /** Server side: consumes the initiator's hello, assigns or resumes a
     *  session, replies, and returns what the session layer needs. */
    fun respond(
        socket: SSLSocket,
        device: DeviceInfo,
        tokenStore: ResumeTokenStore,
    ): Result {
        socket.soTimeout = HANDSHAKE_TIMEOUT_MS
        val peerFingerprint = TlsIdentity.spkiFingerprintOf(
            socket.session.peerCertificates.first() as X509Certificate
        )

        val hello = readHello(socket)
        val peerCaps = capabilitiesOf(hello.payload)
        val peerDevice = deviceOf(hello.payload)
        val advertised = NegotiatedCapabilities.advertised()
        val negotiatedCapabilities = advertised.intersect(peerCaps).resolveTextDirection(
            localKind = device.kind,
            localDir = advertised.textDirection() ?: "bidirectional",
            peerKind = peerDevice?.kind,
            peerDir = peerCaps.textDirection(),
        )
        val presented = (hello.payload["resumeToken"] as? Json.Str)?.value
        val resumedSessionId = presented?.let { tokenStore.redeem(it, peerFingerprint) }
        val sessionId = resumedSessionId ?: "s_${Ulid.generate()}"
        val nextToken = tokenStore.issue(sessionId, peerFingerprint)

        val reply = Envelope(
            messageId = Ulid.generate(),
            sessionId = sessionId,
            type = Envelope.TYPE_HELLO,
            seq = 1,
            sentAtUnixMs = System.currentTimeMillis(),
            requiresAck = false,
            payload = helloPayload(device, nextToken, negotiatedCapabilities),
        )
        FrameIO.write(socket.outputStream, reply.encode())
        socket.soTimeout = 0 // the session layer owns read timeouts from here

        return Result(
            sessionId = sessionId,
            resumeToken = nextToken,
            resumed = resumedSessionId != null,
            peerDevice = deviceOf(hello.payload),
            negotiatedCapabilities = negotiatedCapabilities,
        )
    }

    private fun readHello(socket: SSLSocket): Envelope {
        val frame = FrameIO.read(socket.inputStream)
            ?: throw HandshakeException("transport/connection-lost", "peer closed before hello")
        val envelope = try {
            Envelope.decode(frame)
        } catch (e: EnvelopeException) {
            throw HandshakeException("protocol/invalid-envelope", e.message ?: "bad hello")
        }
        if (envelope.type != Envelope.TYPE_HELLO) {
            throw HandshakeException("protocol/invalid-envelope", "expected hello, got ${envelope.type}")
        }
        validateHelloPayload(envelope.payload)
        return envelope
    }

    /** Structural check per capability.schema.json (strict top level). */
    private fun validateHelloPayload(payload: Json.Obj) {
        val unknown = payload.entries.keys - setOf("device", "resumeToken", "capabilities")
        if (unknown.isNotEmpty()) {
            throw HandshakeException("protocol/invalid-envelope", "unknown hello fields: $unknown")
        }
        val device = payload["device"] as? Json.Obj
            ?: throw HandshakeException("protocol/invalid-envelope", "hello.device missing")
        val unknownDevice = device.entries.keys - setOf("kind", "appVersion", "osVersion", "deviceName")
        if (unknownDevice.isNotEmpty()) {
            throw HandshakeException("protocol/invalid-envelope", "unknown hello.device fields: $unknownDevice")
        }
        if (device["kind"] !is Json.Str || device["appVersion"] !is Json.Str) {
            throw HandshakeException("protocol/invalid-envelope", "hello.device incomplete")
        }
        val kind = (device["kind"] as Json.Str).value
        if (kind !in DEVICE_KINDS) {
            throw HandshakeException("protocol/invalid-envelope", "hello.device.kind invalid")
        }
        val appVersion = (device["appVersion"] as Json.Str).value
        if (!APP_VERSION.matches(appVersion)) {
            throw HandshakeException("protocol/invalid-envelope", "hello.device.appVersion invalid")
        }
        when (payload["resumeToken"]) {
            is Json.Null, is Json.Str -> Unit
            else -> throw HandshakeException("protocol/invalid-envelope", "hello.resumeToken must be string or null")
        }
        if (payload["capabilities"] !is Json.Obj) {
            throw HandshakeException("protocol/invalid-envelope", "hello.capabilities missing")
        }
        capabilitiesOf(payload)
    }

    private fun helloPayload(
        device: DeviceInfo,
        resumeToken: String?,
        capabilities: NegotiatedCapabilities = NegotiatedCapabilities.advertised(),
    ): Json.Obj {
        val deviceEntries = linkedMapOf<String, Json>(
            "kind" to Json.Str(device.kind),
            "appVersion" to Json.Str(device.appVersion),
        )
        device.deviceName?.let { deviceEntries["deviceName"] = Json.Str(it) }
        return Json.obj(
            "device" to Json.Obj(deviceEntries),
            "resumeToken" to (resumeToken?.let { Json.Str(it) } ?: Json.Null),
            "capabilities" to capabilities.toJson(),
        )
    }

    private fun capabilitiesOf(payload: Json.Obj): NegotiatedCapabilities {
        val raw = payload["capabilities"] as? Json.Obj
            ?: throw HandshakeException("protocol/invalid-envelope", "hello.capabilities missing")
        val result = linkedMapOf<String, Json.Obj>()
        for ((name, value) in raw.entries) {
            if (!CAPABILITY_NAME.matches(name)) {
                throw HandshakeException("protocol/invalid-envelope", "bad capability '$name'")
            }
            val options = value as? Json.Obj
                ?: throw HandshakeException("protocol/invalid-envelope", "capability '$name' options must be object")
            validateCapabilityOptions(name, options)
            result[name] = options
        }
        return NegotiatedCapabilities(result)
    }

    private fun validateCapabilityOptions(name: String, options: Json.Obj) {
        when (name) {
            CAPABILITY_NOTIFICATIONS -> {
                options["reply"]?.let { raw ->
                    val reply = (raw as? Json.Str)?.value
                        ?: throw HandshakeException("protocol/invalid-envelope", "notifications.v1.reply must be string")
                    if (reply !in setOf("off", "beta", "on")) {
                        throw HandshakeException("protocol/invalid-envelope", "notifications.v1.reply invalid")
                    }
                }
                if (options["dismiss"] != null && options["dismiss"] !is Json.Bool) {
                    throw HandshakeException("protocol/invalid-envelope", "notifications.v1.dismiss must be boolean")
                }
                if (options["privacyPolicy"] != null && options["privacyPolicy"] !is Json.Bool) {
                    throw HandshakeException("protocol/invalid-envelope", "notifications.v1.privacyPolicy must be boolean")
                }
            }
            CAPABILITY_TRANSFER -> {
                if (options["resume"] != null && options["resume"] !is Json.Bool) {
                    throw HandshakeException("protocol/invalid-envelope", "transfer.v1.resume must be boolean")
                }
                options["maxChunkBytes"]?.let { raw ->
                    val max = (raw as? Json.Num)?.asLong()
                        ?: throw HandshakeException("protocol/invalid-envelope", "transfer.v1.maxChunkBytes must be integer")
                    if (max < MIN_TRANSFER_MAX_CHUNK_BYTES.toLong() || max > MAX_TRANSFER_MAX_CHUNK_BYTES.toLong()) {
                        throw HandshakeException("protocol/invalid-envelope", "transfer.v1.maxChunkBytes out of range")
                    }
                }
            }
            CAPABILITY_TEXT -> {
                options["direction"]?.let { raw ->
                    val direction = (raw as? Json.Str)?.value
                        ?: throw HandshakeException("protocol/invalid-envelope", "text.v1.direction must be string")
                    if (direction !in setOf("bidirectional", "send-only", "receive-only")) {
                        throw HandshakeException("protocol/invalid-envelope", "text.v1.direction invalid")
                    }
                }
            }
            CAPABILITY_DIAGNOSTICS -> {
                if (options["redactedExport"] != null && options["redactedExport"] !is Json.Bool) {
                    throw HandshakeException("protocol/invalid-envelope", "diagnostics.v1.redactedExport must be boolean")
                }
            }
        }
    }

    private fun deviceOf(payload: Json.Obj): DeviceInfo? {
        val device = payload["device"] as? Json.Obj ?: return null
        return DeviceInfo(
            kind = (device["kind"] as? Json.Str)?.value ?: return null,
            appVersion = (device["appVersion"] as? Json.Str)?.value ?: return null,
            deviceName = (device["deviceName"] as? Json.Str)?.value,
        )
    }
}
