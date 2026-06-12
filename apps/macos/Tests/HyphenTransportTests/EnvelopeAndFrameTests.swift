import XCTest
@testable import HyphenTransport

final class EnvelopeAndFrameTests: XCTestCase {

    private func envelope(
        sessionId: String? = "s_test1",
        type: String = "heartbeat"
    ) -> Envelope {
        Envelope(
            messageId: Ulid.generate(),
            sessionId: sessionId,
            type: type,
            seq: 1,
            sentAtUnixMs: 1_781_107_200_000,
            requiresAck: false
        )
    }

    func testUlidShapeUniquenessAndTimeOrdering() {
        let a = Ulid.generate()
        let b = Ulid.generate()
        XCTAssertTrue(Ulid.isValid(a))
        XCTAssertTrue(Ulid.isValid(b))
        XCTAssertNotEqual(a, b)
        XCTAssertLessThan(Ulid.generate(nowMs: 1_000), Ulid.generate(nowMs: 2_000))
    }

    func testRoundtripPreservesEveryField() throws {
        let trace = try ProtocolTrace.local(spanId: "01JZ0000000000000000000000")
        let original = Envelope(
            messageId: Ulid.generate(),
            sessionId: "s_abc-123",
            type: "notification.updated",
            capability: "notifications.v1",
            seq: 42,
            ackOf: Ulid.generate(),
            sentAtUnixMs: 1_781_107_200_000,
            requiresAck: true,
            payload: ["key": "0|com.app|7|tag|10101", "n": 7],
            trace: trace.wireObject
        )
        let decoded = try Envelope.decode(original.encode())
        XCTAssertEqual(decoded.protocolId, original.protocolId)
        XCTAssertEqual(decoded.messageId, original.messageId)
        XCTAssertEqual(decoded.sessionId, original.sessionId)
        XCTAssertEqual(decoded.type, original.type)
        XCTAssertEqual(decoded.capability, original.capability)
        XCTAssertEqual(decoded.seq, original.seq)
        XCTAssertEqual(decoded.ackOf, original.ackOf)
        XCTAssertEqual(decoded.sentAtUnixMs, original.sentAtUnixMs)
        XCTAssertEqual(decoded.requiresAck, original.requiresAck)
        XCTAssertEqual(decoded.payload as NSDictionary, original.payload as NSDictionary)
        XCTAssertEqual(decoded.trace! as NSDictionary, original.trace! as NSDictionary)
    }

    func testNullSessionIdSurvivesRoundtrip() throws {
        let hello = envelope(sessionId: nil, type: "hello")
        XCTAssertNil(try Envelope.decode(hello.encode()).sessionId)
    }

    func testStructuralViolationsAreRejected() throws {
        let json = String(data: try envelope().encode(), encoding: .utf8)!

        let broken = [
            "unknown field": json.replacingOccurrences(of: "{", with: "{\"smuggled\":1,", options: [], range: json.range(of: "{")),
            "bad seq": json.replacingOccurrences(of: "\"seq\":1", with: "\"seq\":0"),
            "float seq": json.replacingOccurrences(of: "\"seq\":1", with: "\"seq\":1.5"),
            "boolean seq": json.replacingOccurrences(of: "\"seq\":1", with: "\"seq\":true"),
            "numeric requiresAck": json.replacingOccurrences(of: "\"requiresAck\":false", with: "\"requiresAck\":0"),
            "bad sessionId": json.replacingOccurrences(of: "\"sessionId\":\"s_test1\"", with: "\"sessionId\":\"nope\""),
            "bad type": json.replacingOccurrences(of: "\"type\":\"heartbeat\"", with: "\"type\":\"Heart_Beat\""),
            "not JSON": "hello",
            "missing fields": "{\"protocol\":\"hyphen/0.3\"}",
        ]
        for (label, text) in broken {
            XCTAssertThrowsError(try Envelope.decode(Data(text.utf8)), label)
        }
    }

