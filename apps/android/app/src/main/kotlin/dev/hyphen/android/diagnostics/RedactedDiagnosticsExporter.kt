package dev.hyphen.android.diagnostics

import dev.hyphen.android.transport.Json

class RedactedDiagnosticsExporter(
    private val logs: LocalStructuredLogStore,
    private val appVersion: String,
    private val sdkInt: Int,
    private val includeTraceIds: Boolean = false,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun previewJson(): String = buildBundle().encode()

    fun exportText(): String = previewJson()

    fun deleteLocalDiagnostics() {
        logs.clear()
    }

    private fun buildBundle(): Json.Obj {
        val events = logs.snapshot()
        return Json.obj(
            "schema" to Json.Str("hyphen-diagnostics-v0"),
            "generatedAtUnixMs" to Json.Num(clock().toString()),
            "platform" to Json.Str("android"),
            "appVersion" to Json.Str(appVersion),
            "sdkInt" to Json.Num(sdkInt.toString()),
            "eventCount" to Json.Num(events.size.toString()),
            "events" to Json.Arr(events.map(::eventToJson)),
        )
    }

    private fun eventToJson(event: StructuredLogEvent): Json.Obj {
        val entries = linkedMapOf(
            "timestampUnixMs" to Json.Num(event.timestampUnixMs.toString()),
            "level" to Json.Str(event.level.name.lowercase()),
            "category" to Json.Str(event.category),
            "code" to Json.Str(event.code),
            "attributes" to Json.Obj(event.attributes.toSortedMap().mapValues { Json.Str(it.value) }),
        )
        if (includeTraceIds && event.traceId != null) {
            entries["traceId"] = Json.Str(event.traceId)
        }
        return Json.Obj(entries)
    }
}
