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
    private var tempRoot: URL!
    private var sessions: [ProtocolSession] = []
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
        sessions.forEach { $0.stop() }
        sessions = []
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
        let bytes = Data((0..<2500).map { UInt8($0 % 251) })
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
            self?.sessions.append(session)
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
                let session = ProtocolSession(connection: connection, sessionId: "s_test1", callbacks: callbacks)
                clientSession = session
                self?.sessions.append(session)
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
        XCTAssertEqual(manifest.chunkCount, 3)
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
        let manifest = try TransferSender(outbox: outbox) { sendProgress.append($0) }.sendSource(
            filename: "sample.bin",
            mimeType: "application/octet-stream",
            source: source(bytes),
            chunkSizeBytes: 1024,
            fileId: "f_progress_test"
        )
        var receiveProgress: [TransferProgress] = []
        let receiver = try receiver { receiveProgress.append($0) }

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
        let manifest = try TransferSender(outbox: firstOutbox).sendSource(
            filename: "sample.bin",
            mimeType: "application/octet-stream",
            source: source(bytes),
            chunkSizeBytes: 1024,
            fileId: "f_cancel_test"
        )
        let receiver = try receiver()
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
        onProgress: @escaping (TransferProgress) -> Void = { _ in }
    ) throws -> TransferReceiver {
        let root = tempRoot.appendingPathComponent("receive-\(UUID().uuidString)", isDirectory: true)
        return TransferReceiver(storage: FileTransferStorage(root: root), onProgress: onProgress)
    }

    private func chunk(fileId: String, chunkIndex: Int, bytes: Data) throws -> SentTransferEnvelope {
        SentTransferEnvelope(
            type: TransferProtocol.typeChunk,
            capability: TransferProtocol.capability,
            requiresAck: true,
            payload: try TransferChunk(fileId: fileId, chunkIndex: chunkIndex, data: bytes).payload
        )
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

        guard shouldForward else { return "dropped-by-test" }
        return session.send(type: type, payload: payload, requiresAck: requiresAck, capability: capability)
    }
}
