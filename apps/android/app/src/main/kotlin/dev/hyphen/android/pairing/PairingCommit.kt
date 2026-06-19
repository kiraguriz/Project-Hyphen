package dev.hyphen.android.pairing

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
     * Sends local decision, waits for remote confirm, then writes trust
     * through [gate] only when both accepted.
     */
    fun finalize(
        gate: SasConfirmationGate,
        confirm: PairingWireProtocol.PairingConfirmExchange,
        localAccepted: Boolean,
    ): Outcome {
        if (!localAccepted) {
            gate.reject()
            confirm.submitLocalDecision(false)
            return Outcome.REJECTED
        }
        confirm.submitLocalDecision(true)
        val remote = confirm.awaitRemoteConfirm()
        when {
            remote == null -> {
                gate.reject()
                return Outcome.INCOMPLETE
            }
            !remote -> {
                gate.reject()
                return Outcome.REJECTED
            }
            confirm.bothAccepted -> {
                gate.confirm()
                return Outcome.TRUSTED
            }
            else -> {
                gate.reject()
                return Outcome.INCOMPLETE
            }
        }
    }
}
