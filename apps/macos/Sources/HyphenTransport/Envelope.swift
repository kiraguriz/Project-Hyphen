import Foundation
import HyphenCore

/// Maps to `protocol/invalid-envelope` in the error taxonomy.
public struct EnvelopeError: Error, CustomStringConvertible {
    public let detail: String
    public var description: String { detail }
    init(_ detail: String) { self.detail = detail }
}

public struct ProtocolTrace: Equatable {
    public let spanId: String

    private init(spanId: String) {
        self.spanId = spanId
    }

    public static func local(spanId: String = Ulid.generate()) throws -> ProtocolTrace {
        guard isValidSpanId(spanId) else { throw EnvelopeError("trace.spanId must be a ULID") }
        return ProtocolTrace(spanId: spanId)
    }

    public static func isValidSpanId(_ spanId: String) -> Bool {
        Ulid.isValid(spanId)
    }

    public var wireObject: [String: Any] {
        [
            "localOnly": true,
            "spanId": spanId,
        ]
    }
}

/// Protocol v0 envelope (HYP-M2-012, protocol doc §3) — model + strict
/// codec matching `protocol/schema/envelope.schema.json` exactly: unknown
/// fields rejected, patterns enforced, `sessionId` nullable. JSON via
/// Foundation's JSONSerialization (payload stays an arbitrary object).
public struct Envelope {

    public static let typeHeartbeat = "heartbeat"
    public static let typeAck = "ack"
    public static let typeHello = "hello"
    public static let typeError = "error"

    public let protocolId: String
    public let messageId: String
    public let sessionId: String?
    public let type: String
    public let capability: String?
    public let seq: Int64
    public let ackOf: String?
    public let sentAtUnixMs: Int64
    public let requiresAck: Bool
    public let payload: [String: Any]
    public let trace: [String: Any]?

    public init(
        protocolId: String = HyphenCore.protocolVersion,
        messageId: String,
        sessionId: String?,
        type: String,
        capability: String? = nil,
        seq: Int64,
        ackOf: String? = nil,
        sentAtUnixMs: Int64,
        requiresAck: Bool,
        payload: [String: Any] = [:],
        trace: [String: Any]? = nil
    ) {
        self.protocolId = protocolId
        self.messageId = messageId
        self.sessionId = sessionId
        self.type = type
        self.capability = capability
        self.seq = seq
        self.ackOf = ackOf
        self.sentAtUnixMs = sentAtUnixMs
        self.requiresAck = requiresAck
        self.payload = payload
        self.trace = trace
    }

    public func encode() throws -> Data {
        var dict: [String: Any] = [
            "protocol": protocolId,
            "messageId": messageId,
            "sessionId": sessionId ?? NSNull(),
            "type": type,
            "seq": seq,
            "sentAtUnixMs": sentAtUnixMs,
            "requiresAck": requiresAck,
            "payload": payload,
        ]
        if let capability { dict["capability"] = capability }
        if let ackOf { dict["ackOf"] = ackOf }
        if let trace { dict["trace"] = trace }
        return try JSONSerialization.data(withJSONObject: dict, options: [.sortedKeys])
    }

    private static let knownFields: Set<String> = [
        "protocol", "messageId", "sessionId", "type", "capability",
        "seq", "ackOf", "sentAtUnixMs", "requiresAck", "payload", "trace",
    ]

