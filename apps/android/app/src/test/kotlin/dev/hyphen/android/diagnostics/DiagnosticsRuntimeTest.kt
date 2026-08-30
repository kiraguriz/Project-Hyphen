package dev.hyphen.android.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsRuntimeTest {
    @Test
    fun `session failures are visible to activity preview and deletion`() {
        val sessionLogs = DiagnosticsRuntime.forSessionSupervisor()
        val activityLogs = DiagnosticsRuntime.forActivityActions()
        sessionLogs.clear()

        try {
            sessionLogs.recordFailure(
                code = "transport/connection-failed",
                component = "session-supervisor",
                operation = "connect",
            )
            val exporter = RedactedDiagnosticsExporter(
                logs = activityLogs,
                appVersion = "test",
                sdkInt = 33,
                clock = { 1L },
            )

            assertTrue(exporter.previewJson().contains("transport/connection-failed"))
            exporter.deleteLocalDiagnostics()
            assertEquals(emptyList<StructuredLogEvent>(), sessionLogs.snapshot())
        } finally {
            sessionLogs.clear()
        }
    }
}
