package dev.hyphen.android.pairing

import dev.hyphen.android.transport.Envelope
import dev.hyphen.android.transport.FrameIO
import dev.hyphen.android.transport.Json
import dev.hyphen.android.transport.Ulid
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import javax.net.ssl.SSLSocket

/**
 * Wire-level pairing state machine (protocol v0 §5.2, security review M-01):
 * `pair.request` → `pair.challenge` → `pair.response`, then bilateral
 * `pair.confirm` after local SAS UI. Trust is committed only when both
 * peers have sent `accepted: true`.
 */
object PairingWireProtocol {

    const val TYPE_REQUEST = "pair.request"
    const val TYPE_CHALLENGE = "pair.challenge"
    const val TYPE_RESPONSE = "pair.response"
    const val TYPE_CONFIRM = "pair.confirm"
    const val ERROR_SAS_REJECTED = "trust/sas-rejected"
    const val PAIRING_TIMEOUT_MS = 10_000

    class PairingException(val code: String, message: String) : Exception(message)

    data class WireDeviceInfo(
        val kind: String,
        val appVersion: String,
        val deviceName: String? = null,
    )

    data class InitiatorResult(
        val transcript: PairingTranscript,
        val peerDevice: WireDeviceInfo?,
        val confirm: PairingConfirmExchange,
    )

    data class ResponderResult(
        val transcript: PairingTranscript,
        val peerDevice: WireDeviceInfo,
        val confirm: PairingConfirmExchange,
    )

    fun runInitiator(
        socket: SSLSocket,
        nonce: ByteArray,
        macSpkiFingerprint: ByteArray,
        androidSpkiFingerprint: ByteArray,
        device: WireDeviceInfo,
        protocolVersion: String = PairingTranscript.PROTOCOL_VERSION,
    ): InitiatorResult {
        socket.soTimeout = PAIRING_TIMEOUT_MS
        val input = socket.inputStream
        val output = socket.outputStream
        var seq = 1L

        send(
            output,
            seq++,
            TYPE_REQUEST,
            Json.obj(
                "nonce" to Json.Str(b64Url(nonce)),
                "androidSpkiFp" to Json.Str(b64Url(androidSpkiFingerprint)),
                "device" to deviceJson(device),
            ),
        )

        val challenge = readTyped(input, TYPE_CHALLENGE)
        val challengeHash = requireHash(challenge.payload, "transcriptHash")

        val transcript = PairingTranscript.create(
            nonce = nonce,
            macSpkiFingerprint = macSpkiFingerprint,
            androidSpkiFingerprint = androidSpkiFingerprint,
            protocolVersion = protocolVersion,
        ) ?: throw PairingException("protocol/invalid-envelope", "transcript inputs invalid")
        if (!challengeHash.contentEquals(transcript.hash)) {
            throw PairingException(ERROR_SAS_REJECTED, "challenge transcriptHash mismatch")
        }

        send(
            output,
            seq++,
            TYPE_RESPONSE,
            Json.obj("transcriptHash" to Json.Str(b64(challengeHash))),
        )

        val peerDevice = deviceOf(challenge.payload["device"])
        return InitiatorResult(
            transcript = transcript,
            peerDevice = peerDevice,
            confirm = PairingConfirmExchange(input, output, seq),
        )
    }

    fun runResponder(
        socket: SSLSocket,
        nonce: ByteArray,
        macSpkiFingerprint: ByteArray,
        expectedAndroidFingerprint: ByteArray,
        protocolVersion: String = PairingTranscript.PROTOCOL_VERSION,
    ): ResponderResult {
        socket.soTimeout = PAIRING_TIMEOUT_MS
        val input = socket.inputStream
        val output = socket.outputStream
        var seq = 1L

        val request = readTyped(input, TYPE_REQUEST)
        val requestNonce = requireBytes(request.payload, "nonce", PairingTranscript.NONCE_LENGTH)
        if (!requestNonce.contentEquals(nonce)) {
            throw PairingException(ERROR_SAS_REJECTED, "pairing nonce mismatch")
        }
        val androidFp = requireBytes(request.payload, "androidSpkiFp", PairingTranscript.FINGERPRINT_LENGTH)
        if (!androidFp.contentEquals(expectedAndroidFingerprint)) {
            throw PairingException(ERROR_SAS_REJECTED, "android fingerprint mismatch")
        }
        val peerDevice = requireDevice(request.payload)

        val transcript = PairingTranscript.create(
            nonce = nonce,
            macSpkiFingerprint = macSpkiFingerprint,
            androidSpkiFingerprint = androidFp,
            protocolVersion = protocolVersion,
        ) ?: throw PairingException("protocol/invalid-envelope", "transcript inputs invalid")

        send(
            output,
            seq++,
            TYPE_CHALLENGE,
            Json.obj("transcriptHash" to Json.Str(b64(transcript.hash))),
        )

        val response = readTyped(input, TYPE_RESPONSE)
        val responseHash = requireHash(response.payload, "transcriptHash")
        if (!responseHash.contentEquals(transcript.hash)) {
            throw PairingException(ERROR_SAS_REJECTED, "response transcriptHash mismatch")
        }

        return ResponderResult(
            transcript = transcript,
            peerDevice = peerDevice,
            confirm = PairingConfirmExchange(input, output, seq),
        )
    }

