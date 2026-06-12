import Foundation
import HyphenTransport

public enum DiagnosticLogLevel: String, Equatable {
    case info
    case warning
    case error
}

public struct StructuredLogEvent: Equatable {
    public let timestampUnixMs: Int64
    public let level: DiagnosticLogLevel
    public let category: String
    public let code: String
    public let attributes: [String: String]
}

public enum DiagnosticLogError: Error, Equatable {
    case invalidCode
    case unknownCategory
    case unsafeMetadata
}

public final class LocalStructuredLogStore {
    private let maxEntries: Int
    private let clock: () -> Int64
    private let lock = NSLock()
    private var storage: [StructuredLogEvent] = []

    public init(
        maxEntries: Int = 500,
        clock: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }
    ) {
        precondition(maxEntries > 0, "maxEntries must be positive")
        self.maxEntries = maxEntries
        self.clock = clock
    }

    @discardableResult
    public func recordFailure(
        code: String,
        component: String,
        operation: String
    ) throws -> StructuredLogEvent {
        let safeCode = try validateCode(code)
        let event = StructuredLogEvent(
            timestampUnixMs: clock(),
            level: .error,
            category: String(safeCode.split(separator: "/", maxSplits: 1)[0]),
            code: safeCode,
            attributes: [
                "component": try validateToken(component),
                "operation": try validateToken(operation),
            ]
        )
        append(event)
        return event
    }

    public func snapshot() -> [StructuredLogEvent] {
        lock.lock()
        defer { lock.unlock() }
        return storage
    }

    public func clear() {
        lock.lock()
        defer { lock.unlock() }
        storage.removeAll()
    }

    private func append(_ event: StructuredLogEvent) {
        lock.lock()
        defer { lock.unlock() }
        while storage.count >= maxEntries {
            storage.removeFirst()
        }
        storage.append(event)
    }

    private func validateCode(_ code: String) throws -> String {
        guard Self.codePattern.firstMatch(in: code, range: NSRange(code.startIndex..., in: code)) != nil else {
            throw DiagnosticLogError.invalidCode
        }
        guard let category = code.split(separator: "/", maxSplits: 1).first,
              Self.allowedCategories.contains(String(category))
        else {
            throw DiagnosticLogError.unknownCategory
        }
        return code
    }

    private func validateToken(_ value: String) throws -> String {
        guard Self.tokenPattern.firstMatch(in: value, range: NSRange(value.startIndex..., in: value)) != nil else {
            throw DiagnosticLogError.unsafeMetadata
        }
        return value
    }

    private static let allowedCategories = Set(["protocol", "transport", "trust", "permission", "plugin"])
    private static let codePattern = try! NSRegularExpression(pattern: #"^[a-z]+/[a-z0-9-]{1,64}$"#)
    private static let tokenPattern = try! NSRegularExpression(pattern: #"^[a-z0-9_.-]{1,64}$"#)
}

public enum DiagnosticProtocolSessionCallbacks {
    public static func wrap(
        store: LocalStructuredLogStore,
        forwarding callbacks: ProtocolSession.Callbacks = ProtocolSession.Callbacks()
    ) -> ProtocolSession.Callbacks {
        var wrapped = ProtocolSession.Callbacks()
        wrapped.onEnvelope = callbacks.onEnvelope
        wrapped.onLiveness = callbacks.onLiveness
        wrapped.onClosed = callbacks.onClosed
        wrapped.onAckTimeout = { messageId in
            record(store: store, code: "protocol/ack-timeout", operation: "ack-timeout")
            callbacks.onAckTimeout(messageId)
        }
        wrapped.onProtocolError = { code, detail in
            record(store: store, code: code, operation: "protocol-error")
            callbacks.onProtocolError(code, detail)
        }
        return wrapped
    }

    private static func record(store: LocalStructuredLogStore, code: String, operation: String) {
        _ = try? store.recordFailure(
            code: code,
            component: "protocol-session",
            operation: operation
        )
    }
}

public final class RedactedDiagnosticsExporter {
    private let logs: LocalStructuredLogStore
    private let appVersion: String
    private let osMajor: Int
    private let osMinor: Int
    private let osPatch: Int
    private let clock: () -> Int64

    public init(
        logs: LocalStructuredLogStore,
        appVersion: String,
        osMajor: Int,
        osMinor: Int,
        osPatch: Int,
        clock: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }
    ) {
        self.logs = logs
        self.appVersion = appVersion
        self.osMajor = osMajor
        self.osMinor = osMinor
        self.osPatch = osPatch
        self.clock = clock
    }

    public func previewJSON() throws -> String {
        try encodeBundle()
    }

    public func exportText() throws -> String {
        try previewJSON()
    }

    public func deleteLocalDiagnostics() {
        logs.clear()
    }

    private func encodeBundle() throws -> String {
        let events = logs.snapshot()
        let bundle: [String: Any] = [
            "schema": "hyphen-diagnostics-v0",
            "generatedAtUnixMs": clock(),
            "platform": "macos",
            "appVersion": appVersion,
            "os": [
                "major": osMajor,
                "minor": osMinor,
                "patch": osPatch,
            ],
            "eventCount": events.count,
            "events": events.map(eventDictionary),
        ]
        let data = try JSONSerialization.data(
            withJSONObject: bundle,
            options: [.prettyPrinted, .sortedKeys]
        )
        return String(decoding: data, as: UTF8.self)
    }

    private func eventDictionary(_ event: StructuredLogEvent) -> [String: Any] {
        [
            "timestampUnixMs": event.timestampUnixMs,
            "level": event.level.rawValue,
            "category": event.category,
            "code": event.code,
            "attributes": event.attributes,
        ]
    }
}
