import Foundation
import HyphenCore

/// Commits trust only after local SAS acceptance AND a remote
/// `pair.confirm { accepted: true }` (security review M-01).
public enum PairingCommit {

    public enum Outcome: Equatable {
        case trusted
        case rejected
        case incomplete
    }

    public static func finalize(
        gate: SasConfirmationGate,
        confirm: PairingWireProtocol.PairingConfirmExchange,
        localAccepted: Bool,
        completion: @escaping (Outcome) -> Void
    ) {
        if !localAccepted {
            gate.reject()
            confirm.submitLocalDecision(false)
            completion(.rejected)
            return
        }
        confirm.submitLocalDecision(true)
        confirm.awaitRemoteConfirm { remote in
            switch remote {
            case nil:
                gate.reject()
                completion(.incomplete)
            case false:
                gate.reject()
                completion(.rejected)
            case true:
                do {
                    if confirm.bothAccepted {
                        _ = try gate.confirm()
                        completion(.trusted)
                    } else {
                        gate.reject()
                        completion(.incomplete)
                    }
                } catch {
                    gate.reject()
                    completion(.incomplete)
                }
            }
        }
    }
}
