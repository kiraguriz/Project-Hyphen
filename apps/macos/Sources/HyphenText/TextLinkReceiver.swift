import Foundation
import HyphenTransport

public enum TextLinkKind: String {
    case text
    case url
}

public enum TextLinkError: Error, Equatable {
    case unsupportedCapability(String?)
    case invalidPayload(String)
}

public struct TextLinkMessage: Equatable {
    public static let typeSend = "text.send"
    public static let capability = "text.v1"
    public static let maxValueLength = 8 * 1024

    public let kind: TextLinkKind
    public let value: String

    public init(kind: TextLinkKind, value: String) throws {
        guard !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw TextLinkError.invalidPayload("value must not be blank")
        }
        guard value.count <= Self.maxValueLength else {
            throw TextLinkError.invalidPayload("value too long")
        }
        if kind == .url {
            guard Self.isAllowedURL(value) else {
                throw TextLinkError.invalidPayload("url must use http or https")
            }
        }
        self.kind = kind
        self.value = value
    }

    public init(payload: [String: Any]) throws {
        guard let rawKind = payload["kind"] as? String,
              let kind = TextLinkKind(rawValue: rawKind)
        else {
            throw TextLinkError.invalidPayload("kind must be text or url")
        }
        guard let value = payload["value"] as? String else {
            throw TextLinkError.invalidPayload("value must be string")
        }
        try self.init(kind: kind, value: value)
    }

    public var payload: [String: Any] {
        [
            "kind": kind.rawValue,
            "value": value,
        ]
    }

    private static func isAllowedURL(_ value: String) -> Bool {
        guard let scheme = URLComponents(string: value)?.scheme?.lowercased() else {
            return false
        }
        return scheme == "http" || scheme == "https"
    }
}

public struct TextLinkConfirmationRequest: Equatable {
    public let messageId: String
    public let message: TextLinkMessage
}

public final class TextLinkReceiver {
    public private(set) var pending: [TextLinkConfirmationRequest] = []

    public init() {}

    public func handle(_ envelope: Envelope) throws -> TextLinkConfirmationRequest? {
        guard envelope.type == TextLinkMessage.typeSend else { return nil }
        guard envelope.capability == TextLinkMessage.capability else {
            throw TextLinkError.unsupportedCapability(envelope.capability)
        }
        let request = TextLinkConfirmationRequest(
            messageId: envelope.messageId,
            message: try TextLinkMessage(payload: envelope.payload)
        )
        pending.append(request)
        return request
    }
}

public protocol TextLinkOutbox {
    @discardableResult
    func send(
        type: String,
        capability: String,
        requiresAck: Bool,
        payload: [String: Any]
    ) -> String?
}

public final class ProtocolSessionTextLinkOutbox: TextLinkOutbox {
    private let session: ProtocolSession

    public init(session: ProtocolSession) {
        self.session = session
    }

    @discardableResult
    public func send(
        type: String,
        capability: String,
        requiresAck: Bool,
        payload: [String: Any]
    ) -> String? {
        session.send(
            type: type,
            payload: payload,
            requiresAck: requiresAck,
            capability: capability
        )
    }
}

public final class TextLinkSender {
    private let outbox: TextLinkOutbox

    public init(outbox: TextLinkOutbox) {
        self.outbox = outbox
    }

    @discardableResult
    public func send(_ message: TextLinkMessage) -> String? {
        outbox.send(
            type: TextLinkMessage.typeSend,
            capability: TextLinkMessage.capability,
            requiresAck: true,
            payload: message.payload
        )
    }
}
