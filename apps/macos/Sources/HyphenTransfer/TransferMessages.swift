import CryptoKit
import Foundation
import HyphenTransport

public enum TransferProtocol {
    public static let capability = "transfer.v1"
    public static let typeManifest = "transfer.manifest"
    public static let typeChunk = "transfer.chunk"
    public static let typeResumeRequest = "transfer.resume.request"
    public static let typeResumeInfo = "transfer.resume.info"
    public static let typeCancel = "transfer.cancel"
    public static let minChunkSizeBytes = 1024
    public static let maxChunkSizeBytes = 2 * 1024 * 1024
    public static let maxV0TransferSizeBytes: Int64 = 1_073_741_824
    public static let maxV0TransferChunkCount = 1_048_576
}

/// Per-session transfer resource ceilings; fail closed when exceeded.
public struct TransferResourceLimits: Equatable {
    public let maxConcurrentTransfers: Int
    public let maxStagingBytes: Int64
    public let maxCompletedEntries: Int
    public let maxSenderRegistrySize: Int
    public let maxOutstandingMessages: Int
    public let maxOutstandingBytes: Int64

    public init(
        maxConcurrentTransfers: Int = 4,
        maxStagingBytes: Int64 = 256 * 1024 * 1024,
        maxCompletedEntries: Int = 64,
        maxSenderRegistrySize: Int = 16,
        maxOutstandingMessages: Int = 64,
        maxOutstandingBytes: Int64 = 32 * 1024 * 1024
    ) {
        precondition(maxConcurrentTransfers > 0)
        precondition(maxStagingBytes >= 0)
        precondition(maxCompletedEntries > 0)
        precondition(maxSenderRegistrySize > 0)
        precondition(maxOutstandingMessages > 0)
        precondition(maxOutstandingBytes >= 0)
        self.maxConcurrentTransfers = maxConcurrentTransfers
        self.maxStagingBytes = maxStagingBytes
        self.maxCompletedEntries = maxCompletedEntries
        self.maxSenderRegistrySize = maxSenderRegistrySize
        self.maxOutstandingMessages = maxOutstandingMessages
        self.maxOutstandingBytes = maxOutstandingBytes
    }
}

public enum TransferError: Error, Equatable {
    case invalidPayload(String)
    case io(String)
}

public struct TransferManifest: Equatable {
    public let fileId: String
    public let filename: String
    public let sizeBytes: Int64
    public let mimeType: String
    public let sha256: String
    public let chunkSizeBytes: Int
    public let chunkCount: Int

    public init(
        fileId: String,
        filename: String,
        sizeBytes: Int64,
        mimeType: String,
        sha256: String,
        chunkSizeBytes: Int,
        chunkCount: Int
    ) throws {
        guard isValidFileId(fileId) else {
            throw TransferError.invalidPayload("invalid fileId")
        }
        guard !filename.isEmpty,
              filename.unicodeScalars.contains(where: { $0.value != 0x20 }),
              filename.unicodeScalars.count <= 255,
              !filename.contains("/"),
              !filename.contains("\\"),
              !filename.unicodeScalars.contains(where: Self.isC0OrC1Control)
        else {
            throw TransferError.invalidPayload("invalid filename")
        }
        guard sizeBytes >= 0 else { throw TransferError.invalidPayload("sizeBytes must be >= 0") }
        guard sizeBytes <= TransferProtocol.maxV0TransferSizeBytes else {
            throw TransferError.invalidPayload("sizeBytes exceeds v0 transfer limit")
        }
        guard Self.matches(mimeType, #"^[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*$"#) else {
            throw TransferError.invalidPayload("invalid mimeType")
        }
        guard Self.matches(sha256, #"^[0-9a-f]{64}$"#) else {
            throw TransferError.invalidPayload("invalid sha256")
        }
        guard (TransferProtocol.minChunkSizeBytes...TransferProtocol.maxChunkSizeBytes).contains(chunkSizeBytes) else {
            throw TransferError.invalidPayload("invalid chunkSizeBytes")
        }
        guard chunkCount >= 0 else { throw TransferError.invalidPayload("chunkCount must be >= 0") }
        guard chunkCount <= TransferProtocol.maxV0TransferChunkCount else {
            throw TransferError.invalidPayload("chunkCount exceeds v0 transfer limit")
        }
        let expectedChunks: Int64 = sizeBytes == 0 ? 0 : (sizeBytes + Int64(chunkSizeBytes) - 1) / Int64(chunkSizeBytes)
        guard expectedChunks <= Int64(Int.max), Int64(chunkCount) == expectedChunks else {
            throw TransferError.invalidPayload("chunkCount does not match sizeBytes/chunkSizeBytes")
        }
        self.fileId = fileId
        self.filename = filename
        self.sizeBytes = sizeBytes
        self.mimeType = mimeType
        self.sha256 = sha256
        self.chunkSizeBytes = chunkSizeBytes
        self.chunkCount = chunkCount
    }

    public init(
        filename: String,
        mimeType: String,
        source: TransferByteSource,
        chunkSizeBytes: Int,
        fileId: String = "f_\(Ulid.generate())"
    ) throws {
        guard source.sizeBytes <= TransferProtocol.maxV0TransferSizeBytes else {
            throw TransferError.invalidPayload("sizeBytes exceeds v0 transfer limit")
        }
        try self.init(
            fileId: fileId,
            filename: filename,
            sizeBytes: source.sizeBytes,
            mimeType: mimeType,
            sha256: try source.sha256Hex(),
            chunkSizeBytes: chunkSizeBytes,
            chunkCount: source.sizeBytes == 0 ? 0 : Int((source.sizeBytes + Int64(chunkSizeBytes) - 1) / Int64(chunkSizeBytes))
        )
    }

    private static func isC0OrC1Control(_ scalar: Unicode.Scalar) -> Bool {
        scalar.value <= 0x1F || (0x7F...0x9F).contains(scalar.value)
    }

    private static let manifestFields: Set<String> = [
        "fileId", "filename", "sizeBytes", "mimeType", "sha256", "chunkSizeBytes", "chunkCount",
    ]

    public init(payload: [String: Any]) throws {
        // transfer-manifest.schema.json is additionalProperties:false; the
        // runtime decoder must reject smuggled fields (e.g. destinationPath)
        // too, not silently ignore them (review dim 05-03).
        let unknown = Set(payload.keys).subtracting(Self.manifestFields)
        guard unknown.isEmpty else {
            throw TransferError.invalidPayload("unknown manifest fields: \(unknown.sorted())")
        }
        try self.init(
            fileId: try string(payload, "fileId"),
            filename: try string(payload, "filename"),
            sizeBytes: try int64(payload, "sizeBytes"),
            mimeType: try string(payload, "mimeType"),
            sha256: try string(payload, "sha256"),
            chunkSizeBytes: Int(try int64(payload, "chunkSizeBytes")),
            chunkCount: Int(try int64(payload, "chunkCount"))
        )
    }

    public var payload: [String: Any] {
        [
            "fileId": fileId,
            "filename": filename,
            "sizeBytes": sizeBytes,
            "mimeType": mimeType,
            "sha256": sha256,
            "chunkSizeBytes": chunkSizeBytes,
            "chunkCount": chunkCount,
        ]
    }

    private static func matches(_ value: String, _ pattern: String) -> Bool {
        value.range(of: pattern, options: .regularExpression) != nil
    }
}

public protocol TransferByteSource {
    var sizeBytes: Int64 { get }
    func openStream() throws -> InputStream
}

public extension TransferByteSource {
    func sha256Hex() throws -> String {
        try sha256HexFromStream(try openStream())
    }
}

public final class FileTransferByteSource: TransferByteSource {
    public let fileURL: URL

    public init(fileURL: URL) {
        self.fileURL = fileURL
    }

    public var sizeBytes: Int64 {
        (try? FileManager.default.attributesOfItem(atPath: fileURL.path)[.size] as? NSNumber)?.int64Value ?? 0
    }

