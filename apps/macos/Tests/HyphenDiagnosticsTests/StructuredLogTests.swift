import HyphenDiagnostics
import HyphenTransport
import XCTest

final class StructuredLogTests: XCTestCase {
    func testProtocolErrorsRecordCodesButOmitSensitiveDetail() {
        let store = LocalStructuredLogStore(clock: { 1234 })
        var forwardedDetail: String?
        var callbacks = ProtocolSession.Callbacks()
        callbacks.onProtocolError = { _, detail in
            forwardedDetail = detail
        }
        let wrapped = DiagnosticProtocolSessionCallbacks.wrap(store: store, forwarding: callbacks)
        let sensitiveDetail = "body=secret notification /Users/alice/private.txt https://secret.example"

        wrapped.onProtocolError("transport/frame-too-large", sensitiveDetail)

        let event = store.snapshot().single()
        XCTAssertEqual(event.timestampUnixMs, 1234)
        XCTAssertEqual(event.level, .error)
        XCTAssertEqual(event.category, "transport")
        XCTAssertEqual(event.code, "transport/frame-too-large")
        XCTAssertEqual(event.attributes, [
            "component": "protocol-session",
            "operation": "protocol-error",
        ])
        XCTAssertEqual(forwardedDetail, sensitiveDetail)

        let rendered = "\(event)"
        XCTAssertFalse(rendered.contains("secret notification"))
        XCTAssertFalse(rendered.contains("/Users/alice"))
        XCTAssertFalse(rendered.contains("https://secret.example"))
    }

    func testAckTimeoutRecordsTaxonomyFailureCode() {
        let store = LocalStructuredLogStore(clock: { 42 })
        var forwardedMessageId: String?
        var callbacks = ProtocolSession.Callbacks()
        callbacks.onAckTimeout = { forwardedMessageId = $0 }
        let wrapped = DiagnosticProtocolSessionCallbacks.wrap(store: store, forwarding: callbacks)

        wrapped.onAckTimeout("01JZ0000000000000000000000")

        let event = store.snapshot().single()
        XCTAssertEqual(event.category, "protocol")
        XCTAssertEqual(event.code, "protocol/ack-timeout")
        XCTAssertEqual(event.attributes["operation"], "ack-timeout")
        XCTAssertEqual(forwardedMessageId, "01JZ0000000000000000000000")
    }

    func testStoreKeepsBoundedLocalHistory() throws {
        let store = LocalStructuredLogStore(maxEntries: 2, clock: { 1 })

        try store.recordFailure(code: "protocol/invalid-envelope", component: "protocol-session", operation: "decode")
        try store.recordFailure(code: "transport/connection-lost", component: "protocol-session", operation: "read")
        try store.recordFailure(code: "plugin/checksum-mismatch", component: "transfer-receiver", operation: "verify")

        XCTAssertEqual(
            store.snapshot().map(\.code),
            ["transport/connection-lost", "plugin/checksum-mismatch"]
        )
    }

    func testUnsafeCodesAndMetadataAreRejected() {
        let store = LocalStructuredLogStore(clock: { 1 })

        XCTAssertThrowsError(
            try store.recordFailure(code: "diagnostics/private", component: "protocol-session", operation: "write")
        )
        XCTAssertThrowsError(
            try store.recordFailure(code: "transport/frame-too-large", component: "protocol session", operation: "write")
        )
        XCTAssertThrowsError(
            try store.recordFailure(
                code: "transport/frame-too-large",
                component: "protocol-session",
                operation: "/Users/alice/private.txt"
            )
        )
    }
}

private extension Array {
    func single(file: StaticString = #filePath, line: UInt = #line) -> Element {
        XCTAssertEqual(count, 1, file: file, line: line)
        return self[0]
    }
}