    public static func decode(_ data: Data) throws -> Envelope {
        let root: Any
        do {
            root = try JSONSerialization.jsonObject(with: data, options: [])
        } catch {
            throw EnvelopeError("not JSON: \(error.localizedDescription)")
        }
        guard let obj = root as? [String: Any] else {
            throw EnvelopeError("envelope must be an object")
        }

        let unknown = Set(obj.keys).subtracting(knownFields)
        guard unknown.isEmpty else { throw EnvelopeError("unknown fields: \(unknown.sorted())") }

        let protocolId = try string(obj, "protocol", pattern: "^hyphen/0\\.[0-9]+$")
        let messageId = try string(obj, "messageId")
        guard Ulid.isValid(messageId) else { throw EnvelopeError("messageId is not a ULID") }

        guard let rawSession = obj["sessionId"] else { throw EnvelopeError("sessionId missing") }
        let sessionId: String?
        if rawSession is NSNull {
            sessionId = nil
        } else if let value = rawSession as? String,
                  value.range(of: "^s_[A-Za-z0-9_-]{2,64}$", options: .regularExpression) != nil {
            sessionId = value
        } else {
            throw EnvelopeError("bad sessionId")
        }

        let type = try string(obj, "type", pattern: "^[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)*$")

        var capability: String?
        if obj["capability"] != nil {
            capability = try string(obj, "capability", pattern: "^[a-z][a-z0-9]*\\.v[0-9]+$")
        }

        let seq = try integer(obj, "seq")
        guard seq >= 1 else { throw EnvelopeError("seq must be >= 1") }

        var ackOf: String?
        if let rawAckOf = obj["ackOf"], !(rawAckOf is NSNull) {
            guard let value = rawAckOf as? String, Ulid.isValid(value) else {
                throw EnvelopeError("ackOf is not a ULID")
            }
            ackOf = value
        }

        let sentAtUnixMs = try integer(obj, "sentAtUnixMs")
        guard sentAtUnixMs >= 0 else { throw EnvelopeError("sentAtUnixMs must be >= 0") }

        guard let rawAck = obj["requiresAck"], isBoolean(rawAck), let requiresAck = rawAck as? Bool else {
            throw EnvelopeError("requiresAck missing or not boolean")
        }

        guard let payload = obj["payload"] as? [String: Any] else {
            throw EnvelopeError("payload missing or not an object")
        }

        var trace: [String: Any]?
        if let rawTrace = obj["trace"] {
            guard let traceObj = rawTrace as? [String: Any] else {
                throw EnvelopeError("trace must be an object")
            }
            let extras = Set(traceObj.keys).subtracting(["localOnly", "spanId"])
            guard extras.isEmpty else { throw EnvelopeError("unknown trace fields: \(extras.sorted())") }
            guard let rawLocalOnly = traceObj["localOnly"], isBoolean(rawLocalOnly), let localOnly = rawLocalOnly as? Bool else {
                throw EnvelopeError("trace.localOnly missing or not boolean")
            }
            guard localOnly else { throw EnvelopeError("trace.localOnly must be true") }
            if let spanId = traceObj["spanId"] {
                guard let spanId = spanId as? String else { throw EnvelopeError("trace.spanId must be a string") }
                guard Ulid.isValid(spanId) else { throw EnvelopeError("trace.spanId must be a ULID") }
            }
            trace = traceObj
        }

        return Envelope(
            protocolId: protocolId,
            messageId: messageId,
            sessionId: sessionId,
            type: type,
            capability: capability,
            seq: seq,
            ackOf: ackOf,
            sentAtUnixMs: sentAtUnixMs,
            requiresAck: requiresAck,
            payload: payload,
            trace: trace
        )
    }

    private static func string(_ obj: [String: Any], _ field: String, pattern: String? = nil) throws -> String {
        guard let value = obj[field] as? String else {
            throw EnvelopeError("\(field) missing or not a string")
        }
        if let pattern, value.range(of: pattern, options: .regularExpression) == nil {
            throw EnvelopeError("bad \(field) '\(value)'")
        }
        return value
    }

    /// Booleans bridge to NSNumber too — require an integer-typed number.
    private static func integer(_ obj: [String: Any], _ field: String) throws -> Int64 {
        guard let number = obj[field] as? NSNumber, !isBoolean(number) else {
            throw EnvelopeError("\(field) missing or not a number")
        }
        if String(cString: number.objCType) == "d" {
            throw EnvelopeError("\(field) is not an integer")
        }
        return number.int64Value
    }

    private static func isBoolean(_ value: Any) -> Bool {
        CFGetTypeID(value as CFTypeRef) == CFBooleanGetTypeID()
    }
}