    public func openStream() throws -> InputStream {
        guard let stream = InputStream(url: fileURL) else {
            throw TransferError.io("could not open source")
        }
        return stream
    }
}

public protocol TransferStorage {
    func prepare(manifest: TransferManifest, reuseExisting: Bool) throws -> URL
    func discard(fileId: String)
    func partFile(fileId: String) -> URL?
}

public extension TransferStorage {
    func prepare(manifest: TransferManifest) throws -> URL {
        try prepare(manifest: manifest, reuseExisting: false)
    }
}

public final class FileTransferStorage: TransferStorage {
    private let root: URL
    private let isCheckpointActive: (String) -> Bool

    public init(
        root: URL = FileManager.default.temporaryDirectory
            .appendingPathComponent("hyphen-transfer", isDirectory: true),
        isCheckpointActive: @escaping (String) -> Bool = { _ in false }
    ) {
        self.root = root
        self.isCheckpointActive = isCheckpointActive
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        cleanupAgedPartFiles()
    }

    public func partFile(fileId: String) -> URL? {
        root.appendingPathComponent("\(fileId).part")
    }

    public func prepare(manifest: TransferManifest, reuseExisting: Bool) throws -> URL {
        cleanupAgedPartFiles()
        let url = partFile(fileId: manifest.fileId)!
        if reuseExisting, FileManager.default.fileExists(atPath: url.path) {
            return url
        }
        if FileManager.default.fileExists(atPath: url.path) {
            try FileManager.default.removeItem(at: url)
        }
        guard FileManager.default.createFile(atPath: url.path, contents: Data()) else {
            throw TransferError.io("could not create transfer storage")
        }
        return url
    }

    public func discard(fileId: String) {
        try? FileManager.default.removeItem(at: root.appendingPathComponent("\(fileId).part"))
    }

    private func cleanupAgedPartFiles() {
        let cutoff = Date(
            timeIntervalSinceNow: -TimeInterval(TransferCheckpointStore.defaultTTLMs) / 1_000
        )
        guard let files = try? FileManager.default.contentsOfDirectory(
            at: root,
            includingPropertiesForKeys: [
                .contentModificationDateKey,
                .isRegularFileKey,
                .isSymbolicLinkKey,
            ],
            options: [.skipsHiddenFiles]
        ) else {
            return
        }
        for url in files where url.pathExtension == "part" && url.lastPathComponent.hasPrefix("f_") {
            let fileId = url.deletingPathExtension().lastPathComponent
            guard isValidFileId(fileId),
                  !isCheckpointActive(fileId) else {
                continue
            }
            _ = OwnedFileCleanup.deleteIfAgedRegular(
                candidate: url,
                cutoff: cutoff,
                stillOwned: { [isCheckpointActive] in !isCheckpointActive(fileId) },
                quarantineName: {
                    "f_cleanup_\(UUID().uuidString.replacingOccurrences(of: "-", with: "")).part"
                }
            )
        }
    }
}

public struct TransferChunk: Equatable {
    public let fileId: String
    public let chunkIndex: Int
    public let data: Data
    public let chunkSha256: String

    public init(fileId: String, chunkIndex: Int, data: Data, chunkSha256: String? = nil) throws {
        guard chunkIndex >= 0 else { throw TransferError.invalidPayload("chunkIndex must be >= 0") }
        let digest = sha256Hex(data)
        guard chunkSha256 == nil || chunkSha256 == digest else {
            throw TransferError.invalidPayload("chunkSha256 mismatch")
        }
        self.fileId = fileId
        self.chunkIndex = chunkIndex
        self.data = data
        self.chunkSha256 = digest
    }

    public init(payload: [String: Any]) throws {
        guard let data = Data(base64Encoded: try string(payload, "dataBase64")) else {
            throw TransferError.invalidPayload("dataBase64 must be valid base64")
        }
        try self.init(
            fileId: try string(payload, "fileId"),
            chunkIndex: Int(try int64(payload, "chunkIndex")),
            data: data,
            chunkSha256: try string(payload, "chunkSha256")
        )
    }

    public var payload: [String: Any] {
        [
            "fileId": fileId,
            "chunkIndex": chunkIndex,
            "dataBase64": data.base64EncodedString(),
            "chunkSha256": chunkSha256,
        ]
    }
}

public struct TransferResumeRequest: Equatable {
    public let fileId: String

    public init(fileId: String) throws {
        guard isValidFileId(fileId) else { throw TransferError.invalidPayload("invalid fileId") }
        self.fileId = fileId
    }

    public init(payload: [String: Any]) throws {
        try self.init(fileId: try string(payload, "fileId"))
    }

    public var payload: [String: Any] {
        ["fileId": fileId]
    }
}

public struct TransferResumeInfo: Equatable {
    public let fileId: String
    public let nextChunkIndex: Int
    public let needsManifest: Bool

    public init(fileId: String, nextChunkIndex: Int, needsManifest: Bool = false) throws {
        guard isValidFileId(fileId) else { throw TransferError.invalidPayload("invalid fileId") }
        guard nextChunkIndex >= 0 else { throw TransferError.invalidPayload("nextChunkIndex must be >= 0") }
        self.fileId = fileId
        self.nextChunkIndex = nextChunkIndex
        self.needsManifest = needsManifest
    }

    public init(payload: [String: Any]) throws {
        try self.init(
            fileId: try string(payload, "fileId"),
            nextChunkIndex: Int(try int64(payload, "nextChunkIndex")),
            needsManifest: try optionalBool(payload, "needsManifest") ?? false
        )
    }

    public var payload: [String: Any] {
        var values: [String: Any] = [
            "fileId": fileId,
            "nextChunkIndex": nextChunkIndex,
        ]
        if needsManifest {
            values["needsManifest"] = true
        }
        return values
    }
}

public struct TransferCancel: Equatable {
    public let fileId: String
    public let discard: Bool

    public init(fileId: String, discard: Bool = false) throws {
        guard isValidFileId(fileId) else { throw TransferError.invalidPayload("invalid fileId") }
        self.fileId = fileId
        self.discard = discard
    }

    public init(payload: [String: Any]) throws {
        guard let discard = payload["discard"] as? Bool else {
            throw TransferError.invalidPayload("discard must be boolean")
        }
        try self.init(fileId: try string(payload, "fileId"), discard: discard)
    }

    public var payload: [String: Any] {
        [
            "fileId": fileId,
            "discard": discard,
        ]
    }
}

public struct TransferProgress: Equatable {
    public let fileId: String
    public let filename: String
    /// Primary UI progress: contiguous acked chunks (sender) or verified chunks (receiver).
    public let completedChunks: Int
    public let totalChunks: Int
    public let completedBytes: Int64
    public let totalBytes: Int64
    /// Sender debug: chunks queued/sent but not necessarily acked.
    public let sentChunks: Int?
    /// Receiver debug: total unique chunks received, including out-of-order.
    public let receivedChunks: Int?

    public var isComplete: Bool {
        completedChunks == totalChunks
    }

    public init(
        manifest: TransferManifest,
        completedChunks: Int,
        sentChunks: Int? = nil,
        receivedChunks: Int? = nil
    ) throws {
        guard (0...manifest.chunkCount).contains(completedChunks) else {
            throw TransferError.invalidPayload("completedChunks out of range")
        }
        self.fileId = manifest.fileId
        self.filename = manifest.filename
        self.completedChunks = completedChunks
        self.totalChunks = manifest.chunkCount
        self.completedBytes = Self.contiguousCompletedBytes(manifest: manifest, contiguousChunks: completedChunks)
        self.totalBytes = manifest.sizeBytes
        self.sentChunks = sentChunks
        self.receivedChunks = receivedChunks
    }

    private static func contiguousCompletedBytes(manifest: TransferManifest, contiguousChunks: Int) -> Int64 {
        if contiguousChunks <= 0 { return 0 }
        if contiguousChunks >= manifest.chunkCount { return manifest.sizeBytes }
        return min(
            manifest.sizeBytes,
            Int64(contiguousChunks) * Int64(manifest.chunkSizeBytes)
        )
    }
}

