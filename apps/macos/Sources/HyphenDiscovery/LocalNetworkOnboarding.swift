import Foundation

/// Onboarding copy for the macOS Local Network Privacy prompt
/// (HYP-M1-012, plan §8.3). Canonical text + design rules live in
/// docs/copy/macos-local-network-onboarding.md — keep them in sync.
public enum LocalNetworkCopy {
    public static let title = "Allow Hyphen to find devices on your network?"

    public static let body = """
    Hyphen looks for your paired phone on the Wi‑Fi network you're on. \
    macOS will ask for the “Local Network” permission next.

    Hyphen only looks for your own paired devices and connects to them \
    directly — nothing leaves your network. Hyphen never scans the \
    internet and never uploads anything about your network.

    You can also pair without this permission: scanning a QR code or \
    typing your phone's address always works.

    Changed your mind later? System Settings → Privacy & Security → \
    Local Network → enable Hyphen.
    """

    public static let continueTitle = "Continue"
    public static let notNowTitle = "Not now"
}

/// Persistence seam; `UserDefaults` conforms as-is.
public protocol OnboardingStateStore {
    func bool(forKey key: String) -> Bool
    func set(_ value: Bool, forKey key: String)
}

extension UserDefaults: OnboardingStateStore {}

/// Explain-first gate: the local-network-touching action (advertise or
/// browse) runs only after the user has seen the explanation once and
/// accepted. Declining persists nothing, so the explanation reappears on
/// the next attempt — the OS prompt itself is never reached uninvited.
public final class LocalNetworkOnboardingGate {

    public static let explainedKey = "dev.hyphen.lnp.explained"

    private let store: OnboardingStateStore

    public init(store: OnboardingStateStore) {
        self.store = store
    }

    /// - Parameters:
    ///   - action: the network-touching work, run at most once per call.
    ///   - present: shows the explanation UI and reports the decision.
    public func run(
        action: @escaping () -> Void,
        present: (@escaping (Bool) -> Void) -> Void
    ) {
        if store.bool(forKey: Self.explainedKey) {
            action()
            return
        }
        present { accepted in
            guard accepted else { return }
            self.store.set(true, forKey: Self.explainedKey)
            action()
        }
    }
}