    /** Bilateral `pair.confirm` exchange on an open pairing socket. */
    class PairingConfirmExchange internal constructor(
        private val input: InputStream,
        private val output: OutputStream,
        private var seq: Long,
    ) {
        private val lock = Any()
        private var localAccepted: Boolean? = null
        private var remoteAccepted: Boolean? = null
        private var dead = false

        val bothAccepted: Boolean
            get() = synchronized(lock) { localAccepted == true && remoteAccepted == true }

        val isDead: Boolean
            get() = synchronized(lock) {
                dead || localAccepted == false || remoteAccepted == false
            }

        fun submitLocalDecision(accepted: Boolean) {
            synchronized(lock) {
                if (dead || localAccepted != null) return
                localAccepted = accepted
                sendConfirm(accepted)
                if (!accepted) dead = true
            }
        }

        /** Blocks until a remote confirm arrives or the socket times out. */
        fun awaitRemoteConfirm(): Boolean? {
            synchronized(lock) {
                remoteAccepted?.let { return it }
            }
            val envelope = try {
                readEnvelope(input)
            } catch (_: Exception) {
                synchronized(lock) { dead = true }
                return null
            }
            if (envelope.type != TYPE_CONFIRM) {
                synchronized(lock) { dead = true }
                throw PairingException("protocol/unknown-type", "expected $TYPE_CONFIRM")
            }
            val accepted = envelope.payload.bool("accepted") ?: false
            synchronized(lock) {
                remoteAccepted = accepted
                if (!accepted) dead = true
            }
            return accepted
        }

        private fun sendConfirm(accepted: Boolean) {
            send(
                output,
                seq++,
                TYPE_CONFIRM,
                Json.obj("accepted" to Json.Bool(accepted)),
            )
        }
    }

    private fun send(output: OutputStream, seq: Long, type: String, payload: Json.Obj) {
        val envelope = Envelope(
            messageId = Ulid.generate(),
            sessionId = null,
            type = type,
            seq = seq,
            sentAtUnixMs = System.currentTimeMillis(),
            requiresAck = false,
            payload = payload,
        )
        FrameIO.write(output, envelope.encode())
    }

    private fun readTyped(input: InputStream, type: String): Envelope {
        val envelope = readEnvelope(input)
        if (envelope.type != type) {
            throw PairingException("protocol/unknown-type", "expected $type, got ${envelope.type}")
        }
        return envelope
    }

    private fun readEnvelope(input: InputStream): Envelope {
        val bytes = FrameIO.read(input)
            ?: throw PairingException("transport/connection-lost", "pairing stream closed")
        return Envelope.decode(bytes)
    }

    private fun deviceJson(device: WireDeviceInfo): Json.Obj {
        val entries = linkedMapOf<String, Json>(
            "kind" to Json.Str(device.kind),
            "appVersion" to Json.Str(device.appVersion),
        )
        device.deviceName?.let { entries["deviceName"] = Json.Str(it) }
        return Json.Obj(entries)
    }

    private fun deviceOf(value: Json?): WireDeviceInfo? {
        val obj = value as? Json.Obj ?: return null
        val kind = obj.string("kind") ?: return null
        val appVersion = obj.string("appVersion") ?: return null
        return WireDeviceInfo(kind, appVersion, obj.string("deviceName"))
    }

    private fun requireDevice(payload: Json.Obj): WireDeviceInfo =
        deviceOf(payload["device"]) ?: throw PairingException("protocol/invalid-envelope", "device missing")

    private fun requireHash(payload: Json.Obj, key: String): ByteArray =
        requireBytes(payload, key, PairingTranscript.FINGERPRINT_LENGTH)

    private fun requireBytes(payload: Json.Obj, key: String, length: Int): ByteArray {
        val encoded = payload.string(key)
            ?: throw PairingException("protocol/invalid-envelope", "$key missing")
        val bytes = try {
            Base64.getDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            try {
                Base64.getUrlDecoder().decode(encoded)
            } catch (_: IllegalArgumentException) {
                throw PairingException("protocol/invalid-envelope", "$key not base64")
            }
        }
        if (bytes.size != length) {
            throw PairingException("protocol/invalid-envelope", "$key wrong length")
        }
        return bytes
    }

    private fun Json.Obj.string(key: String): String? = (this[key] as? Json.Str)?.value

    private fun Json.Obj.bool(key: String): Boolean? = (this[key] as? Json.Bool)?.value

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun b64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