public protocol TransferOutbox {
    @discardableResult
    func send(
        type: String,
        capability: String,
        requiresAck: Bool,
        payload: [String: Any]
    ) -> String?
}

public final class ProtocolSessionTransferOutbox: TransferOutbox {
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
        session.send(type: type, payload: payload, requiresAck: requiresAck, capability: capability)
    }
}

public struct TransferSessionBinding: Equatable {
    public let peerFingerprint: Data
    public let sessionId: String

    public init(peerFingerprint: Data, sessionId: String) {
        self.peerFingerprint = peerFingerprint
        self.sessionId = sessionId
    }
}

public final class TransferSender {
    private struct ControlMessage {
        let type: String
        let payload: [String: Any]
        let key: String
        let sendAttempts: Int
        let generation: UInt64

        init(
            type: String,
            payload: [String: Any],
            key: String,
            sendAttempts: Int = 0,
            generation: UInt64
        ) {
            self.type = type
            self.payload = payload
            self.key = key
            self.sendAttempts = sendAttempts
            self.generation = generation
        }
    }

    private let stateLock = NSRecursiveLock()
    private let outbox: TransferOutbox
    private let negotiatedCapabilities: SessionHandshake.NegotiatedCapabilities?
    private let onProgress: (TransferProgress) -> Void
    private let onRestored: (TransferProgress) -> Void
    private let outstandingWindow: Int
    private let limits: TransferResourceLimits
    private struct RegisteredTransfer {
        let manifest: TransferManifest
        let source: TransferByteSource
    }
    private final class ActiveSend {
        let manifest: TransferManifest
        let source: TransferByteSource
        var stream: InputStream?
        var nextIndex: Int
        var manifestPending: Bool
        var outstanding: [String: Int] = [:]
        var acked: [Bool]
        var highestContiguousAcked = -1
        var outstandingBytes: Int64 = 0
        var pendingChunk: TransferChunk?
        var manifestMessageId: String?
        var sendInFlight = false

        init(
            manifest: TransferManifest,
            source: TransferByteSource,
            stream: InputStream?,
            nextIndex: Int,
            manifestPending: Bool
        ) {
            self.manifest = manifest
            self.source = source
            self.stream = stream
            self.nextIndex = nextIndex
            self.manifestPending = manifestPending
            self.acked = Array(repeating: false, count: manifest.chunkCount)
        }

        func closeStream() {
            stream?.close()
            stream = nil
        }

        func markAcked(chunkIndex: Int) -> Int {
            guard chunkIndex >= 0, chunkIndex < acked.count, !acked[chunkIndex] else {
                return highestContiguousAcked + 1
            }
            acked[chunkIndex] = true
            if chunkIndex == highestContiguousAcked + 1 {
                highestContiguousAcked = chunkIndex
                while highestContiguousAcked + 1 < acked.count, acked[highestContiguousAcked + 1] {
                    highestContiguousAcked += 1
                }
            }
            return highestContiguousAcked + 1
        }
    }
    private var registered: [String: RegisteredTransfer] = [:]
    private var activeSends: [String: ActiveSend] = [:]
    private var tombstones: [String: TerminalOutcome] = [:]
    private var earlyAckMessageIds: Set<String> = []
    private var earlyTimeoutMessageIds: Set<String> = []
    private var pendingControls: [ControlMessage] = []
    private var outstandingControls: [String: ControlMessage] = [:]
    private var controlSendInFlightGeneration: UInt64?
    private var nextControlGeneration: UInt64 = 0
    private var frozen = false
    private var sessionBinding: TransferSessionBinding?
    private let checkpointStore: TransferCheckpointStore?

    public init(
        outbox: TransferOutbox,
        negotiatedCapabilities: SessionHandshake.NegotiatedCapabilities? = nil,
        outstandingWindow: Int = 8,
        limits: TransferResourceLimits = TransferResourceLimits(),
        checkpointStore: TransferCheckpointStore? = nil,
        onRestored: ((TransferProgress) -> Void)? = nil,
        onProgress: @escaping (TransferProgress) -> Void = { _ in }
    ) {
        precondition(outstandingWindow > 0, "outstandingWindow must be positive")
        self.outbox = outbox
        self.negotiatedCapabilities = negotiatedCapabilities
        self.outstandingWindow = outstandingWindow
        self.limits = limits
        self.checkpointStore = checkpointStore
        self.onProgress = onProgress
        self.onRestored = onRestored ?? onProgress
    }

    public func bindSession(peerFingerprint: Data, sessionId: String) {
        stateLock.lock()
        sessionBinding = TransferSessionBinding(peerFingerprint: peerFingerprint, sessionId: sessionId)
        checkpointStore?.purgeExpired()
        let restored = restoreOutboundCheckpoints(peerFingerprint: peerFingerprint)
        stateLock.unlock()
        restored.forEach { progress in
            onRestored(progress)
            // The sender initiates resume by asking the receiver for its
            // durable checkpoint, including when no manifest reached it.
            _ = try? requestResume(fileId: progress.fileId)
        }
    }

    public func invalidatePeerCheckpoints(_ peerFingerprint: Data) {
        stateLock.lock()
        defer { stateLock.unlock() }
        checkpointStore?.invalidatePeer(peerFingerprint)
    }

    deinit {
        activeSends.values.forEach { $0.closeStream() }
    }

    @discardableResult
    public func sendSource(
        filename: String,
        mimeType: String,
        source: TransferByteSource,
        chunkSizeBytes: Int,
        fileId: String = "f_\(Ulid.generate())"
    ) throws -> TransferManifest {
        stateLock.lock()
        defer { stateLock.unlock() }
        if frozen {
            throw TransferError.invalidPayload("plugin/transfer-cancelled")
        }
        if negotiatedCapabilities?.contains(TransferProtocol.capability) == false {
            throw TransferError.invalidPayload("plugin/unsupported-capability")
        }
        if registered.count >= limits.maxSenderRegistrySize, registered[fileId] == nil {
            throw TransferError.invalidPayload("plugin/transfer-quota-exceeded")
        }
        let effectiveChunkSize = min(
            chunkSizeBytes,
            negotiatedCapabilities?.transferMaxChunkBytes ?? chunkSizeBytes
        )
        let manifest = try TransferManifest(
            filename: filename,
            mimeType: mimeType,
            source: source,
            chunkSizeBytes: effectiveChunkSize,
            fileId: fileId
        )
        tombstones.removeValue(forKey: manifest.fileId)
        registered[manifest.fileId] = RegisteredTransfer(manifest: manifest, source: source)
        try persistOutboundCheckpoint(manifest: manifest, source: source, fromChunkIndex: 0)
        try reportProgressOutsideLock(manifest: manifest, completedChunks: 0, sentChunks: 0)
        try startSendLocked(fileId: manifest.fileId, fromChunkIndex: 0, sendManifest: true)
        return manifest
    }

    public func sendRemainingBytes(
        fileId: String,
        fromChunkIndex: Int
    ) throws {
        stateLock.lock()
        defer { stateLock.unlock() }
        try startSendLocked(fileId: fileId, fromChunkIndex: fromChunkIndex, sendManifest: false)
    }

    public func sendRemaining(
        fileId: String,
        fromChunkIndex: Int
    ) throws {
        stateLock.lock()
        defer { stateLock.unlock() }
        try startSendLocked(fileId: fileId, fromChunkIndex: fromChunkIndex, sendManifest: false)
    }

