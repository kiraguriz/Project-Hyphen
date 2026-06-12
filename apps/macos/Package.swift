// swift-tools-version:5.9
import PackageDescription

// SwiftPM-based skeleton (HYP-M1-010): no .xcodeproj, fully auditable.
// Module layout follows plan §8.2; HyphenCore grows toward protocol/
// session/trust responsibilities in M2.
let package = Package(
    name: "Hyphen",
    platforms: [
        // Official support is macOS 15.1+; 14 is the best-effort floor
        // (plan §8.1) and the minimum SwiftPM platform we compile for.
        .macOS(.v14)
    ],
    targets: [
        .executableTarget(
            name: "HyphenApp",
            dependencies: [
                "HyphenCore",
                "HyphenDiagnostics",
                "HyphenDiscovery",
                "HyphenNotifications",
                "HyphenPower",
                "HyphenText",
                "HyphenTransport",
                "HyphenTransfer",
            ]
        ),
        .target(name: "HyphenCore"),
        .target(
            name: "HyphenDiagnostics",
            dependencies: ["HyphenTransport"]
        ),
        .target(
            name: "HyphenDiscovery",
            dependencies: ["HyphenCore"]
        ),
        .target(name: "HyphenPower"),
        .target(
            name: "HyphenTransport",
            dependencies: ["HyphenCore", "HyphenPower"]
        ),
        .target(
            name: "HyphenText",
            dependencies: ["HyphenTransport"]
        ),
        .target(
            name: "HyphenNotifications",
            dependencies: ["HyphenTransport"]
        ),
        .target(
            name: "HyphenTransfer",
            dependencies: ["HyphenTransport"]
        ),
        .testTarget(
            name: "HyphenCoreTests",
            dependencies: ["HyphenCore"]
        ),
        .testTarget(
            name: "HyphenDiagnosticsTests",
            dependencies: ["HyphenDiagnostics"]
        ),
        .testTarget(
            name: "HyphenTransportTests",
            dependencies: ["HyphenTransport"]
        ),
        .testTarget(
            name: "HyphenTextTests",
            dependencies: ["HyphenText"]
        ),
        .testTarget(
            name: "HyphenNotificationsTests",
            dependencies: ["HyphenNotifications"]
        ),
        .testTarget(
            name: "HyphenTransferTests",
            dependencies: ["HyphenTransfer"]
        ),
        .testTarget(
            name: "HyphenDiscoveryTests",
            dependencies: ["HyphenDiscovery"]
        ),
        .testTarget(
            name: "HyphenPowerTests",
            dependencies: ["HyphenPower"]
        ),
    ]
)
