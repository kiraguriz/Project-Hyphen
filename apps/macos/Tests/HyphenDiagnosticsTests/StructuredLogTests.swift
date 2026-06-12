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

    func testRedactedExporterIncludesCodesWithoutSensitiveCallbackDetail() throws {
        let store = LocalStructuredLogStore(clock: { 100 })
        let callbacks = ProtocolSession.Callbacks()
        let wrapped = DiagnosticProtocolSessionCallbacks.wrap(store: store, forwarding: callbacks)
        let sensitiveDetail = "notification body /Users/alice/private.txt https://secret.example"
        wrapped.onProtocolError("transport/frame-too-large", sensitiveDetail)

        let json = try RedactedDiagnosticsExporter(
            logs: store,
            appVersion: "0.0.1",
            osMajor: 15,
            osMinor: 5,
            osPatch: 0,
            clock: { 200 }
        ).previewJSON()

        XCTAssertFalse(json.contains("notification body"))
        XCTAssertFalse(json.contains("/Users/alice"))
        XCTAssertFalse(json.contains("https://secret.example"))

        let bundle = try XCTUnwrap(parseObject(json))
        XCTAssertEqual(bundle["schema"] as? String, "hyphen-diagnostics-v0")
        XCTAssertEqual(bundle["platform"] as? String, "macos")
        XCTAssertEqual(bundle["appVersion"] as? String, "0.0.1")
        XCTAssertEqual(bundle["eventCount"] as? Int, 1)
        XCTAssertEqual((bundle["os"] as? [String: Int])?["major"], 15)

        let events = try XCTUnwrap(bundle["events"] as? [[String: Any]])
        XCTAssertEqual(events.count, 1)
        XCTAssertEqual(events[0]["code"] as? String, "transport/frame-too-large")
        XCTAssertEqual(events[0]["category"] as? String, "transport")
        XCTAssertEqual(events[0]["level"] as? String, "error")
    }

    func testRedactedExporterDeleteClearsNextBundle() throws {
        let store = LocalStructuredLogStore(clock: { 100 })
        try store.recordFailure(code: "protocol/invalid-envelope", component: "protocol-session", operation: "decode")
        let exporter = RedactedDiagnosticsExporter(
            logs: store,
            appVersion: "0.0.1",
            osMajor: 15,
            osMinor: 5,
            osPatch: 0,
            clock: { 200 }
        )

        exporter.deleteLocalDiagnostics()

        let bundle = try XCTUnwrap(parseObject(try exporter.previewJSON()))
        XCTAssertEqual(bundle["eventCount"] as? Int, 0)
        XCTAssertEqual((bundle["events"] as? [[String: Any]])?.count, 0)
    }

    private func parseObject(_ json: String) throws -> [String: Any]? {
        let data = try XCTUnwrap(json.data(using: .utf8))
        return try JSONSerialization.jsonObject(with: data) as? [String: Any]
    }
}

private extension Array {
    func single(file: StaticString = #filePath, line: UInt = #line) -> Element {
        XCTAssertEqual(count, 1, file: file, line: line)
        return self[0]
    }
}