    private func startSendLocked(
        fileId: String,
        fromChunkIndex: Int,
        sendManifest: Bool
    ) throws {
        if frozen {
            throw TransferError.invalidPayload("plugin/transfer-cancelled")
        }
        guard !isTerminal(fileId: fileId) else {
            throw TransferError.invalidPayload("transfer is terminal")
        }
        guard let transfer = registered[fileId] else {
            throw TransferError.invalidPayload("unknown outbound fileId")
        }
        let manifest = transfer.manifest
        let source = transfer.source
        guard (0...manifest.chunkCount).contains(fromChunkIndex) else {
            throw TransferError.invalidPayload("fromChunkIndex out of range")
        }
        guard source.sizeBytes == manifest.sizeBytes else {
            throw TransferError.invalidPayload("size mismatch")
        }
        activeSends[fileId]?.closeStream()
        if fromChunkIndex == manifest.chunkCount, !sendManifest {
            activeSends.removeValue(forKey: fileId)
            registered.removeValue(forKey: fileId)
            checkpointStore?.delete(fileId: fileId)
            recordTombstone(fileId: fileId, outcome: .completed)
            try reportProgressOutsideLock(
                manifest: manifest,
                completedChunks: manifest.chunkCount,
                sentChunks: manifest.chunkCount
            )
            return
        }
        let stream: InputStream?
        if fromChunkIndex < manifest.chunkCount {
            let opened = try source.openStream()
            opened.open()
            try skipFully(opened, bytes: Int64(fromChunkIndex) * Int64(manifest.chunkSizeBytes))
            stream = opened
        } else {
            stream = nil
        }
        activeSends[fileId] = ActiveSend(
            manifest: manifest,
            source: source,
            stream: stream,
            nextIndex: fromChunkIndex,
            manifestPending: sendManifest
        )
        try pumpChunksLocked(fileId: fileId)
    }

    public func handleResumeInfo(_ info: TransferResumeInfo) throws {
        stateLock.lock()
        defer { stateLock.unlock() }
        if frozen || isTerminal(fileId: info.fileId) { return }
        guard let transfer = registered[info.fileId] else {
            throw TransferError.invalidPayload("unknown outbound fileId")
        }
        if info.needsManifest {
            try reportProgressOutsideLock(manifest: transfer.manifest, completedChunks: 0, sentChunks: 0)
        }
        try startSendLocked(
            fileId: info.fileId,
            fromChunkIndex: info.nextChunkIndex,
            sendManifest: info.needsManifest
        )
    }

    public func handleAck(_ messageId: String) throws {
        stateLock.lock()
        defer { stateLock.unlock() }
        if frozen { return }
        if outstandingControls.removeValue(forKey: messageId) != nil {
            // Control messages have no per-transfer ACK window to advance.
        } else if let fileId = activeSends.first(where: {
            $0.value.manifestMessageId == messageId || $0.value.outstanding[messageId] != nil
        })?.key,
           !isTerminal(fileId: fileId),
           let active = activeSends[fileId] {
            if active.manifestMessageId == messageId {
                active.manifestMessageId = nil
            } else if let chunkIndex = active.outstanding.removeValue(forKey: messageId) {
                active.outstandingBytes -= Int64(active.manifest.expectedChunkBytes(chunkIndex))
                let completedChunks = active.markAcked(chunkIndex: chunkIndex)
                try reportProgressOutsideLock(
                    manifest: active.manifest,
                    completedChunks: completedChunks,
                    sentChunks: active.nextIndex
                )
            }
        } else if controlSendInFlightGeneration != nil || activeSends.values.contains(where: { $0.sendInFlight }) {
            earlyTimeoutMessageIds.remove(messageId)
            if earlyAckMessageIds.count >= 128, let oldest = earlyAckMessageIds.first {
                earlyAckMessageIds.remove(oldest)
            }
            earlyAckMessageIds.insert(messageId)
        }
        _ = pumpControlLocked()
        try pumpAllLocked()
    }

    /// ACK timeout aborts the affected transfer and releases sender resources.
    public func handleAckTimeout(_ messageId: String) {
        stateLock.lock()
        defer { stateLock.unlock() }
        if frozen { return }
        if let control = outstandingControls.removeValue(forKey: messageId) {
            if control.sendAttempts < Self.maxControlSendAttempts {
                pendingControls.insert(control, at: 0)
            }
        } else if let fileId = activeSends.first(where: {
            $0.value.manifestMessageId == messageId || $0.value.outstanding[messageId] != nil
        })?.key {
            abortActiveSendLocked(fileId: fileId)
        } else if controlSendInFlightGeneration != nil || activeSends.values.contains(where: { $0.sendInFlight }) {
            earlyAckMessageIds.remove(messageId)
            if earlyTimeoutMessageIds.count >= 128, let oldest = earlyTimeoutMessageIds.first {
                earlyTimeoutMessageIds.remove(oldest)
            }
            earlyTimeoutMessageIds.insert(messageId)
        }
        _ = pumpControlLocked()
        try? pumpAllLocked()
    }

    @discardableResult
    public func cancel(fileId: String, discard: Bool = false) throws -> String? {
        stateLock.lock()
        defer { stateLock.unlock() }
        discardControlsLocked(fileId: fileId)
        activeSends.removeValue(forKey: fileId)?.closeStream()
        registered.removeValue(forKey: fileId)
        checkpointStore?.delete(fileId: fileId)
        recordTombstone(fileId: fileId, outcome: .cancelled)
        return enqueueControlLocked(
            type: TransferProtocol.typeCancel,
            payload: try TransferCancel(fileId: fileId, discard: discard).payload
        )
    }

    /// Apply a peer-originated cancel locally without echoing another control.
    @discardableResult
    public func handleRemoteCancel(_ cancel: TransferCancel) -> Bool {
        stateLock.lock()
        defer { stateLock.unlock() }
        guard registered[cancel.fileId] != nil || activeSends[cancel.fileId] != nil else {
            return false
        }
        discardControlsLocked(fileId: cancel.fileId)
        activeSends.removeValue(forKey: cancel.fileId)?.closeStream()
        registered.removeValue(forKey: cancel.fileId)
        checkpointStore?.delete(fileId: cancel.fileId)
        recordTombstone(fileId: cancel.fileId, outcome: .cancelled)
        return true
    }

    private func discardControlsLocked(fileId: String) {
        let suffix = ":\(fileId)"
        pendingControls.removeAll { $0.key.hasSuffix(suffix) }
        outstandingControls = outstandingControls.filter { !$0.value.key.hasSuffix(suffix) }
    }

    public func containsTransfer(fileId: String) -> Bool {
        stateLock.lock()
        defer { stateLock.unlock() }
        return registered[fileId] != nil || activeSends[fileId] != nil
    }

    public func hasInFlightOutboundTransfers() -> Bool {
        stateLock.lock()
        defer { stateLock.unlock() }
        return activeSends.values.contains { active in
            active.manifestPending ||
                active.manifestMessageId != nil ||
                active.nextIndex < active.manifest.chunkCount ||
                !active.outstanding.isEmpty
        }
    }

    /// Clears in-flight outbound state when the transport session ends.
    public func recycleSession() {
        stateLock.lock()
        defer { stateLock.unlock() }
        frozen = true
        activeSends.values.forEach { $0.closeStream() }
        activeSends.removeAll()
        registered.removeAll()
        pendingControls.removeAll()
        outstandingControls.removeAll()
    }

    @discardableResult
    public func requestResume(fileId: String) throws -> String? {
        stateLock.lock()
        defer { stateLock.unlock() }
        return enqueueControlLocked(
            type: TransferProtocol.typeResumeRequest,
            payload: try TransferResumeRequest(fileId: fileId).payload
        )
    }

    @discardableResult
    public func sendResumeInfo(_ info: TransferResumeInfo) -> String? {
        stateLock.lock()
        defer { stateLock.unlock() }
        return enqueueControlLocked(
            type: TransferProtocol.typeResumeInfo,
            payload: info.payload
        )
    }

    @discardableResult
    public func sendCancel(_ cancel: TransferCancel) -> String? {
        stateLock.lock()
        defer { stateLock.unlock() }
        return enqueueControlLocked(
            type: TransferProtocol.typeCancel,
            payload: cancel.payload
        )
    }

