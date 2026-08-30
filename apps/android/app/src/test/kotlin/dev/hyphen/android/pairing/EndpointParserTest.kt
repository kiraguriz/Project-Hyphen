package dev.hyphen.android.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointParserTest {

    // 32 zero bytes / 16 zero bytes in base64url (no padding)
    private val fp32 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    private val n16 = "AAAAAAAAAAAAAAAAAAAAAA"

    private fun qr(
        v: String = "0",
        ep: String = "192.168.1.20:48273",
        fp: String = fp32,
        n: String = n16,
        extra: String = "",
    ) = "hyphen://pair?v=$v&ep=$ep&fp=$fp&n=$n$extra"

    private fun rejected(result: ParseResult): RejectReason =
        (result as ParseResult.Rejected).reason

    @Test
    fun `valid qr payload parses all fields`() {
        val result = EndpointParser.parseQr(qr(extra = "&dn=Mac%20Studio"))
        val payload = (result as ParseResult.Ok).endpoint as ParsedEndpoint.QrPayload
        assertEquals(0, payload.version)
        assertEquals("192.168.1.20", payload.host)
        assertEquals(48273, payload.port)
        assertEquals(fp32, payload.spkiFingerprintB64)
        assertEquals(n16, payload.nonceB64)
        assertEquals("Mac Studio", payload.deviceName)
    }

    @Test
    fun `device name is optional`() {
        val payload =
            (EndpointParser.parseQr(qr()) as ParseResult.Ok).endpoint as ParsedEndpoint.QrPayload
        assertEquals(null, payload.deviceName)
    }

    @Test
    fun `unknown scheme is rejected`() {
        assertEquals(
            RejectReason.UNKNOWN_SCHEME,
            rejected(EndpointParser.parseQr("https://pair?v=0&ep=1.2.3.4:1&fp=$fp32&n=$n16")),
        )
    }

    @Test
    fun `each missing required field is rejected`() {
        val full = mapOf("v" to "0", "ep" to "1.2.3.4:1", "n" to n16)
        for (dropped in full.keys) {
            val query = full.filterKeys { it != dropped }
                .entries.joinToString("&") { "${it.key}=${it.value}" }
            assertEquals(
                "dropping $dropped must reject",
                RejectReason.MISSING_FIELD,
                rejected(EndpointParser.parseQr("hyphen://pair?$query")),
            )
        }
    }

    @Test
    fun `qr without fingerprint parses for manual sas pairing`() {
        val query = "v=0&ep=192.168.1.20:48273&n=$n16"
        val payload =
            (EndpointParser.parseQr("hyphen://pair?$query") as ParseResult.Ok)
                .endpoint as ParsedEndpoint.QrPayload
        assertEquals(null, payload.spkiFingerprintB64)
        assertEquals(n16, payload.nonceB64)
    }

    @Test
    fun `unsupported version is rejected`() {
        assertEquals(RejectReason.UNSUPPORTED_VERSION, rejected(EndpointParser.parseQr(qr(v = "1"))))
    }

    @Test
    fun `malformed endpoint variants are rejected`() {
        listOf(
            "nohost",
            "1.2.3.4:",
            ":42",
            "1.2.3.4:0",
            "1.2.3.4:65536",
            "1.2.3.4:abc",
            "fe80::1:42",
        ).forEach { ep ->
            assertEquals(
                "ep=$ep must reject",
                RejectReason.MALFORMED_ENDPOINT,
                rejected(EndpointParser.parseQr(qr(ep = ep))),
            )
        }
    }

    @Test
    fun `wrong-length fingerprint or nonce is rejected`() {
        assertEquals(RejectReason.MALFORMED_FIELD, rejected(EndpointParser.parseQr(qr(fp = n16))))
        assertEquals(RejectReason.MALFORMED_FIELD, rejected(EndpointParser.parseQr(qr(n = fp32))))
        assertEquals(
            RejectReason.MALFORMED_FIELD,
            rejected(EndpointParser.parseQr(qr(fp = "!!notb64!!"))),
        )
        assertEquals(
            RejectReason.MALFORMED_FIELD,
            rejected(EndpointParser.parseQr(qr(fp = "$fp32="))),
        )
        assertEquals(
            RejectReason.MALFORMED_FIELD,
            rejected(EndpointParser.parseQr(qr(n = "$n16=="))),
        )
    }

    @Test
    fun `endpoint host and display name reject injection-shaped values`() {
        listOf("mac%2Flocal:48273", "mac#fragment:48273", "mác.local:48273")
            .forEach { endpoint ->
                assertEquals(
                    RejectReason.MALFORMED_ENDPOINT,
                    rejected(EndpointParser.parseQr(qr(ep = endpoint))),
                )
            }
        listOf("", "%0Asecond-line", "%E2%80%AEtxt.exe", "x".repeat(81))
            .forEach { name ->
                assertEquals(
                    RejectReason.MALFORMED_FIELD,
                    rejected(EndpointParser.parseQr(qr(extra = "&dn=$name"))),
                )
            }
    }

    @Test
    fun `public and globally resolved endpoint shapes are rejected before dialing`() {
        listOf(
            "93.184.216.34:443",
            "example.com:443",
            "8.8.8.8:53",
        ).forEach { endpoint ->
            assertEquals(
                RejectReason.NON_LOCAL_ENDPOINT,
                rejected(EndpointParser.parseQr(qr(ep = endpoint))),
            )
            assertEquals(
                RejectReason.NON_LOCAL_ENDPOINT,
                rejected(EndpointParser.parseManual(endpoint)),
            )
        }

        listOf(
            "10.0.0.7:48273",
            "172.16.0.7:48273",
            "192.168.1.7:48273",
            "169.254.2.3:48273",
            "macbook-pro.local:48273",
        ).forEach { endpoint ->
            assertTrue(EndpointParser.parseManual(endpoint) is ParseResult.Ok)
        }
    }

    @Test
    fun `device name uses strict utf8 scalar decoding and canonical percent encoding`() {
        listOf("%ED%A0%80", "%C3%28", "\uD800")
            .forEach { name ->
                assertEquals(
                    RejectReason.MALFORMED_FIELD,
                    rejected(EndpointParser.parseQr(qr(extra = "&dn=$name"))),
                )
            }

        val payload =
            (EndpointParser.parseQr(qr(extra = "&dn=Mac%2BStudio")) as ParseResult.Ok)
                .endpoint as ParsedEndpoint.QrPayload
        assertEquals("Mac+Studio", payload.deviceName)
        assertEquals(
            RejectReason.MALFORMED_FIELD,
            rejected(EndpointParser.parseQr(qr(extra = "&dn=Mac+Studio"))),
        )
    }

    @Test
    fun `duplicate unknown and oversized fields are rejected`() {
        assertEquals(
            RejectReason.MALFORMED_FIELD,
            rejected(EndpointParser.parseQr(qr(extra = "&v=0"))),
        )
        assertEquals(
            RejectReason.TOO_LARGE,
            rejected(EndpointParser.parseQr(qr(extra = "&dn=" + "x".repeat(2000)))),
        )
        assertEquals(
            RejectReason.MALFORMED_FIELD,
            rejected(EndpointParser.parseQr(qr(extra = "&redirect=https%3A%2F%2Fexample.com"))),
        )
    }

    @Test
    fun `garbage and empty input fail safe`() {
        assertEquals(RejectReason.EMPTY, rejected(EndpointParser.parseQr("   ")))
        assertEquals(
            RejectReason.UNKNOWN_SCHEME,
            rejected(EndpointParser.parseQr("\u0000\ufffd garbage")),
        )
        assertTrue(EndpointParser.parseQr("hyphen://pair?===&&&") is ParseResult.Rejected)
    }

    @Test
    fun `empty query segments and raw fragments are rejected`() {
        listOf(
            qr().replace("?", "?&"),
            qr().replace("&ep=", "&&ep="),
            qr() + "&",
            qr(extra = "&dn=Mac#fragment"),
            qr() + "#fragment",
        ).forEach { payload ->
            assertEquals(
                "payload=$payload must reject",
                RejectReason.MALFORMED_FIELD,
                rejected(EndpointParser.parseQr(payload)),
            )
        }
    }

    @Test
    fun `manual host port parses`() {
        val ok = EndpointParser.parseManual(" 10.0.0.7:48273 ")
        assertEquals(ParsedEndpoint.Manual("10.0.0.7", 48273), (ok as ParseResult.Ok).endpoint)
    }

    @Test
    fun `manual input rejects malformed values`() {
        assertEquals(RejectReason.EMPTY, rejected(EndpointParser.parseManual("")))
        listOf(
            "10.0.0.7",
            "10.0.0.7:99999",
            "host with space:1",
            "10.0.0.7:1x",
        )
            .forEach { input ->
                assertEquals(
                    "input=$input must reject",
                    RejectReason.MALFORMED_ENDPOINT,
                    rejected(EndpointParser.parseManual(input)),
                )
            }
        listOf(
            "10.0.0.7:48273?x=$n16",
            "10.0.0.7:48273?n=$n16&n=$n16",
        ).forEach { input ->
            assertEquals(
                RejectReason.MALFORMED_FIELD,
                rejected(EndpointParser.parseManual(input)),
            )
        }
    }
}
