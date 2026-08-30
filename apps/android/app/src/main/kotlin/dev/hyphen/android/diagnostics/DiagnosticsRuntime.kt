package dev.hyphen.android.diagnostics

/**
 * Process-scoped diagnostics composition root.
 *
 * Transport/session failures can outlive an Activity recreation, while preview,
 * export, and deletion are initiated by the current Activity. Keeping one store
 * here guarantees those consumers observe and mutate the same bounded log.
 */
object DiagnosticsRuntime {
    private val sharedLogs = LocalStructuredLogStore()

    /** Log sink used by the process-long transport/session owner. */
    fun forSessionSupervisor(): LocalStructuredLogStore = sharedLogs

    /** Log source managed by Activity preview/export/delete actions. */
    fun forActivityActions(): LocalStructuredLogStore = sharedLogs
}
