import HyphenTransport

public enum NotificationCapabilityGate {
    public static func isNotificationEnvelope(type: String) -> Bool {
        switch type {
        case NotificationMirrorProtocol.typePosted,
             NotificationMirrorProtocol.typeUpdated,
             NotificationMirrorProtocol.typeRemoved,
             NotificationMirrorProtocol.typeDismissResult,
             NotificationMirrorProtocol.typeReplyResult:
            return true
        default:
            return false
        }
    }

    public static func allowsInboundEnvelope(
        type: String,
        capabilities: SessionHandshake.NegotiatedCapabilities?
    ) -> Bool {
        guard isNotificationEnvelope(type: type), canBind(capabilities) else {
            return false
        }
        return allowsInboundResult(type: type, capabilities: capabilities)
    }

    public static func canBind(_ capabilities: SessionHandshake.NegotiatedCapabilities?) -> Bool {
        capabilities?.contains(NotificationMirrorProtocol.capability) == true
    }

    public static func canPresentReplyActions(_ capabilities: SessionHandshake.NegotiatedCapabilities?) -> Bool {
        capabilities?.notificationReplyEnabled == true
    }

    public static func canSendDismiss(_ capabilities: SessionHandshake.NegotiatedCapabilities?) -> Bool {
        capabilities?.notificationDismissEnabled == true
    }

    public static func canSendReply(_ capabilities: SessionHandshake.NegotiatedCapabilities?) -> Bool {
        capabilities?.notificationReplyEnabled == true
    }

    public static func allowsInboundResult(type: String, capabilities: SessionHandshake.NegotiatedCapabilities?) -> Bool {
        switch type {
        case NotificationMirrorProtocol.typeDismissResult:
            return capabilities?.notificationDismissEnabled == true
        case NotificationMirrorProtocol.typeReplyResult:
            return capabilities?.notificationReplyEnabled == true
        default:
            return true
        }
    }
}