    private func enqueueControlLocked(type: String, payload: [String: Any]) -> String? {
        let fileId = payload["fileId"] as? String ?? "unknown"
        nextControlGeneration += 1
        let message = ControlMessage(
            type: type,
            payload: payload,
            key: "\(type):\(fileId)",
            generation: nextControlGeneration
        )
        if let existing = pendingControls.firstIndex(where: { $0.key == message.key }) {
            pendingControls[existing] = message
            return pumpControlLocked(targetKey: message.key)
        }
        guard pendingControls.count + outstandingControls.count < Self.maxPendingControls else {
            return nil
        }
        pendingControls.append(message)
        return pumpControlLocked(targetKey: message.key)
    }

    private func pumpControlLocked(targetKey: String? = nil) -> String? {
        guard !frozen, controlSendInFlightGeneration == nil, let next = pendingControls.first else {
            return nil
        }
        controlSendInFlightGeneration = next.generation
        let messageId = sendOutsideLock(type: next.type, payload: next.payload)
        controlSendInFlightGeneration = nil

        guard pendingControls.first?.generation == next.generation else {
            if let messageId {
                earlyAckMessageIds.remove(messageId)
                earlyTimeoutMessageIds.remove(messageId)
            }
            _ = pumpControlLocked()
            return nil
        }
        guard let messageId else { return nil }
        pendingControls.removeFirst()
        let sent = ControlMessage(
            type: next.type,
            payload: next.payload,
            key: next.key,
            sendAttempts: next.sendAttempts + 1,
            generation: next.generation
        )
        if earlyTimeoutMessageIds.remove(messageId) != nil {
            if sent.sendAttempts < Self.maxControlSendAttempts {
                pendingControls.insert(sent, at: 0)
            }
        } else if earlyAckMessageIds.remove(messageId) == nil {
            outstandingControls[messageId] = sent
        }
        return next.key == targetKey ? messageId : nil
    }

    private static let maxPendingControls = 64
    private static let maxControlSendAttempts = 3

    private func pumpChunksLocked(fileId: String) throws {
        guard !frozen, let active = activeSends[fileId], !active.sendInFlight else { return }
        active.sendInFlight = true
        defer {
            if activeSends[fileId] === active {
                active.sendInFlight = false
            }
            if !activeSends.values.contains(where: { $0.sendInFlight }) {
                earlyAckMessageIds.removeAll()
                earlyTimeoutMessageIds.removeAll()
            }
        }
        if active.manifestPending {
            let messageId = sendOutsideLock(
                type: TransferProtocol.typeManifest,
                payload: active.manifest.payload
            )
            guard activeSends[fileId] === active, !frozen, !isTerminal(fileId: fileId) else { return }
            guard let messageId else { return }
            active.manifestPending = false
            active.manifestMessageId = messageId
            if earlyTimeoutMessageIds.remove(messageId) != nil {
                abortActiveSendLocked(fileId: fileId)
                return
            }
            if earlyAckMessageIds.remove(messageId) != nil {
                active.manifestMessageId = nil
            } else {
                return
            }
        }
        if active.manifestMessageId != nil { return }
        while active.nextIndex < active.manifest.chunkCount {
            if active.outstanding.count >= min(outstandingWindow, limits.maxOutstandingMessages) { break }
            guard let stream = active.stream else { break }
            let index = active.nextIndex
            let chunkBytes = active.manifest.expectedChunkBytes(index)
            if active.outstandingBytes + Int64(chunkBytes) > limits.maxOutstandingBytes { break }
            let chunk: TransferChunk
            if let pending = active.pendingChunk {
                chunk = pending
            } else {
                chunk = try TransferChunk(
                    fileId: active.manifest.fileId,
                    chunkIndex: index,
                    data: try readNextChunk(stream, maxBytes: chunkBytes)
                )
                active.pendingChunk = chunk
            }
            let messageId = sendOutsideLock(type: TransferProtocol.typeChunk, payload: chunk.payload)
            guard activeSends[fileId] === active, !frozen, !isTerminal(fileId: fileId) else { return }
            guard let messageId else {
                break
            }
            active.pendingChunk = nil
            active.outstanding[messageId] = index
            active.outstandingBytes += Int64(chunkBytes)
            active.nextIndex += 1
            if earlyTimeoutMessageIds.remove(messageId) != nil {
                abortActiveSendLocked(fileId: fileId)
                return
            }
            if earlyAckMessageIds.remove(messageId) != nil,
               let acknowledgedIndex = active.outstanding.removeValue(forKey: messageId) {
                active.outstandingBytes -= Int64(active.manifest.expectedChunkBytes(acknowledgedIndex))
                let completedChunks = active.markAcked(chunkIndex: acknowledgedIndex)
                try reportProgressOutsideLock(
                    manifest: active.manifest,
                    completedChunks: completedChunks,
                    sentChunks: active.nextIndex
                )
                guard activeSends[fileId] === active, !frozen, !isTerminal(fileId: fileId) else { return }
            }
        }
        if active.nextIndex == active.manifest.chunkCount {
            active.closeStream()
            if active.outstanding.isEmpty, active.manifestMessageId == nil {
                activeSends.removeValue(forKey: fileId)
                registered.removeValue(forKey: fileId)
                checkpointStore?.delete(fileId: fileId)
                recordTombstone(fileId: fileId, outcome: .completed)
            }
        } else {
            try persistOutboundCheckpoint(
                manifest: active.manifest,
                source: registered[fileId]?.source,
                fromChunkIndex: active.nextIndex
            )
        }
    }

    /// Called only while the state lock is held exactly once. The in-flight
    /// marker keeps callbacks from starting a duplicate send while ProtocolSession
    /// performs its synchronous queue handoff.
    private func sendOutsideLock(type: String, payload: [String: Any]) -> String? {
        stateLock.unlock()
        defer { stateLock.lock() }
        return outbox.send(
            type: type,
            capability: TransferProtocol.capability,
            requiresAck: true,
            payload: payload
        )
    }

    private func pumpAllLocked() throws {
        for fileId in Array(activeSends.keys) {
            try pumpChunksLocked(fileId: fileId)
        }
    }

    private func abortActiveSendLocked(fileId: String) {
        discardControlsLocked(fileId: fileId)
        let active = activeSends.removeValue(forKey: fileId)
        active?.closeStream()
        registered.removeValue(forKey: fileId)
        checkpointStore?.delete(fileId: fileId)
        recordTombstone(fileId: fileId, outcome: .cancelled)
    }

    private func persistOutboundCheckpoint(
        manifest: TransferManifest,
        source: TransferByteSource?,
        fromChunkIndex: Int
    ) throws {
        guard let checkpointStore, let sessionBinding else { return }
        let sourcePath = (source as? FileTransferByteSource)?.fileURL.path
        if sourcePath == nil, fromChunkIndex > 0 {
            checkpointStore.delete(fileId: manifest.fileId)
            return
        }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        try checkpointStore.save(
            TransferCheckpointStore.Record(
                fileId: manifest.fileId,
                manifest: manifest,
                peerFingerprintHex: sessionBinding.peerFingerprint.hexLower(),
                sessionId: sessionBinding.sessionId,
                nextChunkIndex: fromChunkIndex,
                receivedRanges: outboundRanges(fromChunkIndex: fromChunkIndex, chunkCount: manifest.chunkCount),
                updatedAtMs: now,
                expiresAtMs: now + TransferCheckpointStore.defaultTTLMs,
                direction: .outbound,
                outboundSourcePath: sourcePath
            )
        )
    }

    private func restoreOutboundCheckpoints(peerFingerprint: Data) -> [TransferProgress] {
        guard let checkpointStore, sessionBinding != nil else { return [] }
        var restored: [TransferProgress] = []
        for record in checkpointStore.loadActive(for: peerFingerprint, direction: .outbound) {
            guard let path = record.outboundSourcePath,
                  FileManager.default.fileExists(atPath: path) else {
                checkpointStore.delete(fileId: record.fileId)
                continue
            }
            let source = FileTransferByteSource(fileURL: URL(fileURLWithPath: path))
            guard source.sizeBytes == record.manifest.sizeBytes else {
                checkpointStore.delete(fileId: record.fileId)
                continue
            }
            registered[record.fileId] = RegisteredTransfer(manifest: record.manifest, source: source)
            if let progress = try? TransferProgress(
                manifest: record.manifest,
                completedChunks: record.nextChunkIndex,
                sentChunks: record.nextChunkIndex
            ) {
                restored.append(progress)
            }
        }
        return restored
    }

