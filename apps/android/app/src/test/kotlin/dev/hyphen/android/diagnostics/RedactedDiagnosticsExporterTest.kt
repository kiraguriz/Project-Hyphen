package dev.hyphen.android.diagnostics

import dev.hyphen.android.transport.Json
import dev.hyphen.android.transport.ProtocolSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactedDiagnosticsExporterTest {
    @Test
    fun `preview json includes failure codes without sensitive callback detail`() {
        val logs = LocalStructuredLogStore(clock = { 100L })
        val listener = DiagnosticProtocolSessionListener(
            logs = logs,
            delegate = object : ProtocolSession.Listener {},
        )
        val sensitiveDetail = "notification body /Users/alice/private.txt https://secret.example"
        listener.onProtocolError("transport/frame-too-large", sensitiveDetail)

        val preview = RedactedDiagnosticsExporter(
            logs = logs,
            appVersion = "0.0.1",
            sdkInt = 36,
            clock = { 200L },
        ).previewJson()

        assertFalse(preview.contains("notification body"))
        assertFalse(preview.contains("/Users/alice"))
        assertFalse(preview.contains("https://secret.example"))

        val bundle = Json.parse(preview) as Json.Obj
        assertEquals("hyphen-diagnostics-v0", bundle.string("schema"))
        assertEquals("android", bundle.string("platform"))
        assertEquals("0.0.1", bundle.string("appVersion"))
        assertEquals(36L, bundle.long("sdkInt"))
        assertEquals(1L, bundle.long("eventCount"))

        val events = bundle["events"] as Json.Arr
        val event = events.items.single() as Json.Obj
        assertEquals("transport/frame-too-large", event.string("code"))
        assertEquals("transport", event.string("category"))
        assertEquals("error", event.string("level"))
        assertEquals(100L, event.long("timestampUnixMs"))
        assertEquals("protocol-session", (event["attributes"] as Json.Obj).string("component"))
    }

    @Test
    fun `delete clears local diagnostics before next export`() {
        val logs = LocalStructuredLogStore(clock = { 100L })
        logs.recordFailure("protocol/invalid-envelope", "protocol-session", "decode")
        val exporter = RedactedDiagnosticsExporter(
            logs = logs,
            appVersion = "0.0.1",
            sdkInt = 36,
            clock = { 200L },
        )

        exporter.deleteLocalDiagnostics()

        val bundle = Json.parse(exporter.previewJson()) as Json.Obj
        assertEquals(0L, bundle.long("eventCount"))
        assertTrue((bundle["events"] as Json.Arr).items.isEmpty())
    }
}

private fun Json.Obj.string(key: String): String =
    (this[key] as Json.Str).value

private fun Json.Obj.long(key: String): Long =
    (this[key] as Json.Num).asLong()!!
