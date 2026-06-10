import Foundation
import HyphenCore
import Network

/// Browses for `_hyphen._tcp` peers. Results are endpoint hints only;
/// trust is established exclusively by pinning + SAS (protocol v0 §1).
public final class BonjourBrowser {

    private var browser: NWBrowser?
    private let queue = DispatchQueue(label: "dev.hyphen.browser")
    private let onChange: (Set<String>) -> Void

    /// `onChange` receives the full current set of service names.
    public init(onChange: @escaping (Set<String>) -> Void) {
        self.onChange = onChange
    }

    public func start(serviceType: String = HyphenCore.bonjourServiceType) {
        stop()
        let b = NWBrowser(for: .bonjour(type: serviceType, domain: nil), using: .tcp)
        b.browseResultsChangedHandler = { [weak self] results, _ in
            let names = Set(
                results.compactMap { result -> String? in
                    if case let .service(name, _, _, _) = result.endpoint {
                        return name
                    }
                    return nil
                }
            )
            self?.onChange(names)
        }
        browser = b
        b.start(queue: queue)
    }

    public func stop() {
        browser?.cancel()
        browser = nil
    }
}
