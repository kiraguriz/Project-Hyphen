package dev.hyphen.android.pairing

import dev.hyphen.android.trust.TrustedPeer

/**
 * Commits trust only after local SAS acceptance AND a remote
 * `pair.confirm { accepted: true }` (security review M-01).
 */
object PairingCommit {

    enum class Outcome {
        TRUSTED,
        REJECTED,
        INCOMPLETE,
    }

    /**
     * Sends the local decision and waits for the remote decision without
     * touching local trust. Callers with lifecycle ownership can therefore
     * commit trust and durable routing in one token-owned critical section.
     */
    fun exchange(
        confirm: PairingWireProtocol.PairingConfirmExchange,
        localAccepted: Boolean,
    ): Outcome {
        if (!localAccepted) {
            confirm.submitLocalDecision(false)
            return Outcome.REJECTED
        }
        confirm.submitLocalDecision(true)
        val remote = confirm.awaitRemoteConfirm()
        return when {
            remote == null -> Outcome.INCOMPLETE
            !remote -> Outcome.REJECTED
            confirm.bothAccepted -> Outcome.TRUSTED
            else -> Outcome.INCOMPLETE
        }
    }

    /** Applies an already-negotiated outcome to the local SAS trust gate. */
    fun commitTrust(
        gate: SasConfirmationGate,
        outcome: Outcome,
        persist: ((TrustedPeer) -> Unit)? = null,
    ): Outcome =
        when (outcome) {
            Outcome.TRUSTED ->
                if (
                    (persist?.let { gate.confirm(persist = it) } ?: gate.confirm()) ==
                    SasConfirmationGate.Outcome.TRUSTED
                ) {
                    Outcome.TRUSTED
                } else {
                    Outcome.REJECTED
                }
            Outcome.REJECTED -> {
                gate.reject()
                Outcome.REJECTED
            }
            Outcome.INCOMPLETE -> {
                gate.reject()
                Outcome.INCOMPLETE
            }
        }

    /** Convenience path for owners that need no external lifecycle transaction. */
    fun finalize(
        gate: SasConfirmationGate,
        confirm: PairingWireProtocol.PairingConfirmExchange,
        localAccepted: Boolean,
    ): Outcome = commitTrust(gate, exchange(confirm, localAccepted))
}
