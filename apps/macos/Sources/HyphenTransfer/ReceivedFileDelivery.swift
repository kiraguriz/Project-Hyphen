import Foundation
import Darwin
import CryptoKit

/// Delivers a verified receive without replacing a file the user already has.
/// A same-directory staging copy is fsynced and atomically published with a
/// no-replace hard link, so a final basename never exposes partial contents.
public final class ReceivedFileDelivery {
    private static let maxCollisionAttempts = 10_000
    private static let maxFilenameBytes = 255
    private static let maxExtensionBytes = 64
    private static let stagingNamePattern =
        #"^\.hyphen-[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-4[0-9A-Fa-f]{3}-[89ABab][0-9A-Fa-f]{3}-[0-9A-Fa-f]{12}\.part$"#

    private let destinationDirectory: URL
    private let fileManager: FileManager
    private let onSourceCleanupFailure: () -> Void
    private let syncDirectory: (URL) throws -> Void
    private let lock = NSLock()

    public convenience init(
        destinationDirectory: URL,
        fileManager: FileManager = .default,
        onSourceCleanupFailure: @escaping () -> Void = {}
    ) throws {
        try self.init(
            destinationDirectory: destinationDirectory,
            fileManager: fileManager,
            onSourceCleanupFailure: onSourceCleanupFailure,
            syncDirectory: Self.syncDirectoryContents
        )
    }

    init(
        destinationDirectory: URL,
        fileManager: FileManager = .default,
        onSourceCleanupFailure: @escaping () -> Void = {},
        syncDirectory: @escaping (URL) throws -> Void
    ) throws {
        self.destinationDirectory = destinationDirectory
        self.fileManager = fileManager
        self.onSourceCleanupFailure = onSourceCleanupFailure
        self.syncDirectory = syncDirectory
        try fileManager.createDirectory(at: destinationDirectory, withIntermediateDirectories: true)
        cleanupAgedStaging()
    }

    public func deliver(_ completed: TransferCompleted) throws -> URL {
        lock.lock()
        defer { lock.unlock() }

        let originalName = completed.manifest.filename
        guard (originalName as NSString).lastPathComponent == originalName,
              originalName != ".",
              originalName != ".." else {
            throw TransferError.invalidPayload("received filename must be a plain filename")
        }
        guard fileManager.fileExists(atPath: completed.fileURL.path) else {
            throw CocoaError(.fileNoSuchFile)
        }
        guard completed.sha256 == completed.manifest.sha256 else {
            throw TransferError.invalidPayload("verified receive digest does not match manifest")
        }

        let staged = destinationDirectory.appendingPathComponent(
            ".hyphen-\(UUID().uuidString).part",
            isDirectory: false
        )
        try fileManager.copyItem(at: completed.fileURL, to: staged)
        defer { try? fileManager.removeItem(at: staged) }
        try syncPreparedFile(staged)
        let stagedSize = try fileManager.attributesOfItem(atPath: staged.path)[.size] as? NSNumber
        guard stagedSize?.int64Value == completed.manifest.sizeBytes else {
            throw TransferError.invalidPayload("staged receive size does not match manifest")
        }
        guard try sha256Hex(staged) == completed.sha256 else {
            throw TransferError.invalidPayload("staged receive digest does not match verified transfer")
        }

        for collisionIndex in 0..<Self.maxCollisionAttempts {
            let candidate = destinationDirectory.appendingPathComponent(
                collisionSafeName(originalName, collisionIndex: collisionIndex),
                isDirectory: false
            )
            do {
                try Self.publishPreparedFile(from: staged, to: candidate)
                do {
                    // One directory fsync persists the intended namespace:
                    // final link present and hidden staging link absent.
                    try fileManager.removeItem(at: staged)
                    try syncDirectory(destinationDirectory)
                } catch {
                    // The final candidate already references the complete,
                    // verified inode. Keep it with the source/checkpoint; a
                    // retry recognizes it and retries only directory sync.
                    throw error
                }
                cleanupVerifiedSource(completed.fileURL)
                return candidate
            } catch {
                let cocoa = error as NSError
                if cocoa.domain == NSCocoaErrorDomain,
                   cocoa.code == CocoaError.fileWriteFileExists.rawValue {
                    if try matchesVerifiedCandidate(candidate, completed: completed) {
                        try syncDirectory(destinationDirectory)
                        cleanupVerifiedSource(completed.fileURL)
                        return candidate
                    }
                    continue
                }
                throw error
            }
        }
        throw CocoaError(.fileWriteUnknown)
    }

