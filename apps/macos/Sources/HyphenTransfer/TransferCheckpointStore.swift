import Foundation

/// Peer-bound durable transfer checkpoints (dimension 02 P2). Stores manifest,
/// temp `.part` identity, contiguous resume index, compact received ranges,
/// session binding, and expiry under the app support directory. Process restart
/// can reload valid partial receives; `invalidatePeer` clears checkpoints on
/// trust revoke/reset.
public final class TransferCheckpointStore {

    public enum Direction: String {
        case inbound
        case outbound
    }

    public struct Record: Equatable {
        public let fileId: String
        public let manifest: TransferManifest
        public let peerFingerprintHex: String
        public let sessionId: String
        public let nextChunkIndex: Int
        public let receivedRanges: [Range<Int>]
        public let updatedAtMs: Int64
        public let expiresAtMs: Int64
        public let direction: Direction
        public let outboundSourcePath: String?

        public init(
            fileId: String,
            manifest: TransferManifest,
            peerFingerprintHex: String,
            sessionId: String,
            nextChunkIndex: Int,
            receivedRanges: [Range<Int>],
            updatedAtMs: Int64,
            expiresAtMs: Int64,
            direction: Direction = .inbound,
            outboundSourcePath: String? = nil
        ) {
            self.fileId = fileId
            self.manifest = manifest
            self.peerFingerprintHex = peerFingerprintHex
            self.sessionId = sessionId
            self.nextChunkIndex = nextChunkIndex
            self.receivedRanges = receivedRanges
            self.updatedAtMs = updatedAtMs
            self.expiresAtMs = expiresAtMs
            self.direction = direction
            self.outboundSourcePath = outboundSourcePath
        }
    }

    public static let defaultTTLMs: Int64 = 7 * 24 * 60 * 60 * 1000

    private let root: URL
    private let ttlMs: Int64
    private let nowMs: () -> Int64
    private let lock = NSLock()

