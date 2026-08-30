package dev.hyphen.android.service

import java.util.Collections
import java.util.IdentityHashMap

/** Opaque ownership for the gap between a user start and sender registration. */
class TransferForegroundReservation internal constructor()

/**
 * Process-scoped reservations keep the data-sync FGS alive until each worker
 * has either registered protocol work or failed. Identity semantics prevent a
 * stale worker from releasing another transfer's reservation.
 */
class TransferForegroundOwnershipCoordinator {
    private val lock = Any()
    private val reservations = Collections.newSetFromMap(
        IdentityHashMap<TransferForegroundReservation, Boolean>(),
    )
    private var mutationEpoch = 0L

    fun reserve(): TransferForegroundReservation = synchronized(lock) {
        TransferForegroundReservation().also {
            reservations.add(it)
            mutationEpoch += 1
        }
    }

    fun release(reservation: TransferForegroundReservation): Boolean = synchronized(lock) {
        reservations.remove(reservation).also { changed ->
            if (changed) mutationEpoch += 1
        }
    }

    fun clear(): Boolean = synchronized(lock) {
        val changed = reservations.isNotEmpty()
        reservations.clear()
        if (changed) mutationEpoch += 1
        changed
    }

    fun hasReservations(): Boolean = synchronized(lock) { reservations.isNotEmpty() }

    /**
     * Seqlock-style combined check. No coordinator lock is held while probing
     * the sender, so sender progress may call back into reservation cleanup.
     * A concurrent reservation mutation retries instead of returning a stale
     * false; bounded churn conservatively keeps the foreground owner alive.
     */
    fun hasForegroundWork(senderHasWork: () -> Boolean): Boolean {
        repeat(MAX_STABLE_READ_ATTEMPTS) {
            val before = snapshot()
            if (before.hasReservations) return true
            if (senderHasWork()) return true
            val after = snapshot()
            if (after.hasReservations) return true
            if (after.mutationEpoch == before.mutationEpoch) return false
        }
        return true
    }

    private fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(reservations.isNotEmpty(), mutationEpoch)
    }

    private data class Snapshot(
        val hasReservations: Boolean,
        val mutationEpoch: Long,
    )

    private companion object {
        const val MAX_STABLE_READ_ATTEMPTS = 3
    }
}

object TransferForegroundOwnershipRuntime {
    val coordinator = TransferForegroundOwnershipCoordinator()
    val lifecycle = TransferForegroundLifecycleCoordinator()
}

/**
 * Serializes reservation creation with terminal transfer side effects. The
 * narrower reservation lock cannot cover sender cancellation or transport
 * pause callbacks, while this re-entrant process lock can safely encompass the
 * full lifecycle transaction.
 */
class TransferForegroundLifecycleCoordinator {
    private val lock = Any()

    fun <T> transaction(block: () -> T): T = synchronized(lock, block)
}
