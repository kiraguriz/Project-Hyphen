import HyphenTransport
import XCTest
@testable import HyphenText

final class TextLinkReceiverTests: XCTestCase {
    private final class RecordingTextLinkOutbox: TextLinkOutbox {
        var type: String?
        var capability: String?
        var requiresAck: Bool?
        var payload: [String: Any]?

        func send(
            type: String,
            capability: String,
            requiresAck: Bool,
            payload: [String: Any]
        ) -> String? {
            self.type = type
            self.capability = capability
            self.requiresAck = requiresAck
            self.payload = payload
            return "01JZ0000000000000000000001"
        }
    }

    private func envelope(
        type: String = TextLinkMessage.typeSend,
        capability: String? = TextLinkMessage.capability,
        payload: [String: Any]
    ) -> Envelope {
        Envelope(
            messageId: "01JZ0000000000000000000000",
            sessionId: "s_test1",
            type: type,
            capability: capability,
            seq: 2,
            sentAtUnixMs: 1_781_020_800_000,
            requiresAck: true,
            payload: payload
        )
    }

    func testValidTextEnvelopeCreatesPendingConfirmation() throws {
        let receiver = TextLinkReceiver()
        let request = try receiver.handle(
            envelope(payload: ["kind": "text", "value": "hello from Android"])
        )

        XCTAssertEqual(request?.message.kind, .text)
        XCTAssertEqual(request?.message.value, "hello from Android")
        XCTAssertEqual(receiver.pending, [request])
    }

    func testValidUrlEnvelopeCreatesPendingConfirmation() throws {
        let receiver = TextLinkReceiver()
        let request = try receiver.handle(
            envelope(payload: ["kind": "url", "value": "https://example.com/a"])
        )

        XCTAssertEqual(request?.message.kind, .url)
        XCTAssertEqual(request?.message.value, "https://example.com/a")
    }

    func testOtherEnvelopeTypesAreIgnored() throws {
        let receiver = TextLinkReceiver()
        let request = try receiver.handle(
            envelope(type: "heartbeat", capability: nil, payload: [:])
        )

        XCTAssertNil(request)
        XCTAssertTrue(receiver.pending.isEmpty)
    }

    func testWrongCapabilityIsRejected() {
        let receiver = TextLinkReceiver()

        XCTAssertThrowsError(
            try receiver.handle(
                envelope(capability: "notifications.v1", payload: ["kind": "text", "value": "hi"])
            )
        )
    }

    func testUnsafeUrlIsRejected() {
        XCTAssertThrowsError(
            try TextLinkMessage(kind: .url, value: "javascript:alert(1)")
        )
    }

    func testBlankValueIsRejected() {
        XCTAssertThrowsError(
            try TextLinkMessage(kind: .text, value: "  ")
        )
    }

    func testSenderUsesTextSendCapabilityAndRequiresAck() throws {
        let outbox = RecordingTextLinkOutbox()
        let id = try TextLinkSender(outbox: outbox).send(TextLinkMessage(kind: .text, value: "from Mac"))

        XCTAssertEqual(id, "01JZ0000000000000000000001")
        XCTAssertEqual(outbox.type, TextLinkMessage.typeSend)
        XCTAssertEqual(outbox.capability, TextLinkMessage.capability)
        XCTAssertEqual(outbox.requiresAck, true)
        XCTAssertEqual(outbox.payload?["kind"] as? String, "text")
        XCTAssertEqual(outbox.payload?["value"] as? String, "from Mac")
    }
}
