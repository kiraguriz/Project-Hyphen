import HyphenTransport
import XCTest
@testable import HyphenTransfer

private struct SentTransferEnvelope {
    let type: String
    let capability: String
    let requiresAck: Bool
    let payload: [String: Any]
}

private final class RecordingTransferOutbox: TransferOutbox {
    var envelopes: [SentTransferEnvelope] = []

    func send(
        type: String,
        capability: String,
        requiresAck: Bool,
        payload: [String: Any]
    ) -> String? {
        envelopes.append(
            SentTransferEnvelope(
                type: type,
                capability: capability,
                requiresAck: requiresAck,
                payload: payload
            )
        )
        return "01JZ0000000000000000000000"
    }
}

final class TransferMessagesTests: XCTestCase {
    func testSenderEmitsManifestFirstAndAckedChunks() throws {
        let outbox = RecordingTransferOutbox()

        let manifest = try TransferSender(outbox: outbox).sendBytes(
            filename: "notes.txt",
            mimeType: "text/plain",
            bytes: Data((0..<1500).map { UInt8($0 % 251) }),
            chunkSizeBytes: 1024,
            fileId: "f_macos_to_android"
        )

        XCTAssertEqual(manifest.chunkCount, 2)
        XCTAssertEqual(outbox.envelopes.count, 3)
        XCTAssertEqual(outbox.envelopes[0].type, TransferProtocol.typeManifest)
        XCTAssertEqual(outbox.envelopes[1].type, TransferProtocol.typeChunk)
        XCTAssertTrue(outbox.envelopes.allSatisfy {
            $0.capability == TransferProtocol.capability && $0.requiresAck
        })
    }

    func testReceiverReconstructsSmallFilesInBothDirections() throws {
        let macToAndroid = Data((0..<1500).map { UInt8($0 % 251) })
        let androidToMac = Data("hello back from Android".utf8)

        XCTAssertEqual(try sendThroughReceiver(macToAndroid, fileId: "f_macos_to_android").bytes, macToAndroid)
        XCTAssertEqual(try sendThroughReceiver(androidToMac, fileId: "f_android_to_macos").bytes, androidToMac)
    }

    func testReceiverRejectsCorruptedChunkHashes() throws {
        let good = try TransferChunk(fileId: "f_corrupt_test", chunkIndex: 0, data: Data("hello".utf8))
        var payload = good.payload
        payload["chunkSha256"] = String(repeating: "0", count: 64)

        XCTAssertThrowsError(try TransferChunk(payload: payload))
    }

    private func sendThroughReceiver(_ bytes: Data, fileId: String) throws -> TransferCompleted {
        let outbox = RecordingTransferOutbox()
        try TransferSender(outbox: outbox).sendBytes(
            filename: "sample.bin",
            mimeType: "application/octet-stream",
            bytes: bytes,
            chunkSizeBytes: 1024,
            fileId: fileId
        )
        let receiver = TransferReceiver()
        var completed: TransferCompleted?
        for (index, sent) in outbox.envelopes.enumerated() {
            completed = try receiver.handle(
                Envelope(
                    messageId: "01JZ000000000000000000000\(index)",
                    sessionId: "s_test1",
                    type: sent.type,
                    capability: sent.capability,
                    seq: Int64(index + 1),
                    sentAtUnixMs: 1_781_020_800_000,
                    requiresAck: sent.requiresAck,
                    payload: sent.payload
                )
            ) ?? completed
        }
        return try XCTUnwrap(completed)
    }
}
