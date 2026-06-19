package dev.hyphen.android.pairing

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compatibility with the macOS generator (HYP-M2-009/M2-010): URIs below
 * carry exactly the encodings `PairingQRPayload.uriString` emits —
 * unpadded base64url binary fields, RFC 3986 unreserved-only
 * percent-encoded device names. The fp bytes deliberately start 0xFB 0xFF
 * so the url alphabet ('-'/'_') is actually exercised.
 */
class QrScanCompatTest {

    private val fingerprint = byteArrayOf(0xFB.toByte(), 0xFF.toByte()) + ByteArray(30) { 0x42 }
    private val nonce = ByteArray(16) { it.toByte() }

    private fun b64url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun macUri(deviceName: String? = null): String {
        var uri = "hyphen://pair?v=0&ep=192.168.1.20:48273&fp=${b64url(fingerprint)}&n=${b64url(nonce)}"
        if (deviceName != null) uri += "&dn=$deviceName"
        return uri
    }

    private fun parseOk(uri: String): ParsedEndpoint.QrPayload {
        val result = EndpointParser.parseQr(uri)
        assertTrue("expected Ok, got $result", result is ParseResult.Ok)
        return (result as ParseResult.Ok).endpoint as ParsedEndpoint.QrPayload
    }

    @Test
    fun `accepts a macOS-generated payload and decodes the binary fields`() {
        // Sanity: the fixture really is the unpadded url alphabet.
        assertTrue(b64url(fingerprint).startsWith("-_"))
        assertEquals(43, b64url(fingerprint).length)
        assertEquals(22, b64url(nonce).length)

        val payload = parseOk(macUri())
        assertEquals(0, payload.version)
        assertEquals("192.168.1.20", payload.host)
        assertEquals(48273, payload.port)
        assertArrayEquals(fingerprint, payload.decodedFingerprint())
        assertArrayEquals(nonce, payload.decodedNonce())
        assertEquals(null, payload.deviceName)
    }

    @Test
    fun `decodes a percent-encoded device name the way the Mac encodes it`() {
        // "Haitian's Mac & 测试 +1" through the macOS unreserved-only set.
        val encoded = "Haitian%27s%20Mac%20%26%20%E6%B5%8B%E8%AF%95%20%2B1"
        val payload = parseOk(macUri(deviceName = encoded))
        assertEquals("Haitian's Mac & 测试 +1", payload.deviceName)
    }

    @Test
    fun `parsed payload feeds the SAS transcript directly`() {
        val payload = parseOk(macUri())
        val androidFp = ByteArray(32) { 0x07 }
        val transcript = PairingTranscript.create(
            nonce = payload.decodedNonce(),
            macSpkiFingerprint = payload.decodedFingerprint()!!,
            androidSpkiFingerprint = androidFp,
            protocolVersion = "hyphen/0.3",
        )
        assertEquals(6, transcript!!.sas.length)
    }

    @Test
    fun `invalid payloads stay safely rejected`() {
        val bad = mapOf(
            "scheme" to macUri().replaceFirst("hyphen://pair?", "https://pair?"),
            "missing n" to "hyphen://pair?v=0&ep=h:1&fp=${b64url(fingerprint)}",
            "wrong version" to macUri().replaceFirst("v=0", "v=9"),
            "fp not base64url" to macUri().replaceFirst("fp=", "fp=!!!"),
            "fp wrong length" to "hyphen://pair?v=0&ep=h:1&fp=${b64url(ByteArray(31))}&n=${b64url(nonce)}",
            "nonce wrong length" to "hyphen://pair?v=0&ep=h:1&fp=${b64url(fingerprint)}&n=${b64url(ByteArray(15))}",
            "duplicate key" to macUri() + "&v=0",
            "port out of range" to macUri().replaceFirst(":48273", ":99999"),
        )
        for ((label, uri) in bad) {
            val result = EndpointParser.parseQr(uri)
            assertTrue("$label must be rejected, got $result", result is ParseResult.Rejected)
        }
        val manualSas = EndpointParser.parseQr("hyphen://pair?v=0&ep=h:1&n=${b64url(nonce)}")
        assertTrue(manualSas is ParseResult.Ok)
    }
}
