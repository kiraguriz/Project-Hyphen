import HyphenTransport
import XCTest
@testable import HyphenTransfer

private struct SentTransferEnvelope {
    let type: String
    let capability: String
    let requiresAck: Bool
    var payload: [String: Any]
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

    func testReceiverRejectsCompletedFilesWhoseSHA256DoesNotMatchTheManifest() throws {
        let bytes = Data((0..<1500).map { UInt8($0 % 251) })
        let outbox = RecordingTransferOutbox()
        try TransferSender(outbox: outbox).sendBytes(
            filename: "sample.bin",
            mimeType: "application/octet-stream",
            bytes: bytes,
            chunkSizeBytes: 1024,
            fileId: "f_bad_file_hash"
        )
        let receiver = TransferReceiver()
        var first = outbox.envelopes[0]
        first.payload["sha256"] = String(repeating: "0", count: 64)

        XCTAssertThrowsError(
            try ([first] + outbox.envelopes.dropFirst()).enumerated().forEach { index, sent in
                _ = try receiver.handle(toEnvelope(sent, index: index))
            }
        )
    }

    func testInterruptedTransferResumesFromReceiverCheckpoint() throws {
        let bytes = Data((0..<2500).map { UInt8($0 % 251) })
        let firstOutbox = RecordingTransferOutbox()
        let manifest = try TransferSender(outbox: firstOutbox).sendBytes(
            filename: "sample.bin",
            mimeType: "application/octet-stream",
            bytes: bytes,
            chunkSizeBytes: 1024,
            fileId: "f_resume_test"
        )
        let receiver = TransferReceiver()

        for (index, sent) in firstOutbox.envelopes.prefix(2).enumerated() {
            _ = try receiver.handle(toEnvelope(sent, index: index))
        }
        let checkpoint = try XCTUnwrap(receiver.checkpoint(fileId: manifest.fileId))
        XCTAssertEqual(checkpoint.nextChunkIndex, 1)

        let resumeOutbox = RecordingTransferOutbox()
        try TransferSender(outbox: resumeOutbox).sendRemainingBytes(
            manifest: manifest,
            bytes: bytes,
            fromChunkIndex: checkpoint.nextChunkIndex
        )
        var completed: TransferCompleted?
        for (index, sent) in resumeOutbox.envelopes.enumerated() {
            completed = try receiver.handle(toEnvelope(sent, index: index + 2)) ?? completed
        }

        XCTAssertEqual(completed?.bytes, bytes)
    }

    func testResumeRequestAndInfoUseProtocolWireNames() throws {
        let outbox = RecordingTransferOutbox()
        let sender = TransferSender(outbox: outbox)

        try sender.requestResume(fileId: "f_resume_test")
        sender.sendResumeInfo(try TransferResumeInfo(fileId: "f_resume_test", nextChunkIndex: 2))

        XCTAssertEqual(outbox.envelopes[0].type, TransferProtocol.typeResumeRequest)
        XCTAssertEqual(outbox.envelopes[0].payload["fileId"] as? String, "f_resume_test")
        XCTAssertEqual(outbox.envelopes[1].type, TransferProtocol.typeResumeInfo)
        XCTAssertEqual(outbox.envelopes[1].payload["nextChunkIndex"] as? Int, 2)
    }

    func testSenderAndReceiverReportTransferProgress() throws {
        let bytes = Data((0..<2500).map { UInt8($0 % 251) })
        let outbox = RecordingTransferOutbox()
        var sendProgress: [TransferProgress] = []
        let manifest = try TransferSender(outbox: outbox) { sendProgress.append($0) }.sendBytes(
            filename: "sample.bin",
            mimeType: "application/octet-stream",
            bytes: bytes,
            chunkSizeBytes: 1024,
            fileId: "f_progress_test"
        )
        var receiveProgress: [TransferProgress] = []
        let receiver = TransferReceiver { receiveProgress.append($0) }

        for (index, sent) in outbox.envelopes.enumerated() {
            _ = try receiver.handle(toEnvelope(sent, index: index))
        }

        XCTAssertEqual(sendProgress.map(\.completedChunks), [0, 1, 2, 3])
        XCTAssertEqual(receiveProgress.map(\.completedChunks), [0, 1, 2, 3])
        XCTAssertEqual(sendProgress.last?.completedBytes, Int64(bytes.count))
        XCTAssertEqual(receiveProgress.last?.totalBytes, manifest.sizeBytes)
        XCTAssertEqual(sendProgress.last?.isComplete, true)
    }

    func testCancelMessageUsesProtocolWireNameAndDiscardClearsReceiverCheckpoint() throws {
        let bytes = Data((0..<2500).map { UInt8($0 % 251) })
        let firstOutbox = RecordingTransferOutbox()
        let manifest = try TransferSender(outbox: firstOutbox).sendBytes(
            filename: "sample.bin",
            mimeType: "application/octet-stream",
            bytes: bytes,
            chunkSizeBytes: 1024,
            fileId: "f_cancel_test"
        )
        let receiver = TransferReceiver()
        for (index, sent) in firstOutbox.envelopes.prefix(2).enumerated() {
            _ = try receiver.handle(toEnvelope(sent, index: index))
        }
        XCTAssertEqual(receiver.checkpoint(fileId: manifest.fileId)?.nextChunkIndex, 1)

        let cancelOutbox = RecordingTransferOutbox()
        try TransferSender(outbox: cancelOutbox).sendCancel(TransferCancel(fileId: manifest.fileId, discard: true))
        let cancel = try XCTUnwrap(cancelOutbox.envelopes.single)
        XCTAssertEqual(cancel.type, TransferProtocol.typeCancel)
        XCTAssertEqual(cancel.payload["discard"] as? Bool, true)

        _ = try receiver.handle(toEnvelope(cancel, index: 2))

        XCTAssertNil(receiver.checkpoint(fileId: manifest.fileId))
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

    private func toEnvelope(_ sent: SentTransferEnvelope, index: Int) -> Envelope {
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
    }
}

private extension Array {
    var single: Element? {
        count == 1 ? self[0] : nil
    }
}
