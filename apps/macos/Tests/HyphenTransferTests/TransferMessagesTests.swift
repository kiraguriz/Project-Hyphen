import Foundation
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
        let id = "sent-\(envelopes.count)"
        envelopes.append(
            SentTransferEnvelope(
                type: type,
                capability: capability,
                requiresAck: requiresAck,
                payload: payload
            )
        )
        return id
    }
}

final class TransferMessagesTests: XCTestCase {
    private var tempRoot: URL!
    private var sessions: [ProtocolSession] = []
    private let sessionsLock = NSLock()
    private var listener: TLSEndpointListener?

    override func setUpWithError() throws {
        tempRoot = FileManager.default.temporaryDirectory
            .appendingPathComponent("hyphen-transfer-tests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tempRoot, withIntermediateDirectories: true, attributes: nil)
        addTeardownBlock { [tempRoot] in
            if let tempRoot {
                try? FileManager.default.removeItem(at: tempRoot)
            }
        }
    }

    override func tearDown() {
        sessionsLock.lock()
        let toStop = sessions
        sessions = []
        sessionsLock.unlock()
        toStop.forEach { $0.stop() }
        listener?.stop()
        listener = nil
        super.tearDown()
    }

    func testSenderEmitsManifestFirstAndAckedChunks() throws {
        let outbox = RecordingTransferOutbox()
        let bytes = Data((0..<1500).map { UInt8($0 % 251) })

        let manifest = try TransferSender(outbox: outbox).sendSource(
            filename: "notes.txt",
            mimeType: "text/plain",
            source: source(bytes),
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

    func testManifestDecoderRejectsSmuggledUnknownFields() throws {
        // transfer-manifest.schema.json is additionalProperties:false; the
        // runtime decoder must reject a smuggled field too (review dim 05-03).
        let valid = try TransferManifest(
            filename: "notes.txt",
            mimeType: "text/plain",
            source: source(Data("hello".utf8)),
            chunkSizeBytes: 1024,
            fileId: "f_smuggled_destination"
        )
        var smuggled = valid.payload
        smuggled["destinationPath"] = "../../etc/passwd"
        XCTAssertThrowsError(try TransferManifest(payload: smuggled))
    }

    func testReceiverReconstructsSmallFilesInBothDirections() throws {
        let macToAndroid = Data((0..<1500).map { UInt8($0 % 251) })
        let androidToMac = Data("hello back from Android".utf8)

        XCTAssertEqual(try Data(contentsOf: sendThroughReceiver(macToAndroid, fileId: "f_macos_to_android").fileURL), macToAndroid)
        XCTAssertEqual(try Data(contentsOf: sendThroughReceiver(androidToMac, fileId: "f_android_to_macos").fileURL), androidToMac)
    }

    func testReceiverCompletesEmptyFilesWithoutChunks() throws {
        let outbox = RecordingTransferOutbox()
        try TransferSender(outbox: outbox).sendSource(
            filename: "empty.txt",
            mimeType: "text/plain",
            source: source(Data()),
            chunkSizeBytes: 1024,
            fileId: "f_empty_file"
        )

        let event = try receiver().handle(toEnvelope(outbox.envelopes.single!, index: 0))

        guard case .completed(let completed) = event else {
            return XCTFail("empty file should complete from manifest")
        }
        XCTAssertEqual(try Data(contentsOf: completed.fileURL), Data())
        XCTAssertEqual(completed.sha256, completed.manifest.sha256)
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
        try TransferSender(outbox: outbox).sendSource(
            filename: "sample.bin",
            mimeType: "application/octet-stream",
            source: source(bytes),
            chunkSizeBytes: 1024,
            fileId: "f_bad_file_hash"
        )
        let receiver = try receiver()
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
        let outbox = RecordingTransferOutbox()
        let sender = TransferSender(outbox: outbox)
        let manifest = try sender.sendSource(
            filename: "sample.bin",
            mimeType: "application/octet-stream",
            source: source(bytes),
            chunkSizeBytes: 1024,
            fileId: "f_resume_test"
        )
        let receiver = try receiver()

        for (index, sent) in outbox.envelopes.prefix(2).enumerated() {
            _ = try receiver.handle(toEnvelope(sent, index: index))
        }
        outbox.envelopes.removeAll()

        try sender.requestResume(fileId: manifest.fileId)
        let resumeEvent = try receiver.handle(toEnvelope(try XCTUnwrap(outbox.envelopes.single), index: 2))
        guard case .resumeRequested(let info) = resumeEvent else {
            return XCTFail("resume request should produce resume info")
        }
        XCTAssertEqual(info.nextChunkIndex, 1)
        outbox.envelopes.removeAll()

        try sender.handleResumeInfo(info)
        var completed: TransferCompleted?
        for (index, sent) in outbox.envelopes.enumerated() {
            if case .completed(let next) = try receiver.handle(toEnvelope(sent, index: index + 3)) {
                completed = next
            }
        }

        let completedFile = try XCTUnwrap(completed?.fileURL)
        XCTAssertEqual(try Data(contentsOf: completedFile), bytes)
    }

    func testDuplicateManifestIsIdempotentAndKeepsReceivedChunks() throws {
        let bytes = Data((0..<2500).map { UInt8($0 % 251) })
        let outbox = RecordingTransferOutbox()
        let manifest = try TransferSender(outbox: outbox).sendSource(
            filename: "sample.bin",
            mimeType: "application/octet-stream",
            source: source(bytes),
            chunkSizeBytes: 1024,
            fileId: "f_duplicate_manifest"
        )
        let receiver = try receiver()

        _ = try receiver.handle(toEnvelope(outbox.envelopes[0], index: 0))
        _ = try receiver.handle(toEnvelope(outbox.envelopes[1], index: 1))
        _ = try receiver.handle(toEnvelope(outbox.envelopes[0], index: 2))

        XCTAssertEqual(receiver.checkpoint(fileId: manifest.fileId)?.nextChunkIndex, 1)
        var completed: TransferCompleted?
        for (index, sent) in outbox.envelopes.dropFirst(2).enumerated() {
            if case .completed(let done) = try receiver.handle(toEnvelope(sent, index: index + 3)) {
                completed = done
            }
        }
        XCTAssertEqual(try Data(contentsOf: try XCTUnwrap(completed?.fileURL)), bytes)
    }

    func testDuplicateManifestWithChangedPayloadIsRejected() throws {
        let outbox = RecordingTransferOutbox()
        try TransferSender(outbox: outbox).sendSource(
            filename: "sample.bin",
            mimeType: "application/octet-stream",
            source: source(Data((0..<1500).map { UInt8($0 % 251) })),
            chunkSizeBytes: 1024,
            fileId: "f_changed_manifest"
        )
        let receiver = try receiver()
        _ = try receiver.handle(toEnvelope(outbox.envelopes[0], index: 0))
        var changed = outbox.envelopes[0]
        changed.payload["filename"] = "other.bin"

        XCTAssertThrowsError(try receiver.handle(toEnvelope(changed, index: 1)))
    }

    func testUnknownResumeAsksSenderToResendManifestBeforeChunks() throws {
        let bytes = Data((0..<1500).map { UInt8($0 % 251) })
        let receiver = try receiver()
        let outbox = RecordingTransferOutbox()
        let sender = TransferSender(outbox: outbox)
        let manifest = try sender.sendSource(
            filename: "sample.bin",
            mimeType: "application/octet-stream",
            source: source(bytes),
            chunkSizeBytes: 1024,
            fileId: "f_unknown_resume"
        )
        outbox.envelopes.removeAll()

        try sender.requestResume(fileId: manifest.fileId)
        let event = try receiver.handle(toEnvelope(try XCTUnwrap(outbox.envelopes.single), index: 0))
        guard case .resumeRequested(let info) = event else {
            return XCTFail("resume request should produce info")
        }
        XCTAssertTrue(info.needsManifest)

        try sender.handleResumeInfo(info)
        XCTAssertEqual(outbox.envelopes[1].type, TransferProtocol.typeManifest)
        XCTAssertEqual(outbox.envelopes[2].type, TransferProtocol.typeChunk)
    }

    func testResumeRequestForCompletedTransferReturnsFinalCheckpointWithoutManifest() throws {
        let bytes = Data((0..<2500).map { UInt8($0 % 251) })
        let outbox = RecordingTransferOutbox()
        let sender = TransferSender(outbox: outbox)
        let manifest = try sender.sendSource(
            filename: "sample.bin",
            mimeType: "application/octet-stream",
            source: source(bytes),
            chunkSizeBytes: 1024,
            fileId: "f_completed_resume"
        )
        let receiver = try receiver()
        for (index, sent) in outbox.envelopes.enumerated() {
            _ = try receiver.handle(toEnvelope(sent, index: index))
        }
        outbox.envelopes.removeAll()

        try sender.requestResume(fileId: manifest.fileId)
        let event = try receiver.handle(toEnvelope(try XCTUnwrap(outbox.envelopes.single), index: 0))
        guard case .resumeRequested(let info) = event else {
            return XCTFail("resume request should produce info")
        }

        XCTAssertEqual(info.nextChunkIndex, manifest.chunkCount)
        XCTAssertFalse(info.needsManifest)
        try sender.handleResumeInfo(info)
        XCTAssertEqual(outbox.envelopes.count, 1)
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
        XCTAssertNil(outbox.envelopes[1].payload["needsManifest"])
    }

    func testTransferPayloadIntegersRejectFractionalNumbers() throws {
        XCTAssertThrowsError(
            try TransferResumeInfo(payload: [
                "fileId": "f_fractional_resume",
                "nextChunkIndex": NSNumber(value: 1.9),
            ])
        )
        XCTAssertThrowsError(
            try TransferChunk(payload: [
                "fileId": "f_fractional_chunk",
                "chunkIndex": NSNumber(value: 1.9),
                "dataBase64": Data("hello".utf8).base64EncodedString(),
                "chunkSha256": try TransferChunk(fileId: "f_fractional_chunk", chunkIndex: 0, data: Data("hello".utf8)).chunkSha256,
            ])
        )
    }

    func testSenderHonorsBoundedOutstandingChunkWindowAndAdvancesOnAck() throws {
        let bytes = Data((0..<5_000).map { UInt8($0 % 251) })
        let outbox = RecordingTransferOutbox()
        let sender = TransferSender(outbox: outbox, outstandingWindow: 2)

        try sender.sendSource(
            filename: "sample.bin",
            mimeType: "application/octet-stream",
            source: source(bytes),
            chunkSizeBytes: 1024,
            fileId: "f_window_test"
        )

        XCTAssertEqual(outbox.envelopes.map(\.type), [
            TransferProtocol.typeManifest,
            TransferProtocol.typeChunk,
            TransferProtocol.typeChunk,
        ])

        try sender.handleAck("sent-1")
        XCTAssertEqual(outbox.envelopes.count, 4)
        XCTAssertEqual(outbox.envelopes[3].type, TransferProtocol.typeChunk)
        try sender.handleAck("sent-2")
        XCTAssertEqual(outbox.envelopes.count, 5)
        try sender.handleAck("sent-3")
        XCTAssertEqual(outbox.envelopes.count, 6)
        try sender.handleAck("sent-4")
        try sender.handleAck("sent-5")
    }

    func testSenderUsesNegotiatedTransferMaxChunkSizeAndRejectsUnsupportedTransfer() throws {
        let bytes = Data((0..<3000).map { UInt8($0 % 251) })
        let outbox = RecordingTransferOutbox()
        let manifest = try TransferSender(
            outbox: outbox,
            negotiatedCapabilities: try SessionHandshake.NegotiatedCapabilities.advertised(maxTransferChunkBytes: 1024)
        ).sendSource(
            filename: "sample.bin",
            mimeType: "application/octet-stream",
            source: source(bytes),
            chunkSizeBytes: 2048,
            fileId: "f_negotiated_chunk"
        )

        XCTAssertEqual(manifest.chunkSizeBytes, 1024)
        XCTAssertThrowsError(
            try TransferSender(
                outbox: RecordingTransferOutbox(),
                negotiatedCapabilities: SessionHandshake.NegotiatedCapabilities.empty()
            ).sendSource(
                filename: "sample.bin",
                mimeType: "application/octet-stream",
                source: source(bytes),
                chunkSizeBytes: 1024,
                fileId: "f_unsupported_transfer"
            )
        )
    }

    func testProtocolSessionLoopbackResumesInterruptedTransfer() throws {
        let serverStore = KeychainIdentityStore(label: "dev.hyphen.identity.test.\(UUID().uuidString)")
        let clientStore = KeychainIdentityStore(label: "dev.hyphen.identity.test.\(UUID().uuidString)")
        addTeardownBlock {
            try? serverStore.removeIdentity()
            try? clientStore.removeIdentity()
        }
        let serverIdentity = try serverStore.loadOrCreate(commonName: "Server")
        let clientIdentity = try clientStore.loadOrCreate(commonName: "Client")
        let queue = DispatchQueue(label: "transfer-loopback-test")
        let fileId = "f_protocol_resume"
        let bytes = Data((0..<12_500).map { UInt8($0 % 251) })
        let firstChunk = expectation(description: "first chunk checkpoint")
        firstChunk.assertForOverFulfill = false
        let completed = expectation(description: "transfer completed")
        var completedURL: URL?

        let serverReceiver = try receiver()
        var serverSession: ProtocolSession?
        listener = TLSEndpointListener(
            identity: serverIdentity,
            verifier: SPKIPinVerifier(expectedFingerprint: clientIdentity.spkiFingerprint)
        )
        let listening = expectation(description: "listening")
        try listener?.start(port: 0, queue: queue, onState: { state in
            if case .listening = state { listening.fulfill() }
        }, onConnection: { [weak self] connection in
            var callbacks = ProtocolSession.Callbacks()
            callbacks.onEnvelope = { envelope in
                do {
                    switch try serverReceiver.handle(envelope) {
                    case .completed(let done):
                        completedURL = done.fileURL
                        completed.fulfill()
                    case .resumeRequested(let info):
                        if let serverSession {
                            TransferSender(outbox: ProtocolSessionTransferOutbox(session: serverSession)).sendResumeInfo(info)
                        }
                    case .cancelled, .ignored:
                        break
                    }
                    if serverReceiver.checkpoint(fileId: fileId)?.nextChunkIndex == 1 {
                        firstChunk.fulfill()
                    }
                } catch {
                    XCTFail("server transfer handler failed: \(error)")
                }
            }
            let session = ProtocolSession(connection: connection, sessionId: "s_test1", callbacks: callbacks)
            serverSession = session
            self?.appendSession(session)
            session.start()
        })
        wait(for: [listening], timeout: 5)
        guard case .listening(let port) = listener?.state else {
            throw XCTSkip("listener failed to report a port")
        }

        let connected = expectation(description: "client connected")
        var clientSession: ProtocolSession?
        var clientSender: TransferSender?
        TLSConnector.connect(
            host: "127.0.0.1",
            port: port,
            identity: clientIdentity,
            verifier: SPKIPinVerifier(expectedFingerprint: serverIdentity.spkiFingerprint),
            queue: queue
        ) { [weak self] result in
            if case .success(let connection) = result {
                var callbacks = ProtocolSession.Callbacks()
                callbacks.onEnvelope = { envelope in
                    guard envelope.type == TransferProtocol.typeResumeInfo else { return }
                    do {
                        try clientSender?.handleResumeInfo(TransferResumeInfo(payload: envelope.payload))
                    } catch {
                        XCTFail("client resume handler failed: \(error)")
                    }
                }
                callbacks.onAck = { messageId in
                    do {
                        try clientSender?.handleAck(messageId)
                    } catch {
                        XCTFail("client ack handler failed: \(error)")
                    }
                }
                let session = ProtocolSession(connection: connection, sessionId: "s_test1", callbacks: callbacks)
                clientSession = session
                self?.appendSession(session)
                session.start()
            }
            connected.fulfill()
        }
        wait(for: [connected], timeout: 5)
        let client = try XCTUnwrap(clientSession)
        let gatedOutbox = GatedProtocolTransferOutbox(session: client)
        let sender = TransferSender(outbox: gatedOutbox)
        clientSender = sender

        let manifest = try sender.sendSource(
            filename: "sample.bin",
            mimeType: "application/octet-stream",
            source: source(bytes),
            chunkSizeBytes: 1024,
            fileId: fileId
        )
        XCTAssertGreaterThan(manifest.chunkCount, 8)
        wait(for: [firstChunk], timeout: 5)

        gatedOutbox.mode = .all
        try sender.requestResume(fileId: fileId)
        wait(for: [completed], timeout: 5)
        XCTAssertEqual(try Data(contentsOf: try XCTUnwrap(completedURL)), bytes)
    }

    func testSenderAndReceiverReportTransferProgress() throws {
        let bytes = Data((0..<2500).map { UInt8($0 % 251) })
        let outbox = RecordingTransferOutbox()
        var sendProgress: [TransferProgress] = []
        let sender = TransferSender(outbox: outbox) { sendProgress.append($0) }
        let manifest = try sender.sendSource(
            filename: "sample.bin",
            mimeType: "application/octet-stream",
            source: source(bytes),
            chunkSizeBytes: 1024,
            fileId: "f_progress_test"
        )
        var receiveProgress: [TransferProgress] = []
        let receiver = try receiver { receiveProgress.append($0) }

        XCTAssertEqual(sendProgress.map(\.completedChunks), [0])

        var processed = 0
        while processed < outbox.envelopes.count {
            let sent = outbox.envelopes[processed]
            _ = try receiver.handle(toEnvelope(sent, index: processed))
            if sent.type == TransferProtocol.typeChunk {
                try sender.handleAck("sent-\(processed)")
            }
            processed += 1
        }

        XCTAssertEqual(sendProgress.map(\.completedChunks), [0, 1, 2, 3])
        XCTAssertEqual(receiveProgress.map(\.completedChunks), [0, 1, 2, 3])
        XCTAssertTrue(sendProgress.allSatisfy { ($0.sentChunks ?? 0) >= $0.completedChunks })
        XCTAssertEqual(sendProgress.last?.completedBytes, Int64(bytes.count))
        XCTAssertEqual(receiveProgress.last?.totalBytes, manifest.sizeBytes)
        XCTAssertEqual(sendProgress.last?.isComplete, true)
    }

    func testDuplicateAndOutOfOrderChunksKeepContiguousCheckpointSeparate() throws {
        let bytes = Data((0..<2500).map { UInt8($0 % 251) })
        let outbox = RecordingTransferOutbox()
        let manifest = try TransferSender(outbox: outbox).sendSource(
            filename: "sample.bin",
            mimeType: "application/octet-stream",
            source: source(bytes),
            chunkSizeBytes: 1024,
            fileId: "f_out_of_order"
        )
        var receiveProgress: [TransferProgress] = []
        let receiver = try receiver { receiveProgress.append($0) }
        _ = try receiver.handle(toEnvelope(outbox.envelopes[0], index: 0))
        _ = try receiver.handle(toEnvelope(outbox.envelopes[1], index: 1))
        _ = try receiver.handle(toEnvelope(outbox.envelopes[1], index: 2))
        _ = try receiver.handle(toEnvelope(outbox.envelopes[3], index: 3))

        XCTAssertEqual(receiver.checkpoint(fileId: manifest.fileId)?.nextChunkIndex, 1)
        let progress = try XCTUnwrap(receiveProgress.last)
        XCTAssertEqual(progress.completedChunks, 1)
        XCTAssertEqual(progress.receivedChunks, 2)

        _ = try receiver.handle(toEnvelope(outbox.envelopes[2], index: 4))
        XCTAssertEqual(receiver.checkpoint(fileId: manifest.fileId)?.nextChunkIndex, 3)
    }

    func testReceiverEnforcesConcurrentTransferAndStagingQuotas() throws {
        let limits = TransferResourceLimits(maxConcurrentTransfers: 1, maxStagingBytes: 2048, maxCompletedEntries: 1)
        let receiver = try receiver(limits: limits)
        let first = manifestPayload(fileId: "f_quota_one", sizeBytes: 1500, chunkSizeBytes: 1024, chunkCount: 2)
        let second = manifestPayload(fileId: "f_quota_two", sizeBytes: 1500, chunkSizeBytes: 1024, chunkCount: 2)

        _ = try receiver.handle(
            toEnvelope(
                SentTransferEnvelope(
                    type: TransferProtocol.typeManifest,
                    capability: TransferProtocol.capability,
                    requiresAck: true,
                    payload: first
                ),
                index: 0
            )
        )
        XCTAssertThrowsError(
            try receiver.handle(
                toEnvelope(
                    SentTransferEnvelope(
                        type: TransferProtocol.typeManifest,
                        capability: TransferProtocol.capability,
                        requiresAck: true,
                        payload: second
                    ),
                    index: 1
                )
            )
        )
    }

    func testSenderEnforcesRegistryAndOutstandingQuotas() throws {
        let limits = TransferResourceLimits(
            maxSenderRegistrySize: 1,
            maxOutstandingMessages: 1,
            maxOutstandingBytes: 2048
        )
        let outbox = RecordingTransferOutbox()
        let sender = TransferSender(outbox: outbox, outstandingWindow: 4, limits: limits)
        try sender.sendSource(
            filename: "one.bin",
            mimeType: "application/octet-stream",
            source: source(Data((0..<1500).map { UInt8($0 % 251) })),
            chunkSizeBytes: 1024,
            fileId: "f_registry_one"
        )
        XCTAssertThrowsError(
            try sender.sendSource(
                filename: "two.bin",
                mimeType: "application/octet-stream",
                source: source(Data((0..<1500).map { UInt8($0 % 251) })),
                chunkSizeBytes: 1024,
                fileId: "f_registry_two"
            )
        )
        XCTAssertEqual(outbox.envelopes.filter { $0.type == TransferProtocol.typeChunk }.count, 1)
    }

    func testAckTimeoutAbortsSenderTransfer() throws {
        let outbox = RecordingTransferOutbox()
        let sender = TransferSender(outbox: outbox, outstandingWindow: 2)
        let manifest = try sender.sendSource(
            filename: "sample.bin",
            mimeType: "application/octet-stream",
            source: source(Data((0..<5000).map { UInt8($0 % 251) })),
            chunkSizeBytes: 1024,
            fileId: "f_ack_timeout"
        )
        let before = outbox.envelopes.count
        sender.handleAckTimeout("sent-1")
        try sender.handleAck("sent-2")
        XCTAssertEqual(outbox.envelopes.count, before)
        XCTAssertThrowsError(try sender.sendRemaining(fileId: manifest.fileId, fromChunkIndex: 0))
    }

    func testCancelOnSameSenderStopsFurtherChunksAfterAck() throws {
        let bytes = Data((0..<10_000).map { UInt8($0 % 251) })
        let outbox = RecordingTransferOutbox()
        let sender = TransferSender(outbox: outbox, outstandingWindow: 2)
        let manifest = try sender.sendSource(
            filename: "sample.bin",
            mimeType: "application/octet-stream",
            source: source(bytes),
            chunkSizeBytes: 1024,
            fileId: "f_cancel_same_sender"
        )

        _ = try sender.cancel(fileId: manifest.fileId, discard: true)
        XCTAssertEqual(outbox.envelopes.last?.type, TransferProtocol.typeCancel)
        let countAfterCancel = outbox.envelopes.count

        try sender.handleAck("sent-1")
        try sender.handleAck("sent-2")
        XCTAssertEqual(outbox.envelopes.count, countAfterCancel)
        XCTAssertThrowsError(try sender.sendRemaining(fileId: manifest.fileId, fromChunkIndex: 0))
    }

    func testSenderRecyclesRegistryAfterAllChunksAreAcked() throws {
        let bytes = Data((0..<5_000).map { UInt8($0 % 251) })
        let outbox = RecordingTransferOutbox()
        let sender = TransferSender(outbox: outbox, outstandingWindow: 2)
        let manifest = try sender.sendSource(
            filename: "sample.bin",
            mimeType: "application/octet-stream",
            source: source(bytes),
            chunkSizeBytes: 1024,
            fileId: "f_sender_recycle_complete"
        )

        var processed = 0
        while processed < outbox.envelopes.count {
            let sent = outbox.envelopes[processed]
            if sent.type == TransferProtocol.typeChunk {
                try sender.handleAck("sent-\(processed)")
            }
            processed += 1
        }
        XCTAssertThrowsError(try sender.sendRemaining(fileId: manifest.fileId, fromChunkIndex: manifest.chunkCount))
        XCTAssertThrowsError(try sender.sendRemaining(fileId: manifest.fileId, fromChunkIndex: 0))
    }

    func testReceiverRecycleSessionClearsActivePartialState() throws {
        let bytes = Data((0..<2500).map { UInt8($0 % 251) })
        let outbox = RecordingTransferOutbox()
        let manifest = try TransferSender(outbox: outbox).sendSource(
            filename: "sample.bin",
            mimeType: "application/octet-stream",
            source: source(bytes),
            chunkSizeBytes: 1024,
            fileId: "f_receiver_recycle"
        )
        let receiver = try receiver()
        _ = try receiver.handle(toEnvelope(outbox.envelopes[0], index: 0))
        _ = try receiver.handle(toEnvelope(outbox.envelopes[1], index: 1))
        XCTAssertEqual(receiver.checkpoint(fileId: manifest.fileId)?.nextChunkIndex, 1)

        receiver.recycleSession()

        XCTAssertNil(receiver.checkpoint(fileId: manifest.fileId))
        XCTAssertThrowsError(try receiver.handle(toEnvelope(outbox.envelopes[1], index: 2)))
    }

    func testReceiverResumesTransfersAfterRecycleAndRebind() throws {
        let bytes = Data((0..<2500).map { UInt8($0 % 251) })
        let outbox = RecordingTransferOutbox()
        _ = try TransferSender(outbox: outbox).sendSource(
            filename: "sample.bin",
            mimeType: "application/octet-stream",
            source: source(bytes),
            chunkSizeBytes: 1024,
            fileId: "f_rebind_rearm"
        )
        let receiver = try receiver()

        // Freeze the long-lived receiver as a session teardown would, then re-arm
        // it on the next session: a fresh transfer must complete, not be rejected.
        receiver.recycleSession()
        receiver.bindSession(peerFingerprint: Data(repeating: 1, count: 32), sessionId: "s_rebind")

        var completed: TransferCompleted?
        for (index, sent) in outbox.envelopes.enumerated() {
            if case let .completed(next) = try receiver.handle(toEnvelope(sent, index: index)) {
                completed = next
            }
        }
        let delivered = try XCTUnwrap(completed)
        XCTAssertEqual(try Data(contentsOf: delivered.fileURL), bytes)
    }

    func testCompletedReceiverKeepsBoundedTombstoneCheckpointWithoutFileRef() throws {
        let bytes = Data((0..<2500).map { UInt8($0 % 251) })
        let outbox = RecordingTransferOutbox()
        let manifest = try TransferSender(outbox: outbox).sendSource(
            filename: "sample.bin",
            mimeType: "application/octet-stream",
            source: source(bytes),
            chunkSizeBytes: 1024,
            fileId: "f_completed_tombstone"
        )
        let receiver = try receiver()
        for (index, sent) in outbox.envelopes.enumerated() {
            _ = try receiver.handle(toEnvelope(sent, index: index))
        }

        XCTAssertEqual(receiver.checkpoint(fileId: manifest.fileId)?.nextChunkIndex, manifest.chunkCount)
        let duplicate = try receiver.handle(toEnvelope(outbox.envelopes[0], index: outbox.envelopes.count))
        XCTAssertEqual(duplicate, .ignored)
    }

    func testCancelMessageUsesProtocolWireNameAndDiscardClearsReceiverCheckpoint() throws {
        let bytes = Data((0..<2500).map { UInt8($0 % 251) })
        let outbox = RecordingTransferOutbox()
        let sender = TransferSender(outbox: outbox)
        let manifest = try sender.sendSource(
            filename: "sample.bin",
            mimeType: "application/octet-stream",
            source: source(bytes),
            chunkSizeBytes: 1024,
            fileId: "f_cancel_test"
        )
        let receiver = try receiver()
        for (index, sent) in outbox.envelopes.prefix(2).enumerated() {
            _ = try receiver.handle(toEnvelope(sent, index: index))
        }
        XCTAssertEqual(receiver.checkpoint(fileId: manifest.fileId)?.nextChunkIndex, 1)

        _ = try sender.cancel(fileId: manifest.fileId, discard: true)
        let cancel = try XCTUnwrap(outbox.envelopes.last)
        XCTAssertEqual(cancel.type, TransferProtocol.typeCancel)
        XCTAssertEqual(cancel.payload["discard"] as? Bool, true)

        _ = try receiver.handle(toEnvelope(cancel, index: 2))

        XCTAssertNil(receiver.checkpoint(fileId: manifest.fileId))
    }

    func testReceiverRejectsChunkSizesThatDoNotMatchTheManifest() throws {
        let bytes = Data((0..<2500).map { UInt8($0 % 251) })
        let outbox = RecordingTransferOutbox()
        let manifest = try TransferSender(outbox: outbox).sendSource(
            filename: "sample.bin",
            mimeType: "application/octet-stream",
            source: source(bytes),
            chunkSizeBytes: 1024,
            fileId: "f_bad_chunk_size"
        )
        let receiver = try receiver()
        _ = try receiver.handle(toEnvelope(outbox.envelopes[0], index: 0))

        XCTAssertThrowsError(try receiver.handle(toEnvelope(chunk(fileId: manifest.fileId, chunkIndex: 0, bytes: Data(repeating: 0, count: 1000)), index: 1)))
        XCTAssertThrowsError(try receiver.handle(toEnvelope(chunk(fileId: manifest.fileId, chunkIndex: 0, bytes: Data(repeating: 0, count: 1025)), index: 2)))
        XCTAssertThrowsError(try receiver.handle(toEnvelope(chunk(fileId: manifest.fileId, chunkIndex: 2, bytes: Data(repeating: 0, count: 500)), index: 3)))
    }

    func testReceiverRejectsOversizedManifestsBeforeStateAllocation() throws {
        let receiver = TransferReceiver(storage: RejectingTransferStorage())

        XCTAssertThrowsError(
            try receiver.handle(
                toEnvelope(
                    SentTransferEnvelope(
                        type: TransferProtocol.typeManifest,
                        capability: TransferProtocol.capability,
                        requiresAck: true,
                        payload: manifestPayload(
                            fileId: "f_oversized_manifest",
                            sizeBytes: TransferProtocol.maxV0TransferSizeBytes + 1,
                            chunkSizeBytes: 1024,
                            chunkCount: TransferProtocol.maxV0TransferChunkCount + 1
                        )
                    ),
                    index: 0
                )
            )
        )
    }

    func testManifestAcceptsExactlyOneGibibyteWhenChunkMathIsValid() throws {
        let manifest = try TransferManifest(
            payload: manifestPayload(
                fileId: "f_exactly_one_gib",
                sizeBytes: TransferProtocol.maxV0TransferSizeBytes,
                chunkSizeBytes: TransferProtocol.maxChunkSizeBytes,
                chunkCount: 512
            )
        )

        XCTAssertEqual(manifest.sizeBytes, TransferProtocol.maxV0TransferSizeBytes)
        XCTAssertEqual(manifest.chunkCount, 512)
    }

    func testSenderRejectsOversizedSourcesBeforeHashing() throws {
        let source = CountingTransferByteSource(sizeBytes: TransferProtocol.maxV0TransferSizeBytes + 1)

        XCTAssertThrowsError(
            try TransferManifest(
                filename: "large.bin",
                mimeType: "application/octet-stream",
                source: source,
                chunkSizeBytes: 1024,
                fileId: "f_oversized_source"
            )
        )
        XCTAssertEqual(source.openCount, 0)
    }

    private func sendThroughReceiver(_ bytes: Data, fileId: String) throws -> TransferCompleted {
        let outbox = RecordingTransferOutbox()
        try TransferSender(outbox: outbox).sendSource(
            filename: "sample.bin",
            mimeType: "application/octet-stream",
            source: source(bytes),
            chunkSizeBytes: 1024,
            fileId: fileId
        )
        let receiver = try receiver()
        var completed: TransferCompleted?
        for (index, sent) in outbox.envelopes.enumerated() {
            if case .completed(let next) = try receiver.handle(
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
            ) {
                completed = next
            }
        }
        return try XCTUnwrap(completed)
    }

    private func source(_ bytes: Data) throws -> FileTransferByteSource {
        let url = tempRoot.appendingPathComponent("source-\(UUID().uuidString).bin")
        try bytes.write(to: url)
        return FileTransferByteSource(fileURL: url)
    }

    private func receiver(
        limits: TransferResourceLimits = TransferResourceLimits(),
        onProgress: @escaping (TransferProgress) -> Void = { _ in }
    ) throws -> TransferReceiver {
        let root = tempRoot.appendingPathComponent("receive-\(UUID().uuidString)", isDirectory: true)
        return TransferReceiver(storage: FileTransferStorage(root: root), limits: limits, onProgress: onProgress)
    }

    private func appendSession(_ session: ProtocolSession) {
        sessionsLock.lock()
        sessions.append(session)
        sessionsLock.unlock()
    }

    private func chunk(fileId: String, chunkIndex: Int, bytes: Data) throws -> SentTransferEnvelope {
        SentTransferEnvelope(
            type: TransferProtocol.typeChunk,
            capability: TransferProtocol.capability,
            requiresAck: true,
            payload: try TransferChunk(fileId: fileId, chunkIndex: chunkIndex, data: bytes).payload
        )
    }

    private func manifestPayload(
        fileId: String,
        sizeBytes: Int64,
        chunkSizeBytes: Int,
        chunkCount: Int
    ) -> [String: Any] {
        [
            "fileId": fileId,
            "filename": "sample.bin",
            "sizeBytes": sizeBytes,
            "mimeType": "application/octet-stream",
            "sha256": String(repeating: "0", count: 64),
            "chunkSizeBytes": chunkSizeBytes,
            "chunkCount": chunkCount,
        ]
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

private final class RejectingTransferStorage: TransferStorage {
    func prepare(manifest: TransferManifest, reuseExisting: Bool) throws -> URL {
        XCTFail("oversized manifest should reject before storage allocation")
        throw TransferError.io("unexpected storage allocation")
    }

    func discard(fileId: String) {}

    func partFile(fileId: String) -> URL? { nil }
}

private final class CountingTransferByteSource: TransferByteSource {
    let sizeBytes: Int64
    private(set) var openCount = 0

    init(sizeBytes: Int64) {
        self.sizeBytes = sizeBytes
    }

    func openStream() throws -> InputStream {
        openCount += 1
        return InputStream(data: Data())
    }
}

private extension Array {
    var single: Element? {
        count == 1 ? self[0] : nil
    }
}

private final class GatedProtocolTransferOutbox: TransferOutbox {
    enum Mode {
        case manifestAndFirstChunk
        case all
    }

    private let session: ProtocolSession
    private let lock = NSLock()
    private var forwardedInitialChunks = 0
    var mode: Mode = .manifestAndFirstChunk

    init(session: ProtocolSession) {
        self.session = session
    }

    func send(
        type: String,
        capability: String,
        requiresAck: Bool,
        payload: [String: Any]
    ) -> String? {
        lock.lock()
        let shouldForward: Bool
        switch (mode, type) {
        case (.all, _), (.manifestAndFirstChunk, TransferProtocol.typeManifest):
            shouldForward = true
        case (.manifestAndFirstChunk, TransferProtocol.typeChunk):
            shouldForward = forwardedInitialChunks == 0
            forwardedInitialChunks += 1
        default:
            shouldForward = true
        }
        lock.unlock()

        guard shouldForward else { return nil }
        return session.send(type: type, payload: payload, requiresAck: requiresAck, capability: capability)
    }
}