    private func outboundRanges(fromChunkIndex: Int, chunkCount: Int) -> [Range<Int>] {
        guard fromChunkIndex > 0, fromChunkIndex < chunkCount else { return [] }
        return [0..<fromChunkIndex]
    }

    /// Builds progress from locked state, then invokes the external callback
    /// without the sender lock so callbacks can safely cross the session Seam.
    private func reportProgressOutsideLock(
        manifest: TransferManifest,
        completedChunks: Int,
        sentChunks: Int
    ) throws {
        let progress = try TransferProgress(
            manifest: manifest,
            completedChunks: completedChunks,
            sentChunks: sentChunks
        )
        stateLock.unlock()
        defer { stateLock.lock() }
        onProgress(progress)
    }

    private func isTerminal(fileId: String) -> Bool {
        tombstones[fileId] != nil
    }

    private func recordTombstone(fileId: String, outcome: TerminalOutcome) {
        tombstones[fileId] = outcome
        while tombstones.count > limits.maxCompletedEntries {
            if let oldest = tombstones.keys.first {
                tombstones.removeValue(forKey: oldest)
            }
        }
    }
}

private enum TerminalOutcome {
    case completed
    case cancelled
}

private struct ReceiverTombstone {
    let manifest: TransferManifest
    let outcome: TerminalOutcome
}

public struct TransferCompleted: Equatable {
    public let manifest: TransferManifest
    public let fileURL: URL
    public let sha256: String
}

public enum TransferEvent: Equatable {
    case completed(TransferCompleted)
    case resumeRequested(TransferResumeInfo)
    case cancelled(TransferCancel)
    case ignored
}

public final class TransferReceiver {
    private var states: [String: TransferState] = [:]
    private struct PendingDelivery {
        let completed: TransferCompleted
        let peerFingerprint: Data?
    }
    private var pendingDeliveries: [String: PendingDelivery] = [:]
    private var tombstones: [String: ReceiverTombstone] = [:]
    private let storage: TransferStorage
    private let limits: TransferResourceLimits
    private let checkpointStore: TransferCheckpointStore?
    private let onProgress: (TransferProgress) -> Void
    private let onRestored: (TransferProgress) -> Void
    private let onInboundPayloadSynced: (String) -> Void
    private var frozen = false
    private var sessionBinding: TransferSessionBinding?

    public init(
        storage: TransferStorage = FileTransferStorage(),
        limits: TransferResourceLimits = TransferResourceLimits(),
        checkpointStore: TransferCheckpointStore? = nil,
        onInboundPayloadSynced: @escaping (String) -> Void = { _ in },
        onRestored: ((TransferProgress) -> Void)? = nil,
        onProgress: @escaping (TransferProgress) -> Void = { _ in }
    ) {
        self.storage = storage
        self.limits = limits
        self.checkpointStore = checkpointStore
        self.onProgress = onProgress
        self.onRestored = onRestored ?? onProgress
        self.onInboundPayloadSynced = onInboundPayloadSynced
    }

    @discardableResult
    public func bindSession(peerFingerprint: Data, sessionId: String) -> [TransferCompleted] {
        // A new session re-arms a receiver that was frozen by recycleSession; the
        // receiver is a long-lived singleton, so without this it would reject every
        // transfer after the first disconnect.
        frozen = false
        sessionBinding = TransferSessionBinding(peerFingerprint: peerFingerprint, sessionId: sessionId)
        checkpointStore?.purgeExpired()
        reconcilePendingDeliveriesWithCheckpoints()
        restorePersistedInboundCheckpoints(peerFingerprint: peerFingerprint)
        for state in Array(states.values) {
            _ = try? completeIfReady(state)
        }
        return pendingDeliveries.values.compactMap {
            $0.peerFingerprint == peerFingerprint ? $0.completed : nil
        }
    }

    public func invalidatePeerCheckpoints(_ peerFingerprint: Data) {
        checkpointStore?.invalidatePeer(peerFingerprint)
        if sessionBinding?.peerFingerprint == peerFingerprint {
            let activeFileIds = Array(states.keys)
            states.values.forEach { $0.close() }
            states.removeAll()
            activeFileIds.forEach { storage.discard(fileId: $0) }
            tombstones.removeAll()
            sessionBinding = nil
        }
        let invalidated = pendingDeliveries.compactMap { fileId, pending in
            pending.peerFingerprint == peerFingerprint ? fileId : nil
        }
        for fileId in invalidated {
            pendingDeliveries.removeValue(forKey: fileId)
            storage.discard(fileId: fileId)
        }
    }

    public func invalidateAllCheckpoints() {
        checkpointStore?.invalidateAll()
        let fileIds = Set(states.keys).union(pendingDeliveries.keys)
        states.values.forEach { $0.close() }
        states.removeAll()
        pendingDeliveries.removeAll()
        tombstones.removeAll()
        fileIds.forEach { storage.discard(fileId: $0) }
        sessionBinding = nil
    }

    private func activeStagingBytes() -> Int64 {
        states.values.reduce(0) { $0 + $1.manifest.sizeBytes } +
            pendingDeliveries.values.reduce(0) { $0 + $1.completed.manifest.sizeBytes }
    }

    private func ensureReceiveQuota(manifest: TransferManifest) throws {
        if frozen {
            throw TransferError.invalidPayload("plugin/transfer-cancelled")
        }
        if states.count >= limits.maxConcurrentTransfers {
            throw TransferError.invalidPayload("plugin/transfer-quota-exceeded")
        }
        while !tombstones.isEmpty,
              tombstones.count + pendingDeliveries.count + states.count >= limits.maxCompletedEntries,
              let oldest = tombstones.keys.first {
            tombstones.removeValue(forKey: oldest)
        }
        if pendingDeliveries.count + states.count >= limits.maxCompletedEntries {
            throw TransferError.invalidPayload("plugin/transfer-quota-exceeded")
        }
        if activeStagingBytes() + manifest.sizeBytes > limits.maxStagingBytes {
            throw TransferError.invalidPayload("plugin/disk-full")
        }
    }

    public func checkpoint(fileId: String) -> TransferResumeInfo? {
        if let state = states[fileId] {
            return try? state.checkpoint()
        }
        if let pending = pendingDeliveries[fileId] {
            return try? TransferResumeInfo(
                fileId: fileId,
                nextChunkIndex: pending.completed.manifest.chunkCount
            )
        }
        guard let tombstone = tombstones[fileId] else { return nil }
        switch tombstone.outcome {
        case .completed:
            return try? TransferResumeInfo(fileId: fileId, nextChunkIndex: tombstone.manifest.chunkCount)
        case .cancelled:
            return nil
        }
    }

    public func pendingDelivery(fileId: String) -> TransferCompleted? {
        pendingDeliveries[fileId]?.completed
    }

    /// Cancel one inbound item locally; the controller separately writes the wire control.
    @discardableResult
    public func cancelLocal(fileId: String, discard: Bool = true) -> Bool {
        guard let cancel = try? TransferCancel(fileId: fileId, discard: discard) else {
            return false
        }
        return cancelReceive(cancel)
    }

    /// Drops active partial receives when the transport session ends.
    public func recycleSession() {
        frozen = true
        states.values.forEach { $0.close() }
        states.removeAll()
    }