    public init(
        root: URL = TransferCheckpointStore.defaultRoot(),
        ttlMs: Int64 = TransferCheckpointStore.defaultTTLMs,
        nowMs: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }
    ) {
        self.root = root
        self.ttlMs = ttlMs
        self.nowMs = nowMs
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    public static func defaultRoot() -> URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        return base.appendingPathComponent("Hyphen/transfer-checkpoints", isDirectory: true)
    }

    public func save(_ record: Record) throws {
        lock.lock()
        defer { lock.unlock() }
        let data = try JSONSerialization.data(withJSONObject: encode(record), options: [.sortedKeys])
        try data.write(to: fileURL(for: record.fileId), options: .atomic)
    }

    public func load(fileId: String) -> Record? {
        lock.lock()
        defer { lock.unlock() }
        guard let data = try? Data(contentsOf: fileURL(for: fileId)),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let record = try? decode(object) else {
            return nil
        }
        return isExpired(record) ? nil : record
    }

    public func loadActive(for peerFingerprint: Data, direction: Direction? = nil) -> [Record] {
        lock.lock()
        defer { lock.unlock() }
        let hex = peerFingerprint.hexLower()
        guard let files = try? FileManager.default.contentsOfDirectory(
            at: root,
            includingPropertiesForKeys: nil
        ) else {
            return []
        }
        return files.compactMap { url -> Record? in
            guard url.pathExtension == "json" else { return nil }
            guard let data = try? Data(contentsOf: url),
                  let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let record = try? decode(object) else {
                return nil
            }
            guard record.peerFingerprintHex == hex else { return nil }
            if let direction, record.direction != direction { return nil }
            return isExpired(record) ? nil : record
        }
    }

    public func delete(fileId: String) {
        lock.lock()
        defer { lock.unlock() }
        try? FileManager.default.removeItem(at: fileURL(for: fileId))
    }

    public func invalidatePeer(_ peerFingerprint: Data) {
        lock.lock()
        defer { lock.unlock() }
        let hex = peerFingerprint.hexLower()
        guard let files = try? FileManager.default.contentsOfDirectory(
            at: root,
            includingPropertiesForKeys: nil
        ) else {
            return
        }
        for url in files where url.pathExtension == "json" {
            guard let data = try? Data(contentsOf: url),
                  let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let record = try? decode(object),
                  record.peerFingerprintHex == hex else {
                continue
            }
            try? FileManager.default.removeItem(at: url)
        }
    }

    public func invalidateAll() {
        lock.lock()
        defer { lock.unlock() }
        guard let files = try? FileManager.default.contentsOfDirectory(
            at: root,
            includingPropertiesForKeys: nil
        ) else {
            return
        }
        for url in files where url.pathExtension == "json" {
            try? FileManager.default.removeItem(at: url)
        }
    }

    public func purgeExpired() {
        lock.lock()
        defer { lock.unlock() }
        guard let files = try? FileManager.default.contentsOfDirectory(
            at: root,
            includingPropertiesForKeys: nil
        ) else {
            return
        }
        for url in files where url.pathExtension == "json" {
            if let data = try? Data(contentsOf: url),
               let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
               let record = try? decode(object),
               !isExpired(record) {
                continue
            }
            try? FileManager.default.removeItem(at: url)
        }
    }

    private func fileURL(for fileId: String) -> URL {
        root.appendingPathComponent("\(fileId).json")
    }

    private func isExpired(_ record: Record) -> Bool {
        nowMs() >= record.expiresAtMs
    }

    private func encode(_ record: Record) -> [String: Any] {
        var payload: [String: Any] = [
            "version": 1,
            "fileId": record.fileId,
            "manifest": record.manifest.payload,
            "peerFingerprintHex": record.peerFingerprintHex,
            "sessionId": record.sessionId,
            "nextChunkIndex": record.nextChunkIndex,
            "receivedRanges": record.receivedRanges.map { [$0.lowerBound, $0.upperBound] },
            "updatedAtMs": record.updatedAtMs,
            "expiresAtMs": record.expiresAtMs,
            "direction": record.direction.rawValue,
        ]
        if let outboundSourcePath = record.outboundSourcePath {
            payload["outboundSourcePath"] = outboundSourcePath
        }
        return payload
    }

    private func decode(_ object: [String: Any]) throws -> Record {
        guard try checkpointInt(object, "version") == 1 else {
            throw TransferError.invalidPayload("unsupported checkpoint version")
        }
        let directionRaw = try checkpointString(object, "direction")
        let direction = Direction(rawValue: directionRaw) ?? .inbound
        let rangeArrays = object["receivedRanges"] as? [[Any]] ?? []
        let ranges = try rangeArrays.map { pair -> Range<Int> in
            guard pair.count == 2 else {
                throw TransferError.invalidPayload("invalid received range")
            }
            let start = try intValue(pair[0])
            let endExclusive = try intValue(pair[1])
            guard endExclusive > start else {
                throw TransferError.invalidPayload("invalid received range bounds")
            }
            return start..<endExclusive
        }
        let outboundSourcePath = object["outboundSourcePath"] as? String
        return Record(
            fileId: try checkpointString(object, "fileId"),
            manifest: try TransferManifest(payload: object["manifest"] as? [String: Any] ?? [:]),
            peerFingerprintHex: try checkpointString(object, "peerFingerprintHex"),
            sessionId: try checkpointString(object, "sessionId"),
            nextChunkIndex: try checkpointInt(object, "nextChunkIndex"),
            receivedRanges: ranges,
            updatedAtMs: try checkpointInt64(object, "updatedAtMs"),
            expiresAtMs: try checkpointInt64(object, "expiresAtMs"),
            direction: direction,
            outboundSourcePath: outboundSourcePath
        )
    }
}

private func checkpointString(_ payload: [String: Any], _ field: String) throws -> String {
    guard let value = payload[field] as? String else {
        throw TransferError.invalidPayload("\(field) must be string")
    }
    return value
}

private func checkpointInt(_ payload: [String: Any], _ field: String) throws -> Int {
    Int(try checkpointInt64(payload, field))
}

private func checkpointInt64(_ payload: [String: Any], _ field: String) throws -> Int64 {
    guard let value = payload[field] else {
        throw TransferError.invalidPayload("\(field) must be integer")
    }
    if let int = value as? Int { return Int64(int) }
    if let int64 = value as? Int64 { return int64 }
    if let number = value as? NSNumber { return number.int64Value }
    throw TransferError.invalidPayload("\(field) must be integer")
}

private extension Data {
    func hexLower() -> String {
        map { String(format: "%02x", $0) }.joined()
    }
}

private func intValue(_ value: Any) throws -> Int {
    if let int = value as? Int { return int }
    if let int64 = value as? Int64 { return Int(int64) }
    if let num = value as? NSNumber { return num.intValue }
    throw TransferError.invalidPayload("invalid integer")
}
