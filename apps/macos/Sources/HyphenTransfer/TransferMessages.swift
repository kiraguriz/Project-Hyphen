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
        guard !filename.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              filename.count <= 255,
              !filename.contains("/"),
              !filename.contains("\\")
        else {
            throw TransferError.invalidPayload("invalid filename")
        }
        guard sizeBytes >= 0 else { throw TransferError.invalidPayload("sizeBytes must be >= 0") }
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

    public init(payload: [String: Any]) throws {
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

    func readChunk(offset: Int64, maxBytes: Int) throws -> Data {
        let stream = try openStream()
        stream.open()
        defer { stream.close() }
        try skipFully(stream, bytes: offset)
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
}

public final class FileTransferByteSource: TransferByteSource {
    private let fileURL: URL

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
    func prepare(manifest: TransferManifest) throws -> URL
    func discard(fileId: String)
}

public final class FileTransferStorage: TransferStorage {
    private let root: URL

    public init(root: URL = FileManager.default.temporaryDirectory.appendingPathComponent("hyphen-transfer", isDirectory: true)) {
        self.root = root
    }

    public func prepare(manifest: TransferManifest) throws -> URL {
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true, attributes: nil)
        let url = root.appendingPathComponent("\(manifest.fileId).part")
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

    public init(fileId: String, nextChunkIndex: Int) throws {
        guard isValidFileId(fileId) else { throw TransferError.invalidPayload("invalid fileId") }
        guard nextChunkIndex >= 0 else { throw TransferError.invalidPayload("nextChunkIndex must be >= 0") }
        self.fileId = fileId
        self.nextChunkIndex = nextChunkIndex
    }

    public init(payload: [String: Any]) throws {
        try self.init(
            fileId: try string(payload, "fileId"),
            nextChunkIndex: Int(try int64(payload, "nextChunkIndex"))
        )
    }

    public var payload: [String: Any] {
        [
            "fileId": fileId,
            "nextChunkIndex": nextChunkIndex,
        ]
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
    public let completedChunks: Int
    public let totalChunks: Int
    public let completedBytes: Int64
    public let totalBytes: Int64

    public var isComplete: Bool {
        completedChunks == totalChunks
    }

    public init(manifest: TransferManifest, completedChunks: Int) throws {
        guard (0...manifest.chunkCount).contains(completedChunks) else {
            throw TransferError.invalidPayload("completedChunks out of range")
        }
        self.fileId = manifest.fileId
        self.filename = manifest.filename
        self.completedChunks = completedChunks
        self.totalChunks = manifest.chunkCount
        self.completedBytes = min(
            manifest.sizeBytes,
            Int64(completedChunks) * Int64(manifest.chunkSizeBytes)
        )
        self.totalBytes = manifest.sizeBytes
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

public final class TransferSender {
    private let outbox: TransferOutbox
    private let negotiatedCapabilities: SessionHandshake.NegotiatedCapabilities?
    private let onProgress: (TransferProgress) -> Void
    private struct RegisteredTransfer {
        let manifest: TransferManifest
        let source: TransferByteSource
    }
    private var registered: [String: RegisteredTransfer] = [:]

    public init(
        outbox: TransferOutbox,
        negotiatedCapabilities: SessionHandshake.NegotiatedCapabilities? = nil,
        onProgress: @escaping (TransferProgress) -> Void = { _ in }
    ) {
        self.outbox = outbox
        self.negotiatedCapabilities = negotiatedCapabilities
        self.onProgress = onProgress
    }

    @discardableResult
    public func sendSource(
        filename: String,
        mimeType: String,
        source: TransferByteSource,
        chunkSizeBytes: Int,
        fileId: String = "f_\(Ulid.generate())"
    ) throws -> TransferManifest {
        if negotiatedCapabilities?.contains(TransferProtocol.capability) == false {
            throw TransferError.invalidPayload("plugin/unsupported-capability")
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
        registered[manifest.fileId] = RegisteredTransfer(manifest: manifest, source: source)
        outbox.send(
            type: TransferProtocol.typeManifest,
            capability: TransferProtocol.capability,
            requiresAck: true,
            payload: manifest.payload
        )
        onProgress(try TransferProgress(manifest: manifest, completedChunks: 0))
        try sendRemaining(fileId: manifest.fileId, fromChunkIndex: 0)
        return manifest
    }

    public func sendRemainingBytes(
        fileId: String,
        fromChunkIndex: Int
    ) throws {
        try sendRemaining(fileId: fileId, fromChunkIndex: fromChunkIndex)
    }

    public func sendRemaining(
        fileId: String,
        fromChunkIndex: Int
    ) throws {
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
        guard try source.sha256Hex() == manifest.sha256 else {
            throw TransferError.invalidPayload("file sha256 mismatch")
        }
        for index in fromChunkIndex..<manifest.chunkCount {
            let start = Int64(index) * Int64(manifest.chunkSizeBytes)
            let chunk = try TransferChunk(
                fileId: manifest.fileId,
                chunkIndex: index,
                data: try source.readChunk(offset: start, maxBytes: manifest.expectedChunkBytes(index))
            )
            outbox.send(
                type: TransferProtocol.typeChunk,
                capability: TransferProtocol.capability,
                requiresAck: true,
                payload: chunk.payload
            )
            onProgress(try TransferProgress(manifest: manifest, completedChunks: index + 1))
        }
    }

    public func handleResumeInfo(_ info: TransferResumeInfo) throws {
        try sendRemaining(fileId: info.fileId, fromChunkIndex: info.nextChunkIndex)
    }

    @discardableResult
    public func requestResume(fileId: String) throws -> String? {
        try outbox.send(
            type: TransferProtocol.typeResumeRequest,
            capability: TransferProtocol.capability,
            requiresAck: true,
            payload: TransferResumeRequest(fileId: fileId).payload
        )
    }

    @discardableResult
    public func sendResumeInfo(_ info: TransferResumeInfo) -> String? {
        outbox.send(
            type: TransferProtocol.typeResumeInfo,
            capability: TransferProtocol.capability,
            requiresAck: true,
            payload: info.payload
        )
    }

    @discardableResult
    public func sendCancel(_ cancel: TransferCancel) -> String? {
        outbox.send(
            type: TransferProtocol.typeCancel,
            capability: TransferProtocol.capability,
            requiresAck: true,
            payload: cancel.payload
        )
    }
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
    private let storage: TransferStorage
    private let onProgress: (TransferProgress) -> Void

    public init(
        storage: TransferStorage = FileTransferStorage(),
        onProgress: @escaping (TransferProgress) -> Void = { _ in }
    ) {
        self.storage = storage
        self.onProgress = onProgress
    }

    public func checkpoint(fileId: String) -> TransferResumeInfo? {
        try? states[fileId]?.checkpoint()
    }

    public func handle(_ envelope: Envelope) throws -> TransferEvent {
        guard envelope.capability == TransferProtocol.capability else { return .ignored }
        switch envelope.type {
        case TransferProtocol.typeManifest:
            let manifest = try TransferManifest(payload: envelope.payload)
            let state = try TransferState(manifest: manifest, fileURL: storage.prepare(manifest: manifest))
            states[manifest.fileId] = state
            onProgress(try TransferProgress(manifest: manifest, completedChunks: 0))
            if let completed = try completeIfReady(state) { return .completed(completed) }
            return .ignored
        case TransferProtocol.typeChunk:
            let chunk = try TransferChunk(payload: envelope.payload)
            guard let state = states[chunk.fileId] else {
                throw TransferError.invalidPayload("unknown fileId")
            }
            try state.accept(chunk)
            onProgress(try state.progress())
            if let completed = try completeIfReady(state) { return .completed(completed) }
            return .ignored
        case TransferProtocol.typeResumeRequest:
            let request = try TransferResumeRequest(payload: envelope.payload)
            return .resumeRequested(try states[request.fileId]?.checkpoint() ?? TransferResumeInfo(fileId: request.fileId, nextChunkIndex: 0))
        case TransferProtocol.typeCancel:
            let cancel = try TransferCancel(payload: envelope.payload)
            if cancel.discard {
                states.removeValue(forKey: cancel.fileId)
                storage.discard(fileId: cancel.fileId)
            }
            return .cancelled(cancel)
        default:
            return .ignored
        }
    }

    private func completeIfReady(_ state: TransferState) throws -> TransferCompleted? {
        guard let fileURL = state.fileIfComplete() else { return nil }
        let size = ((try? FileManager.default.attributesOfItem(atPath: fileURL.path)[.size] as? NSNumber)?.int64Value) ?? -1
        guard size == state.manifest.sizeBytes else {
            throw TransferError.invalidPayload("size mismatch")
        }
        let digest = try sha256Hex(fileURL)
        guard digest == state.manifest.sha256 else {
            throw TransferError.invalidPayload("file sha256 mismatch")
        }
        states.removeValue(forKey: state.manifest.fileId)
        return TransferCompleted(manifest: state.manifest, fileURL: fileURL, sha256: digest)
    }
}

private final class TransferState {
    let manifest: TransferManifest
    private let fileURL: URL
    private var received: [Bool]

    init(manifest: TransferManifest, fileURL: URL) throws {
        self.manifest = manifest
        self.fileURL = fileURL
        self.received = Array(repeating: false, count: manifest.chunkCount)
    }

    func accept(_ chunk: TransferChunk) throws {
        guard chunk.chunkIndex < received.count else {
            throw TransferError.invalidPayload("chunkIndex out of range")
        }
        guard chunk.data.count == manifest.expectedChunkBytes(chunk.chunkIndex) else {
            throw TransferError.invalidPayload("chunk size mismatch")
        }
        let handle = try FileHandle(forWritingTo: fileURL)
        defer { try? handle.close() }
        try handle.seek(toOffset: UInt64(Int64(chunk.chunkIndex) * Int64(manifest.chunkSizeBytes)))
        try handle.write(contentsOf: chunk.data)
        received[chunk.chunkIndex] = true
    }

    func fileIfComplete() -> URL? {
        for chunk in received {
            guard chunk else { return nil }
        }
        return fileURL
    }

    func checkpoint() throws -> TransferResumeInfo {
        var next = 0
        while next < received.count && received[next] {
            next += 1
        }
        return try TransferResumeInfo(fileId: manifest.fileId, nextChunkIndex: next)
    }

    func progress() throws -> TransferProgress {
        var completed = 0
        while completed < received.count && received[completed] {
            completed += 1
        }
        return try TransferProgress(manifest: manifest, completedChunks: completed)
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
    if let number = value as? NSNumber { return number.int64Value }
    throw TransferError.invalidPayload("\(field) must be integer")
}

private func isBoolean(_ value: Any) -> Bool {
    CFGetTypeID(value as CFTypeRef) == CFBooleanGetTypeID()
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