    public func handle(_ envelope: Envelope) throws -> TransferEvent {
        guard envelope.capability == TransferProtocol.capability else { return .ignored }
        switch envelope.type {
        case TransferProtocol.typeManifest:
            let manifest = try TransferManifest(payload: envelope.payload)
            if let pending = pendingDeliveries[manifest.fileId] {
                guard pending.completed.manifest == manifest else {
                    throw TransferError.invalidPayload("manifest does not match pending delivery")
                }
                guard pending.peerFingerprint == nil ||
                        pending.peerFingerprint == sessionBinding?.peerFingerprint else {
                    throw TransferError.invalidPayload("pending delivery belongs to another peer")
                }
                return .completed(pending.completed)
            }
            if let existing = states[manifest.fileId] {
                guard existing.manifest == manifest else {
                    throw TransferError.invalidPayload("manifest does not match active transfer")
                }
                onProgress(try existing.progress())
                if let completed = try completeIfReady(existing) { return .completed(completed) }
                return .ignored
            }
            if let existing = tombstones[manifest.fileId] {
                guard existing.manifest == manifest else {
                    throw TransferError.invalidPayload("manifest does not match terminal transfer")
                }
                return .ignored
            }
            try ensureReceiveQuota(manifest: manifest)
            let state = try TransferState(manifest: manifest, fileURL: storage.prepare(manifest: manifest))
            states[manifest.fileId] = state
            try persistInboundCheckpoint(state)
            onProgress(try TransferProgress(manifest: manifest, completedChunks: 0))
            if let completed = try completeIfReady(state) { return .completed(completed) }
            return .ignored
        case TransferProtocol.typeChunk:
            let chunk = try TransferChunk(payload: envelope.payload)
            if frozen {
                throw TransferError.invalidPayload("plugin/transfer-cancelled")
            }
            guard let state = states[chunk.fileId] else {
                throw TransferError.invalidPayload("unknown fileId")
            }
            try state.accept(chunk)
            try state.synchronizeForCheckpoint()
            onInboundPayloadSynced(chunk.fileId)
            onProgress(try state.progress())
            try persistInboundCheckpoint(state)
            if let completed = try completeIfReady(state) { return .completed(completed) }
            return .ignored
        case TransferProtocol.typeResumeRequest:
            let request = try TransferResumeRequest(payload: envelope.payload)
            return .resumeRequested(
                try states[request.fileId]?.checkpoint()
                    ?? checkpoint(fileId: request.fileId)
                    ?? TransferResumeInfo(fileId: request.fileId, nextChunkIndex: 0, needsManifest: true)
            )
        case TransferProtocol.typeCancel:
            let cancel = try TransferCancel(payload: envelope.payload)
            _ = cancelReceive(cancel)
            return .cancelled(cancel)
        default:
            return .ignored
        }
    }

    private func completeIfReady(_ state: TransferState) throws -> TransferCompleted? {
        guard let fileURL = state.fileIfComplete() else { return nil }
        state.close()
        let size = ((try? FileManager.default.attributesOfItem(atPath: fileURL.path)[.size] as? NSNumber)?.int64Value) ?? -1
        guard size == state.manifest.sizeBytes else {
            throw TransferError.invalidPayload("size mismatch")
        }
        let digest = try sha256Hex(fileURL)
        guard digest == state.manifest.sha256 else {
            throw TransferError.invalidPayload("file sha256 mismatch")
        }
        states.removeValue(forKey: state.manifest.fileId)
        let done = TransferCompleted(manifest: state.manifest, fileURL: fileURL, sha256: digest)
        pendingDeliveries[state.manifest.fileId] = PendingDelivery(
            completed: done,
            peerFingerprint: sessionBinding?.peerFingerprint
        )
        return done
    }

    /// Commits the protocol tombstone only after user-visible publication succeeds.
    public func markDeliverySucceeded(fileId: String) {
        guard let completed = pendingDeliveries.removeValue(forKey: fileId)?.completed else { return }
        checkpointStore?.delete(fileId: fileId)
        recordTombstone(
            fileId: fileId,
            tombstone: ReceiverTombstone(manifest: completed.manifest, outcome: .completed)
        )
    }

    /// Keeps the verified source/checkpoint reachable for duplicate or restart retry.
    @discardableResult
    public func markDeliveryFailed(fileId: String) -> Bool {
        pendingDeliveries[fileId] != nil
    }

    private func cancelReceive(_ cancel: TransferCancel) -> Bool {
        let checkpointManifest = checkpointStore?.load(fileId: cancel.fileId)?.manifest
        let state = states.removeValue(forKey: cancel.fileId)
        let pending = cancel.discard ? pendingDeliveries.removeValue(forKey: cancel.fileId) : nil
        state?.close()
        if cancel.discard {
            storage.discard(fileId: cancel.fileId)
            checkpointStore?.delete(fileId: cancel.fileId)
        }
        guard let manifest = state?.manifest ?? pending?.completed.manifest ?? checkpointManifest else {
            return false
        }
        recordTombstone(
            fileId: cancel.fileId,
            tombstone: ReceiverTombstone(manifest: manifest, outcome: .cancelled)
        )
        return true
    }

    private func reconcilePendingDeliveriesWithCheckpoints() {
        guard let checkpointStore else { return }
        let expired = pendingDeliveries.compactMap { fileId, pending in
            pending.peerFingerprint != nil && checkpointStore.load(fileId: fileId) == nil
                ? fileId
                : nil
        }
        for fileId in expired {
            pendingDeliveries.removeValue(forKey: fileId)
            storage.discard(fileId: fileId)
        }
    }

    private func recordTombstone(fileId: String, tombstone: ReceiverTombstone) {
        tombstones[fileId] = tombstone
        while tombstones.count > limits.maxCompletedEntries {
            if let oldest = tombstones.keys.first {
                tombstones.removeValue(forKey: oldest)
            }
        }
    }

    private func persistInboundCheckpoint(_ state: TransferState) throws {
        guard let checkpointStore, let sessionBinding else { return }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        try checkpointStore.save(
            TransferCheckpointStore.Record(
                fileId: state.manifest.fileId,
                manifest: state.manifest,
                peerFingerprintHex: sessionBinding.peerFingerprint.hexLower(),
                sessionId: sessionBinding.sessionId,
                nextChunkIndex: try state.checkpoint().nextChunkIndex,
                // Persist only the contiguous prefix (O(1)); resume drives from
                // nextChunkIndex, so storing the full received bitmap here would
                // re-introduce an O(n) scan on every chunk.
                receivedRanges: state.contiguousReceivedRanges(),
                updatedAtMs: now,
                expiresAtMs: now + TransferCheckpointStore.defaultTTLMs
            )
        )
    }

    private func restorePersistedInboundCheckpoints(peerFingerprint: Data) {
        guard let checkpointStore else { return }
        let fileStorage = storage as? FileTransferStorage
        for record in checkpointStore.loadActive(for: peerFingerprint, direction: .inbound) {
            if states[record.fileId] != nil ||
                pendingDeliveries[record.fileId] != nil ||
                tombstones[record.fileId] != nil {
                continue
            }
            guard let partURL = fileStorage?.partFile(fileId: record.fileId),
                  FileManager.default.fileExists(atPath: partURL.path) else {
                checkpointStore.delete(fileId: record.fileId)
                continue
            }
            // Fail closed: restored checkpoints must respect the same concurrency
            // and staging-byte ceilings as live manifests. Over-quota records stay
            // on disk for a later session or TTL purge rather than reloading.
            if states.count >= limits.maxConcurrentTransfers { continue }
            if activeStagingBytes() + record.manifest.sizeBytes > limits.maxStagingBytes { continue }
            do {
                let state = try TransferState.restore(
                    manifest: record.manifest,
                    fileURL: partURL,
                    ranges: record.receivedRanges
                )
                states[record.fileId] = state
                onRestored(try state.progress())
            } catch {
                checkpointStore.delete(fileId: record.fileId)
                storage.discard(fileId: record.fileId)
            }
        }
    }
}

private final class TransferState {
    let manifest: TransferManifest
    private let fileURL: URL
    private var received: [Bool]
    private var handle: FileHandle?
    private(set) var receivedCount = 0
    private(set) var highestContiguousIndex = -1
    private(set) var receivedBytes: Int64 = 0

    init(manifest: TransferManifest, fileURL: URL) throws {
        self.manifest = manifest
        self.fileURL = fileURL
        self.received = Array(repeating: false, count: manifest.chunkCount)
    }

