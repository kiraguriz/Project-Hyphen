package dev.hyphen.android.service

import android.content.Context
import java.util.UUID

object CompanionConnectionPreferences {
    private const val PREFS = "dev.hyphen.android.companion"
    private const val KEY_KEEP_CONNECTED = "keep_connected"
    private const val KEY_TRANSFER_RETRY_REQUIRED = "transfer_retry_required"
    private const val KEY_TRANSFER_ACTIVE = "transfer_active"
    private val transferStateGate = TransferStateRevisionGate()
    private val transferProcessEpoch = UUID.randomUUID().toString()

    fun keepConnected(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_KEEP_CONNECTED, false)

    fun setKeepConnected(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_KEEP_CONNECTED, enabled)
            .apply()
    }

    fun transferRetryRequired(context: Context): Boolean =
        transferStateGate.read {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_TRANSFER_RETRY_REQUIRED, false)
        }

    fun setTransferRetryRequired(context: Context, required: Boolean) {
        transferStateGate.mutate {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_TRANSFER_RETRY_REQUIRED, required)
                .apply()
        }
    }

    fun transferActive(context: Context): Boolean =
        transferStateGate.read {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_TRANSFER_ACTIVE, false)
        }

    fun setTransferActive(context: Context, active: Boolean) {
        transferStateGate.mutate {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_TRANSFER_ACTIVE, active)
                .apply()
        }
    }

    fun setTransferState(
        context: Context,
        retryRequired: Boolean,
        active: Boolean,
    ): Long = transferStateGate.mutate {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_TRANSFER_RETRY_REQUIRED, retryRequired)
            .putBoolean(KEY_TRANSFER_ACTIVE, active)
            .apply()
    }

    internal fun transferStateSnapshot(context: Context): TransferStateSnapshot =
        transferStateGate.snapshot(processEpoch = transferProcessEpoch) {
            val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            TransferStateValues(
                retryRequired = preferences.getBoolean(KEY_TRANSFER_RETRY_REQUIRED, false),
                active = preferences.getBoolean(KEY_TRANSFER_ACTIVE, false),
            )
        }

    internal fun applyTransferStopIfCurrent(
        context: Context,
        expectedRevision: Long,
        retryRequired: Boolean,
        terminalEffects: () -> Unit,
    ): Boolean = transferStateGate.mutateIfCurrent(expectedRevision) {
        // Keep callbacks triggered by terminal effects inside the same state
        // generation, then persist the owning stop outcome last.
        terminalEffects()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_TRANSFER_RETRY_REQUIRED, retryRequired)
            .putBoolean(KEY_TRANSFER_ACTIVE, false)
            .apply()
    }
}

internal data class TransferStateValues(
    val retryRequired: Boolean,
    val active: Boolean,
)

internal data class TransferStateSnapshot(
    val processEpoch: String,
    val revision: Long,
    val retryRequired: Boolean,
    val active: Boolean,
)

internal data class TransferStateControlToken(
    val processEpoch: String,
    val revision: Long,
)

internal fun TransferStateSnapshot.controlToken(): TransferStateControlToken =
    TransferStateControlToken(processEpoch, revision)

internal fun TransferStateControlToken.matches(snapshot: TransferStateSnapshot): Boolean =
    processEpoch == snapshot.processEpoch && revision == snapshot.revision

/**
 * Process-local revision gate for queued Service controls. Controls also carry
 * an unlogged per-process epoch, so a command queued by a dead process cannot
 * collide with the new process's revision counter.
 */
internal class TransferStateRevisionGate {
    private val lock = Any()
    private var revision = 0L

    fun <T> read(block: () -> T): T = synchronized(lock, block)

    fun snapshot(
        processEpoch: String = "test-process",
        readValues: () -> TransferStateValues,
    ): TransferStateSnapshot =
        synchronized(lock) {
            val values = readValues()
            TransferStateSnapshot(processEpoch, revision, values.retryRequired, values.active)
        }

    fun mutate(write: () -> Unit): Long = synchronized(lock) {
        revision += 1
        write()
        revision
    }

    fun mutateIfCurrent(expectedRevision: Long, write: () -> Unit): Boolean =
        synchronized(lock) {
            if (revision != expectedRevision) return@synchronized false
            revision += 1
            write()
            true
        }
}
