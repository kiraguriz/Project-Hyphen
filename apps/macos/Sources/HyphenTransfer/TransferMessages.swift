import CryptoKit
import Foundation
import HyphenTransport

public enum TransferProtocol {
    public static let capability = "transfer.v1"
    public static let typeManifest = "transfer.manifest"
    public static let typeChunk = "transfer.chunk"
    public static let typeResumeRequest = "transfer.resume.request"
    public static let typeResumeInfo = "transfer.resume.info"
    public static let minChunkSizeBytes = 1024
    public static let maxChunkSizeBytes = 2 * 1024 * 1024
}

public enum TransferError: Error, Equatable {
    case invalidPayload(String)
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
        bytes: Data,
        chunkSizeBytes: Int,
        fileId: String = "f_\(Ulid.generate())"
    ) throws {
        try self.init(
            fileId: fileId,
            filename: filename,
            sizeBytes: Int64(bytes.count),
            mimeType: mimeType,
            sha256: sha256Hex(bytes),
            chunkSizeBytes: chunkSizeBytes,
            chunkCount: bytes.isEmpty ? 0 : (bytes.count + chunkSizeBytes - 1) / chunkSizeBytes
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

    public init(outbox: TransferOutbox) {
        self.outbox = outbox
    }

    @discardableResult
    public func sendBytes(
        filename: String,
        mimeType: String,
        bytes: Data,
        chunkSizeBytes: Int,
        fileId: String = "f_\(Ulid.generate())"
    ) throws -> TransferManifest {
        let manifest = try TransferManifest(
            filename: filename,
            mimeType: mimeType,
            bytes: bytes,
            chunkSizeBytes: chunkSizeBytes,
            fileId: fileId
        )
        outbox.send(
            type: TransferProtocol.typeManifest,
            capability: TransferProtocol.capability,
            requiresAck: true,
            payload: manifest.payload
        )
        try sendRemainingBytes(manifest: manifest, bytes: bytes, fromChunkIndex: 0)
        return manifest
    }

    public func sendRemainingBytes(
        manifest: TransferManifest,
        bytes: Data,
        fromChunkIndex: Int
    ) throws {
        guard (0...manifest.chunkCount).contains(fromChunkIndex) else {
            throw TransferError.invalidPayload("fromChunkIndex out of range")
        }
        guard Int64(bytes.count) == manifest.sizeBytes else {
            throw TransferError.invalidPayload("size mismatch")
        }
        guard sha256Hex(bytes) == manifest.sha256 else {
            throw TransferError.invalidPayload("file sha256 mismatch")
        }
        for index in fromChunkIndex..<manifest.chunkCount {
            let start = index * manifest.chunkSizeBytes
            let end = min(start + manifest.chunkSizeBytes, bytes.count)
            let chunk = try TransferChunk(
                fileId: manifest.fileId,
                chunkIndex: index,
                data: bytes.subdata(in: start..<end)
            )
            outbox.send(
                type: TransferProtocol.typeChunk,
                capability: TransferProtocol.capability,
                requiresAck: true,
                payload: chunk.payload
            )
        }
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
}

public struct TransferCompleted: Equatable {
    public let manifest: TransferManifest
    public let bytes: Data
}

public final class TransferReceiver {
    private var states: [String: TransferState] = [:]

    public init() {}

    public func checkpoint(fileId: String) -> TransferResumeInfo? {
        try? states[fileId]?.checkpoint()
    }

    public func handle(_ envelope: Envelope) throws -> TransferCompleted? {
        guard envelope.capability == TransferProtocol.capability else { return nil }
        switch envelope.type {
        case TransferProtocol.typeManifest:
            let manifest = try TransferManifest(payload: envelope.payload)
            let state = TransferState(manifest: manifest)
            states[manifest.fileId] = state
            return try completeIfReady(state)
        case TransferProtocol.typeChunk:
            let chunk = try TransferChunk(payload: envelope.payload)
            guard let state = states[chunk.fileId] else {
                throw TransferError.invalidPayload("unknown fileId")
            }
            try state.accept(chunk)
            return try completeIfReady(state)
        default:
            return nil
        }
    }

    private func completeIfReady(_ state: TransferState) throws -> TransferCompleted? {
        guard let bytes = state.bytesIfComplete() else { return nil }
        guard Int64(bytes.count) == state.manifest.sizeBytes else {
            throw TransferError.invalidPayload("size mismatch")
        }
        guard sha256Hex(bytes) == state.manifest.sha256 else {
            throw TransferError.invalidPayload("file sha256 mismatch")
        }
        states.removeValue(forKey: state.manifest.fileId)
        return TransferCompleted(manifest: state.manifest, bytes: bytes)
    }
}

private final class TransferState {
    let manifest: TransferManifest
    private var chunks: [Data?]

    init(manifest: TransferManifest) {
        self.manifest = manifest
        self.chunks = Array(repeating: nil, count: manifest.chunkCount)
    }

    func accept(_ chunk: TransferChunk) throws {
        guard chunk.chunkIndex < chunks.count else {
            throw TransferError.invalidPayload("chunkIndex out of range")
        }
        chunks[chunk.chunkIndex] = chunk.data
    }

    func bytesIfComplete() -> Data? {
        var out = Data()
        for chunk in chunks {
            guard let chunk else { return nil }
            out.append(chunk)
        }
        return out
    }

    func checkpoint() throws -> TransferResumeInfo {
        var next = 0
        while next < chunks.count && chunks[next] != nil {
            next += 1
        }
        return try TransferResumeInfo(fileId: manifest.fileId, nextChunkIndex: next)
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
    guard let value = payload[field], !(value is Bool) else {
        throw TransferError.invalidPayload("\(field) must be integer")
    }
    if let int = value as? Int { return Int64(int) }
    if let int64 = value as? Int64 { return int64 }
    if let number = value as? NSNumber { return number.int64Value }
    throw TransferError.invalidPayload("\(field) must be integer")
}

private func sha256Hex(_ data: Data) -> String {
    SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
}