    static func restore(
        manifest: TransferManifest,
        fileURL: URL,
        ranges: [Range<Int>]
    ) throws -> TransferState {
        var received = Array(repeating: false, count: manifest.chunkCount)
        for range in ranges {
            for index in range {
                guard received.indices.contains(index) else {
                    throw TransferError.invalidPayload("received range out of bounds")
                }
                received[index] = true
            }
        }
        let state = try TransferState(manifest: manifest, fileURL: fileURL)
        state.received = received
        state.recomputeDerivedState()
        return state
    }

    func receivedRanges() -> [Range<Int>] {
        compactReceivedRanges(received)
    }

    /// O(1) contiguous-prefix range used for durable checkpoints.
    func contiguousReceivedRanges() -> [Range<Int>] {
        highestContiguousIndex < 0 ? [] : [0..<(highestContiguousIndex + 1)]
    }

    private func recomputeDerivedState() {
        receivedCount = 0
        receivedBytes = 0
        highestContiguousIndex = -1
        for index in received.indices where received[index] {
            receivedCount += 1
            receivedBytes += Int64(manifest.expectedChunkBytes(index))
        }
        var next = 0
        while next < received.count, received[next] {
            highestContiguousIndex = next
            next += 1
        }
    }

    deinit {
        close()
    }

    func accept(_ chunk: TransferChunk) throws {
        guard chunk.chunkIndex < received.count else {
            throw TransferError.invalidPayload("chunkIndex out of range")
        }
        guard chunk.data.count == manifest.expectedChunkBytes(chunk.chunkIndex) else {
            throw TransferError.invalidPayload("chunk size mismatch")
        }
        if received[chunk.chunkIndex] { return }
        let writer = try openHandle()
        try writer.seek(toOffset: UInt64(Int64(chunk.chunkIndex) * Int64(manifest.chunkSizeBytes)))
        try writer.write(contentsOf: chunk.data)
        received[chunk.chunkIndex] = true
        receivedCount += 1
        receivedBytes += Int64(chunk.data.count)
        if chunk.chunkIndex == highestContiguousIndex + 1 {
            highestContiguousIndex = chunk.chunkIndex
            while highestContiguousIndex + 1 < received.count, received[highestContiguousIndex + 1] {
                highestContiguousIndex += 1
            }
        }
    }

    func synchronizeForCheckpoint() throws {
        try handle?.synchronize()
    }

    /// Releases the long-lived write handle so the file can be read back (final
    /// whole-file SHA-256) and the descriptor is not leaked.
    func close() {
        try? handle?.close()
        handle = nil
    }

    private func openHandle() throws -> FileHandle {
        if let handle { return handle }
        let opened = try FileHandle(forWritingTo: fileURL)
        handle = opened
        return opened
    }

    func fileIfComplete() -> URL? {
        receivedCount == manifest.chunkCount ? fileURL : nil
    }

    func checkpoint() throws -> TransferResumeInfo {
        try TransferResumeInfo(fileId: manifest.fileId, nextChunkIndex: highestContiguousIndex + 1)
    }

    func progress() throws -> TransferProgress {
        try TransferProgress(
            manifest: manifest,
            completedChunks: highestContiguousIndex + 1,
            receivedChunks: receivedCount
        )
    }
}

private func compactReceivedRanges(_ received: [Bool]) -> [Range<Int>] {
    var ranges: [Range<Int>] = []
    var index = 0
    while index < received.count {
        guard received[index] else {
            index += 1
            continue
        }
        let start = index
        while index < received.count, received[index] {
            index += 1
        }
        ranges.append(start..<index)
    }
    return ranges
}

private extension Data {
    func hexLower() -> String {
        map { String(format: "%02x", $0) }.joined()
    }
}

private extension TransferManifest {
    func expectedChunkBytes(_ chunkIndex: Int) -> Int {
        precondition((0..<chunkCount).contains(chunkIndex), "chunkIndex out of range")
        let offset = Int64(chunkIndex) * Int64(chunkSizeBytes)
        return Int(min(Int64(chunkSizeBytes), sizeBytes - offset))
    }
}

private func isValidFileId(_ value: String) -> Bool {
    value.range(of: #"^f_[A-Za-z0-9_-]{8,128}$"#, options: .regularExpression) != nil
}

private func string(_ payload: [String: Any], _ field: String) throws -> String {
    guard let value = payload[field] as? String else {
        throw TransferError.invalidPayload("\(field) must be string")
    }
    return value
}

private func int64(_ payload: [String: Any], _ field: String) throws -> Int64 {
    guard let value = payload[field], !isBoolean(value) else {
        throw TransferError.invalidPayload("\(field) must be integer")
    }
    if let int = value as? Int { return Int64(int) }
    if let int64 = value as? Int64 { return int64 }
    if let number = value as? NSNumber { return try exactInt64(number, field: field) }
    throw TransferError.invalidPayload("\(field) must be integer")
}

private func optionalBool(_ payload: [String: Any], _ field: String) throws -> Bool? {
    guard let value = payload[field] else { return nil }
    guard let bool = value as? Bool else {
        throw TransferError.invalidPayload("\(field) must be boolean")
    }
    return bool
}

private func isBoolean(_ value: Any) -> Bool {
    CFGetTypeID(value as CFTypeRef) == CFBooleanGetTypeID()
}

private func exactInt64(_ number: NSNumber, field: String) throws -> Int64 {
    let type = String(cString: number.objCType)
    if ["c", "s", "i", "l", "q"].contains(type) {
        return number.int64Value
    }
    if ["C", "S", "I", "L", "Q"].contains(type) {
        guard number.uint64Value <= UInt64(Int64.max) else {
            throw TransferError.invalidPayload("\(field) must be integer")
        }
        return number.int64Value
    }
    let value = number.doubleValue
    guard value.isFinite,
          value.rounded(.towardZero) == value,
          value >= Double(Int64.min),
          value <= Double(Int64.max)
    else {
        throw TransferError.invalidPayload("\(field) must be integer")
    }
    return number.int64Value
}

private func sha256Hex(_ data: Data) -> String {
    SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
}

private func sha256Hex(_ url: URL) throws -> String {
    guard let stream = InputStream(url: url) else {
        throw TransferError.io("could not open file")
    }
    return try sha256HexFromStream(stream)
}

private func sha256HexFromStream(_ stream: InputStream) throws -> String {
    var hasher = SHA256()
    stream.open()
    defer { stream.close() }
    var buffer = [UInt8](repeating: 0, count: 64 * 1024)
    while true {
        let read = stream.read(&buffer, maxLength: buffer.count)
        if read < 0 {
            throw TransferError.io(stream.streamError?.localizedDescription ?? "read failed")
        }
        if read == 0 { break }
        hasher.update(data: Data(buffer.prefix(read)))
    }
    return hasher.finalize().map { String(format: "%02x", $0) }.joined()
}

private func skipFully(_ stream: InputStream, bytes: Int64) throws {
    var remaining = bytes
    var scratch = [UInt8](repeating: 0, count: min(8192, max(1, Int(min(Int64(Int.max), bytes)))))
    while remaining > 0 {
        let readSize = min(scratch.count, Int(remaining))
        let read = stream.read(&scratch, maxLength: readSize)
        if read < 0 {
            throw TransferError.io(stream.streamError?.localizedDescription ?? "skip failed")
        }
        if read == 0 {
            throw TransferError.invalidPayload("source ended before requested chunk")
        }
        remaining -= Int64(read)
    }
}

private func readNextChunk(_ stream: InputStream, maxBytes: Int) throws -> Data {
    var data = Data()
    var remaining = maxBytes
    var buffer = [UInt8](repeating: 0, count: min(8192, max(1, maxBytes)))
    while remaining > 0 {
        let read = stream.read(&buffer, maxLength: min(buffer.count, remaining))
        if read < 0 {
            throw TransferError.io(stream.streamError?.localizedDescription ?? "read failed")
        }
        if read == 0 { break }
        data.append(buffer, count: read)
        remaining -= read
    }
    return data
}