    private func syncPreparedFile(_ staged: URL) throws {
        let descriptor = Darwin.open(staged.path, O_RDONLY)
        guard descriptor >= 0 else { throw posixError(path: staged.path) }
        defer { Darwin.close(descriptor) }
        guard Darwin.fsync(descriptor) == 0 else { throw posixError(path: staged.path) }
    }

    private static func syncDirectoryContents(_ directory: URL) throws {
        let descriptor = Darwin.open(directory.path, O_RDONLY | O_DIRECTORY | O_CLOEXEC)
        guard descriptor >= 0 else {
            throw NSError(
                domain: NSPOSIXErrorDomain,
                code: Int(errno),
                userInfo: [NSFilePathErrorKey: directory.path]
            )
        }
        defer { Darwin.close(descriptor) }
        guard Darwin.fsync(descriptor) == 0 else {
            throw NSError(
                domain: NSPOSIXErrorDomain,
                code: Int(errno),
                userInfo: [NSFilePathErrorKey: directory.path]
            )
        }
    }

    private func sha256Hex(_ url: URL) throws -> String {
        guard let stream = InputStream(url: url) else {
            throw TransferError.io("could not open staged receive")
        }
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

    private func matchesVerifiedCandidate(
        _ candidate: URL,
        completed: TransferCompleted
    ) throws -> Bool {
        var metadata = stat()
        guard Darwin.lstat(candidate.path, &metadata) == 0,
              metadata.st_mode & S_IFMT == S_IFREG,
              metadata.st_size == completed.manifest.sizeBytes else {
            return false
        }
        return try sha256Hex(candidate) == completed.sha256
    }

    private func cleanupVerifiedSource(_ source: URL) {
        do {
            try fileManager.removeItem(at: source)
        } catch {
            onSourceCleanupFailure()
        }
    }

    private func cleanupAgedStaging() {
        let cutoff = Date(timeIntervalSinceNow: -24 * 60 * 60)
        guard let files = try? fileManager.contentsOfDirectory(
            at: destinationDirectory,
            includingPropertiesForKeys: [
                .contentModificationDateKey,
                .isRegularFileKey,
                .isSymbolicLinkKey,
            ],
            options: []
        ) else {
            return
        }
        for url in files where url.lastPathComponent.range(
            of: Self.stagingNamePattern,
            options: .regularExpression
        ) != nil {
            _ = OwnedFileCleanup.deleteIfAgedRegular(candidate: url, cutoff: cutoff)
        }
    }

    /// The final basename is installed in one atomic no-replace operation.
    /// Both names reference the already-complete inode until staging cleanup.
    static func publishPreparedFile(from staged: URL, to candidate: URL) throws {
        guard Darwin.link(staged.path, candidate.path) == 0 else {
            if errno == EEXIST { throw CocoaError(.fileWriteFileExists) }
            throw NSError(
                domain: NSPOSIXErrorDomain,
                code: Int(errno),
                userInfo: [NSFilePathErrorKey: candidate.path]
            )
        }
    }

    private func posixError(path: String) -> NSError {
        NSError(
            domain: NSPOSIXErrorDomain,
            code: Int(errno),
            userInfo: [NSFilePathErrorKey: path]
        )
    }

    private func collisionSafeName(_ original: String, collisionIndex: Int) -> String {
        let dot = original.lastIndex(of: ".").flatMap { $0 == original.startIndex ? nil : $0 }
        let rawStem = dot.map { String(original[..<$0]) } ?? original
        let rawExtension = dot.map { String(original[$0...]) } ?? ""
        let suffix = collisionIndex == 0 ? "" : " (\(collisionIndex))"
        let safeExtension = utf8Prefix(rawExtension, maxBytes: Self.maxExtensionBytes)
        let stemBudget = Self.maxFilenameBytes - suffix.utf8.count - safeExtension.utf8.count
        let safeStem = utf8Prefix(rawStem, maxBytes: stemBudget)
        return "\(safeStem)\(suffix)\(safeExtension)"
    }

    /// Keep every candidate below common Android/macOS NAME_MAX without
    /// splitting a Unicode scalar's UTF-8 encoding.
    private func utf8Prefix(_ value: String, maxBytes: Int) -> String {
        guard maxBytes > 0, value.utf8.count > maxBytes else { return maxBytes > 0 ? value : "" }
        let bytes = Array(value.utf8)
        var end = min(maxBytes, bytes.count)
        while end > 0 {
            if let prefix = String(bytes: bytes.prefix(end), encoding: .utf8) {
                return prefix
            }
            end -= 1
        }
        return ""
    }
}