    func testTraceIsValidatedStrictly() throws {
        let json = String(data: try envelope().encode(), encoding: .utf8)!
            .replacingOccurrences(of: "\"payload\":{}", with: "\"payload\":{},\"trace\":{\"localOnly\":true,\"extra\":1}")
        XCTAssertThrowsError(try Envelope.decode(Data(json.utf8)))

        let localOnlyFalse = String(data: try envelope().encode(), encoding: .utf8)!
            .replacingOccurrences(
                of: "\"payload\":{}",
                with: "\"payload\":{},\"trace\":{\"localOnly\":false,\"spanId\":\"01JZ0000000000000000000000\"}"
            )
        XCTAssertThrowsError(try Envelope.decode(Data(localOnlyFalse.utf8)))

        let badSpan = String(data: try envelope().encode(), encoding: .utf8)!
            .replacingOccurrences(
                of: "\"payload\":{}",
                with: "\"payload\":{},\"trace\":{\"localOnly\":true,\"spanId\":\"not-a-ulid\"}"
            )
        XCTAssertThrowsError(try Envelope.decode(Data(badSpan.utf8)))
    }

    func testFramesRoundtripThroughDribbledFeeds() throws {
        let payloads = [Data("first".utf8), Data("second".utf8)]
        var wire = Data()
        for payload in payloads { wire.append(try FrameCodec.encode(payload)) }

        // Feed one byte at a time: partial headers and bodies must buffer.
        let reader = FrameReader()
        var frames: [Data] = []
        for byte in wire {
            frames.append(contentsOf: try reader.feed(Data([byte])))
        }
        XCTAssertEqual(frames, payloads)
    }

    func testFeedUntilFirstPreservesCoalescedFramesForSessionReplay() throws {
        let hello = Data("hello".utf8)
        let postHello = Data("post-hello".utf8)
        let trailing = try FrameCodec.encode(Data("partial".utf8))
        var wire = Data()
        wire.append(try FrameCodec.encode(hello))
        wire.append(try FrameCodec.encode(postHello))
        wire.append(trailing.prefix(5))

        let first = try XCTUnwrap(FrameReader().feedUntilFirst(wire))
        XCTAssertEqual(first.frame, hello)

        let replayReader = FrameReader()
        XCTAssertEqual(try replayReader.feed(first.leftover), [postHello])
        XCTAssertEqual(try replayReader.feed(Data(trailing.dropFirst(5))), [Data("partial".utf8)])
    }

    func testOversizedFrameIsRejected() {
        let fiveMiB = UInt32(5 * 1024 * 1024)
        let header = Data([
            UInt8(truncatingIfNeeded: fiveMiB >> 24),
            UInt8(truncatingIfNeeded: fiveMiB >> 16),
            UInt8(truncatingIfNeeded: fiveMiB >> 8),
            UInt8(truncatingIfNeeded: fiveMiB),
        ])
        XCTAssertThrowsError(try FrameReader().feed(header)) { error in
            XCTAssertEqual(error as? FrameError, .tooLarge(declared: UInt64(fiveMiB)))
        }
        XCTAssertThrowsError(try FrameCodec.encode(Data(count: Int(fiveMiB))))
    }

    func testMonitorDegradesOnSilenceAndRecovers() {
        var transitions: [HeartbeatMonitor.State] = []
        let monitor = HeartbeatMonitor(intervalMs: 10_000, startedAtMs: 0) { transitions.append($0) }
        monitor.tick(nowMs: 20_000)
        XCTAssertEqual(monitor.state, .healthy, "exactly 2 intervals is not yet missed")
        monitor.tick(nowMs: 20_001)
        XCTAssertEqual(monitor.state, .degraded)
        monitor.envelopeReceived(nowMs: 25_000)
        XCTAssertEqual(monitor.state, .healthy)
        XCTAssertEqual(transitions, [.degraded, .healthy])
    }

    func testAckTrackerTimesOutOnceAndResolvesOnAck() {
        var timeouts: [String] = []
        let tracker = AckTracker(timeoutMs: 10_000) { timeouts.append($0) }
        tracker.registerSent(messageId: "A", nowMs: 0)
        tracker.registerSent(messageId: "B", nowMs: 0)
        XCTAssertTrue(tracker.ackReceived("A"))
        tracker.tick(nowMs: 10_001)
        tracker.tick(nowMs: 20_000)
        XCTAssertEqual(timeouts, ["B"])
        XCTAssertEqual(tracker.pendingCount, 0)
    }
}
