/// Shared constants for the Hyphen macOS app (grows into protocol/session/
/// trust responsibilities in M2; see plan §8.2).
public enum HyphenCore {
    public static let version = "0.0.1"

    /// Bonjour service type advertised/browsed on the LAN. Must match the
    /// Android side (`DiscoveryManager.SERVICE_TYPE`) and protocol v0.
    public static let bonjourServiceType = "_hyphen._tcp"
}
