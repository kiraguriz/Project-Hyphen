import Foundation
import HyphenCore
import Network

/// Advertises `_hyphen._tcp` on the LAN (HYP-M1-011, plan §8.2).
/// Incoming connections are refused at the discovery listener — pairing and
/// steady-session acceptors are separate product paths (discovery ≠ trust).
public final class BonjourAdvertiser {

    public enum State: Equatable {
        case idle
        case starting
        case advertising(port: UInt16)
        case failed(String)
        case stopped
    }

    private var listener: NWListener?
    private let queue = DispatchQueue(label: "dev.hyphen.advertiser")
    private let onState: (State) -> Void

    public private(set) var state: State = .idle

    public init(onState: @escaping (State) -> Void) {
        self.onState = onState
    }

    public func start(
        deviceName: String,
        serviceType: String = HyphenCore.bonjourServiceType
    ) {
        stop()
        let l: NWListener
        do {
            l = try NWListener(using: .tcp)
        } catch {
            update(.failed(String(describing: error)))
            return
        }
        l.service = NWListener.Service(name: deviceName, type: serviceType)
        l.newConnectionHandler = { connection in
            // Discovery advertisement only; pairing/steady listeners own TLS accept.
            connection.cancel()
        }
        l.stateUpdateHandler = { [weak self] newState in
            guard let self else { return }
            switch newState {
            case .ready:
                self.update(.advertising(port: self.listener?.port?.rawValue ?? 0))
            case .failed(let error):
                self.update(.failed(String(describing: error)))
            case .cancelled:
                self.update(.stopped)
            default:
                break
            }
        }
        update(.starting)
        listener = l
        l.start(queue: queue)
    }

    public func stop() {
        listener?.cancel()
        listener = nil
    }

    private func update(_ newState: State) {
        state = newState
        onState(newState)
    }
}
