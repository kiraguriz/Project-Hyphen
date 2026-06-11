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
    )

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
            payload = helloPayload(device, nextToken),
        )
        FrameIO.write(socket.outputStream, reply.encode())
        socket.soTimeout = 0 // the session layer owns read timeouts from here

        return Result(
            sessionId = sessionId,
            resumeToken = nextToken,
            resumed = resumedSessionId != null,
            peerDevice = deviceOf(hello.payload),
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
        if (device["kind"] !is Json.Str || device["appVersion"] !is Json.Str) {
            throw HandshakeException("protocol/invalid-envelope", "hello.device incomplete")
        }
        when (payload["resumeToken"]) {
            is Json.Null, is Json.Str -> Unit
            else -> throw HandshakeException("protocol/invalid-envelope", "hello.resumeToken must be string or null")
        }
        if (payload["capabilities"] !is Json.Obj) {
            throw HandshakeException("protocol/invalid-envelope", "hello.capabilities missing")
        }
    }

    private fun helloPayload(device: DeviceInfo, resumeToken: String?): Json.Obj {
        val deviceEntries = linkedMapOf<String, Json>(
            "kind" to Json.Str(device.kind),
            "appVersion" to Json.Str(device.appVersion),
        )
        device.deviceName?.let { deviceEntries["deviceName"] = Json.Str(it) }
        return Json.obj(
            "device" to Json.Obj(deviceEntries),
            "resumeToken" to (resumeToken?.let { Json.Str(it) } ?: Json.Null),
            // Capability families arrive with the M3 feature work.
            "capabilities" to Json.Obj.EMPTY,
        )
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
