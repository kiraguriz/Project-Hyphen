import Foundation
import Darwin
import CryptoKit
import XCTest
@testable import HyphenTransfer

final class ReceivedFileDeliveryTests: XCTestCase {

    func testDeliveryPreservesAnExistingSameNameFile() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        let destination = root.appendingPathComponent("received", isDirectory: true)
        let source = root.appendingPathComponent("verified.part")
        try FileManager.default.createDirectory(at: destination, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }

        let existing = destination.appendingPathComponent("report.txt")
        try Data("keep me".utf8).write(to: existing)
        let payload = Data("new payload".utf8)
        try payload.write(to: source)
        let digest = SHA256.hash(data: payload).map { String(format: "%02x", $0) }.joined()
        let manifest = try TransferManifest(
            fileId: "f_delivery_test012345",
            filename: "report.txt",
            sizeBytes: 11,
            mimeType: "text/plain",
            sha256: digest,
            chunkSizeBytes: 1024,
            chunkCount: 1
        )
        let completed = TransferCompleted(
            manifest: manifest,
            fileURL: source,
            sha256: digest
        )

        let delivered = try ReceivedFileDelivery(destinationDirectory: destination).deliver(completed)

        XCTAssertEqual(try String(contentsOf: existing, encoding: .utf8), "keep me")
        XCTAssertEqual(delivered.lastPathComponent, "report (1).txt")
        XCTAssertEqual(try String(contentsOf: delivered, encoding: .utf8), "new payload")
        XCTAssertFalse(FileManager.default.fileExists(atPath: source.path))
    }

    func testDeliveryRejectsAVerifiedSourceMutatedBeforePublication() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        let destination = root.appendingPathComponent("received", isDirectory: true)
        let source = root.appendingPathComponent("mutated-source.part")
        try FileManager.default.createDirectory(at: destination, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let verified = Data("verified payload".utf8)
        try verified.write(to: source)
        let digest = SHA256.hash(data: verified).map { String(format: "%02x", $0) }.joined()
        let completed = TransferCompleted(
            manifest: try manifest(filename: "report.txt", sizeBytes: Int64(verified.count), sha256: digest),
            fileURL: source,
            sha256: digest
        )
        try Data("tampered payload".utf8).write(to: source)

        XCTAssertThrowsError(
            try ReceivedFileDelivery(destinationDirectory: destination).deliver(completed)
        )

        XCTAssertTrue(FileManager.default.fileExists(atPath: source.path))
        XCTAssertTrue(
            try FileManager.default.contentsOfDirectory(atPath: destination.path)
                .allSatisfy { $0.hasPrefix(".hyphen-") }
        )
    }

    func testDeliverySyncsFinalDirectoryBeforeDeletingVerifiedSource() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        let destination = root.appendingPathComponent("received", isDirectory: true)
        let source = root.appendingPathComponent("directory-sync-source.part")
        try FileManager.default.createDirectory(at: destination, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let verified = Data("verified payload".utf8)
        try verified.write(to: source)
        let digest = SHA256.hash(data: verified).map { String(format: "%02x", $0) }.joined()
        var directorySynced = false
        let delivery = try ReceivedFileDelivery(
            destinationDirectory: destination,
            syncDirectory: { directory in
                XCTAssertEqual(directory, destination)
                XCTAssertTrue(FileManager.default.fileExists(atPath: source.path))
                XCTAssertTrue(
                    FileManager.default.fileExists(
                        atPath: destination.appendingPathComponent("report.txt").path
                    )
                )
                XCTAssertTrue(
                    try FileManager.default.contentsOfDirectory(atPath: destination.path)
                        .allSatisfy { !$0.hasPrefix(".hyphen-") }
                )
                directorySynced = true
            }
        )

        let delivered = try delivery.deliver(
            TransferCompleted(
                manifest: try manifest(
                    filename: "report.txt",
                    sizeBytes: Int64(verified.count),
                    sha256: digest
                ),
                fileURL: source,
                sha256: digest
            )
        )

        XCTAssertTrue(directorySynced)
        XCTAssertEqual(try Data(contentsOf: delivered), verified)
        XCTAssertFalse(FileManager.default.fileExists(atPath: source.path))
    }

    func testDirectorySyncFailureRetainsVerifiedCandidateAndRetryResyncsOriginalName() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        let destination = root.appendingPathComponent("received", isDirectory: true)
        let source = root.appendingPathComponent("directory-sync-failure-source.part")
        try FileManager.default.createDirectory(at: destination, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let verified = Data("verified payload".utf8)
        try verified.write(to: source)
        let digest = SHA256.hash(data: verified).map { String(format: "%02x", $0) }.joined()
        var syncAttempts = 0
        let delivery = try ReceivedFileDelivery(
            destinationDirectory: destination,
            syncDirectory: { _ in
                syncAttempts += 1
                if syncAttempts == 1 { throw CocoaError(.fileWriteUnknown) }
            }
        )

        XCTAssertThrowsError(
            try delivery.deliver(
                TransferCompleted(
                    manifest: try manifest(
                        filename: "report.txt",
                        sizeBytes: Int64(verified.count),
                        sha256: digest
                    ),
                    fileURL: source,
                    sha256: digest
                )
            )
        )

        XCTAssertTrue(FileManager.default.fileExists(atPath: source.path))
        XCTAssertEqual(
            try Data(contentsOf: destination.appendingPathComponent("report.txt")),
            verified
        )

        let delivered = try delivery.deliver(
            TransferCompleted(
                manifest: try manifest(
                    filename: "report.txt",
                    sizeBytes: Int64(verified.count),
                    sha256: digest
                ),
                fileURL: source,
                sha256: digest
            )
        )

        XCTAssertEqual(delivered.lastPathComponent, "report.txt")
        XCTAssertEqual(try Data(contentsOf: delivered), verified)
        XCTAssertFalse(FileManager.default.fileExists(atPath: source.path))
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: destination.path), ["report.txt"])
    }

    func testConcurrentDeliveryInstancesNeverOverwriteTheSameCandidate() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        let destination = root.appendingPathComponent("received", isDirectory: true)
        try FileManager.default.createDirectory(at: destination, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }

        let payloads = ["first payload", "second payload"]
        let sources = try payloads.enumerated().map { index, payload -> URL in
            let source = root.appendingPathComponent("source-\(index).part")
            try Data(payload.utf8).write(to: source)
            return source
        }
        let group = DispatchGroup()
        let resultLock = NSLock()
        var results: [Result<URL, Error>] = []
        for source in sources {
            group.enter()
            DispatchQueue.global().async {
                defer { group.leave() }
                let result: Result<URL, Error> = Result {
                    let data = try Data(contentsOf: source)
                    let digest = SHA256.hash(data: data)
                        .map { String(format: "%02x", $0) }
                        .joined()
                    let manifest = try self.manifest(
                        filename: "report.txt",
                        sizeBytes: Int64(data.count),
                        sha256: digest
                    )
                    return try ReceivedFileDelivery(destinationDirectory: destination).deliver(
                        TransferCompleted(manifest: manifest, fileURL: source, sha256: digest)
                    )
                }
                resultLock.lock()
                results.append(result)
                resultLock.unlock()
            }
        }
        XCTAssertEqual(group.wait(timeout: .now() + 5), .success)
        let delivered = try results.map { try $0.get() }

        XCTAssertEqual(Set(delivered.map(\.lastPathComponent)), Set(["report.txt", "report (1).txt"]))
        XCTAssertEqual(
            Set(try delivered.map { try String(contentsOf: $0, encoding: .utf8) }),
            Set(payloads)
        )
    }

    func testDeliveryTruncatesUTF8NamesAndReservesCollisionSuffixBytes() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        let destination = root.appendingPathComponent("received", isDirectory: true)
        try FileManager.default.createDirectory(at: destination, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }

        let veryLongName = String(repeating: "😀", count: 255)
        let longSource = root.appendingPathComponent("long-name.part")
        let longPayload = Data("long payload".utf8)
        try longPayload.write(to: longSource)
        let longDigest = SHA256.hash(data: longPayload).map { String(format: "%02x", $0) }.joined()
        let longManifest = try manifest(
            filename: veryLongName,
            sizeBytes: Int64(longPayload.count),
            sha256: longDigest
        )

        let deliveredLong = try ReceivedFileDelivery(destinationDirectory: destination).deliver(
            TransferCompleted(manifest: longManifest, fileURL: longSource, sha256: longDigest)
        )

        XCTAssertTrue(FileManager.default.fileExists(atPath: deliveredLong.path))
        XCTAssertLessThanOrEqual(deliveredLong.lastPathComponent.utf8.count, 255)

        let collisionName = String(repeating: "😀", count: 62) + ".txt"
        let existing = destination.appendingPathComponent(collisionName)
        try Data("keep me".utf8).write(to: existing)
        let collisionSource = root.appendingPathComponent("collision-name.part")
        let collisionPayload = Data("new payload".utf8)
        try collisionPayload.write(to: collisionSource)
        let collisionDigest = SHA256.hash(data: collisionPayload)
            .map { String(format: "%02x", $0) }
            .joined()
        let collisionManifest = try manifest(
            filename: collisionName,
            sizeBytes: Int64(collisionPayload.count),
            sha256: collisionDigest
        )

        let deliveredCollision = try ReceivedFileDelivery(destinationDirectory: destination).deliver(
            TransferCompleted(
                manifest: collisionManifest,
                fileURL: collisionSource,
                sha256: collisionDigest
            )
        )

        XCTAssertEqual(try String(contentsOf: existing, encoding: .utf8), "keep me")
        XCTAssertTrue(deliveredCollision.lastPathComponent.hasSuffix(" (1).txt"))
        XCTAssertLessThanOrEqual(deliveredCollision.lastPathComponent.utf8.count, 255)
        XCTAssertEqual(try String(contentsOf: deliveredCollision, encoding: .utf8), "new payload")
    }

    func testPreparedFileIsAtomicallyPublishedByNoReplaceHardLink() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }

        let staged = root.appendingPathComponent(".hyphen-ready.part")
        let candidate = root.appendingPathComponent("report.txt")
        try Data("verified payload".utf8).write(to: staged)

        try ReceivedFileDelivery.publishPreparedFile(from: staged, to: candidate)

        XCTAssertEqual(try inode(of: staged), try inode(of: candidate))
        XCTAssertEqual(try String(contentsOf: candidate, encoding: .utf8), "verified payload")

        let competing = root.appendingPathComponent("competing.part")
        try Data("different".utf8).write(to: competing)
        XCTAssertThrowsError(
            try ReceivedFileDelivery.publishPreparedFile(from: competing, to: candidate)
        )
        XCTAssertEqual(try String(contentsOf: candidate, encoding: .utf8), "verified payload")
    }

    func testCleanupNeverTruncatesAPublishedFileSharingTheStagingInode() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let staged = root.appendingPathComponent(
            ".hyphen-923E4567-E89B-42D3-8456-426614174000.part"
        )
        let delivered = root.appendingPathComponent("report.txt")
        try Data("published payload".utf8).write(to: staged)
        try ReceivedFileDelivery.publishPreparedFile(from: staged, to: delivered)
        try FileManager.default.setAttributes(
            [.modificationDate: Date(timeIntervalSince1970: 1)],
            ofItemAtPath: staged.path
        )

        _ = try ReceivedFileDelivery(destinationDirectory: root)

        XCTAssertEqual(try String(contentsOf: delivered, encoding: .utf8), "published payload")
        XCTAssertTrue(FileManager.default.fileExists(atPath: staged.path))
        XCTAssertEqual(try inode(of: staged), try inode(of: delivered))
    }

    func testDeliveryStartupScavengesAgedPublicationStagingOnly() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }

        let aged = root.appendingPathComponent(".hyphen-123E4567-E89B-42D3-A456-426614174000.part")
        let fresh = root.appendingPathComponent(".hyphen-223E4567-E89B-42D3-B456-426614174000.part")
        let versionOneLookalike = root.appendingPathComponent(
            ".hyphen-323E4567-E89B-12D3-A456-426614174000.part"
        )
        let lookalike = root.appendingPathComponent(".hyphen-user.part")
        let lookalikeDirectory = root.appendingPathComponent(
            ".hyphen-423E4567-E89B-42D3-8456-426614174000.part",
            isDirectory: true
        )
        let unrelated = root.appendingPathComponent("keep.part")
        try Data("partial".utf8).write(to: aged)
        try Data("active".utf8).write(to: fresh)
        try Data("not produced by Hyphen".utf8).write(to: versionOneLookalike)
        try Data("user file".utf8).write(to: lookalike)
        try FileManager.default.createDirectory(at: lookalikeDirectory, withIntermediateDirectories: true)
        try Data("keep".utf8).write(to: lookalikeDirectory.appendingPathComponent("keep.txt"))
        try Data("keep".utf8).write(to: unrelated)
        let old = Date(timeIntervalSince1970: 1)
        try FileManager.default.setAttributes([.modificationDate: old], ofItemAtPath: aged.path)
        try FileManager.default.setAttributes(
            [.modificationDate: old],
            ofItemAtPath: versionOneLookalike.path
        )
        try FileManager.default.setAttributes([.modificationDate: old], ofItemAtPath: lookalike.path)
        try FileManager.default.setAttributes(
            [.modificationDate: old],
            ofItemAtPath: lookalikeDirectory.path
        )
        try FileManager.default.setAttributes([.modificationDate: old], ofItemAtPath: unrelated.path)

        _ = try ReceivedFileDelivery(destinationDirectory: root)

        XCTAssertFalse(FileManager.default.fileExists(atPath: aged.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: fresh.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: versionOneLookalike.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: lookalike.path))
        XCTAssertTrue(
            FileManager.default.fileExists(
                atPath: lookalikeDirectory.appendingPathComponent("keep.txt").path
            )
        )
        XCTAssertTrue(FileManager.default.fileExists(atPath: unrelated.path))
    }

    func testOwnedCleanupQuarantinesBeforeInspectionAndPreservesASwappedDirectory() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let candidate = root.appendingPathComponent(
            ".hyphen-523E4567-E89B-42D3-8456-426614174000.part"
        )
        let saved = root.appendingPathComponent("saved-staging.part")
        try Data("old staging".utf8).write(to: candidate)
        try FileManager.default.setAttributes(
            [.modificationDate: Date(timeIntervalSince1970: 1)],
            ofItemAtPath: candidate.path
        )

        let deleted = OwnedFileCleanup.deleteIfAgedRegular(
            candidate: candidate,
            cutoff: Date(),
            beforeQuarantineMove: {
                try FileManager.default.moveItem(at: candidate, to: saved)
                try FileManager.default.createDirectory(at: candidate, withIntermediateDirectories: false)
                try Data("keep".utf8).write(
                    to: candidate.appendingPathComponent("keep.txt")
                )
            }
        )

        XCTAssertFalse(deleted)
        XCTAssertEqual(try String(contentsOf: saved, encoding: .utf8), "old staging")
        XCTAssertEqual(
            try String(
                contentsOf: candidate.appendingPathComponent("keep.txt"),
                encoding: .utf8
            ),
            "keep"
        )
    }

    func testOwnedCleanupPreservesARegularFileSwappedAfterPreflight() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let candidate = root.appendingPathComponent(
            ".hyphen-623E4567-E89B-42D3-8456-426614174000.part"
        )
        let saved = root.appendingPathComponent("saved-original.part")
        try Data("original staging".utf8).write(to: candidate)
        try FileManager.default.setAttributes(
            [.modificationDate: Date(timeIntervalSince1970: 1)],
            ofItemAtPath: candidate.path
        )

        let deleted = OwnedFileCleanup.deleteIfAgedRegular(
            candidate: candidate,
            cutoff: Date(),
            beforeQuarantineMove: {
                try FileManager.default.moveItem(at: candidate, to: saved)
                try Data("replacement".utf8).write(to: candidate)
                try FileManager.default.setAttributes(
                    [.modificationDate: Date(timeIntervalSince1970: 1)],
                    ofItemAtPath: candidate.path
                )
            }
        )

        XCTAssertFalse(deleted)
        XCTAssertEqual(try String(contentsOf: saved, encoding: .utf8), "original staging")
        XCTAssertEqual(try String(contentsOf: candidate, encoding: .utf8), "replacement")
    }

    func testOwnedCleanupNeverTruncatesAReplacementSwappedAfterDescriptorBinding() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let candidate = root.appendingPathComponent(
            ".hyphen-723E4567-E89B-42D3-8456-426614174000.part"
        )
        let movedOwnedFile = root.appendingPathComponent("owned-moved-aside.part")
        var replacement: URL?
        try Data("owned staging".utf8).write(to: candidate)
        try FileManager.default.setAttributes(
            [.modificationDate: Date(timeIntervalSince1970: 1)],
            ofItemAtPath: candidate.path
        )

        let reclaimed = OwnedFileCleanup.deleteIfAgedRegular(
            candidate: candidate,
            cutoff: Date(),
            quarantineName: { ".hyphen-823E4567-E89B-42D3-8456-426614174000.part" },
            afterQuarantineBound: { quarantine in
                try FileManager.default.moveItem(at: quarantine, to: movedOwnedFile)
                try Data("victim".utf8).write(to: quarantine)
                replacement = quarantine
            }
        )

        XCTAssertTrue(reclaimed)
        XCTAssertEqual(try Data(contentsOf: movedOwnedFile).count, 0)
        XCTAssertEqual(try String(contentsOf: candidate, encoding: .utf8), "victim")
        XCTAssertFalse(FileManager.default.fileExists(atPath: try XCTUnwrap(replacement).path))
        XCTAssertFalse(
            try FileManager.default.contentsOfDirectory(atPath: root.path)
                .contains(where: { $0.hasSuffix(".hold") })
        )
    }

    func testFileTransferStorageScavengesAgedUnreferencedPartFiles() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }

        let aged = root.appendingPathComponent("f_aged_receive.part")
        let fresh = root.appendingPathComponent("f_active_receive.part")
        let unrelated = root.appendingPathComponent("keep.txt")
        try Data("verified but retained".utf8).write(to: aged)
        try Data("active".utf8).write(to: fresh)
        try Data("keep".utf8).write(to: unrelated)
        let old = Date(timeIntervalSince1970: 1)
        try FileManager.default.setAttributes([.modificationDate: old], ofItemAtPath: aged.path)
        try FileManager.default.setAttributes([.modificationDate: old], ofItemAtPath: unrelated.path)

        _ = FileTransferStorage(root: root)

        XCTAssertFalse(FileManager.default.fileExists(atPath: aged.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: fresh.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: unrelated.path))
    }

    func testFileTransferStoragePreservesAgedPartsWithAnActiveCheckpoint() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }

        let referenced = root.appendingPathComponent("f_referenced_receive.part")
        try Data("resumable".utf8).write(to: referenced)
        try FileManager.default.setAttributes(
            [.modificationDate: Date(timeIntervalSince1970: 1)],
            ofItemAtPath: referenced.path
        )

        _ = FileTransferStorage(
            root: root,
            isCheckpointActive: { $0 == "f_referenced_receive" }
        )

        XCTAssertTrue(FileManager.default.fileExists(atPath: referenced.path))
    }

    private func inode(of url: URL) throws -> ino_t {
        var metadata = stat()
        guard Darwin.lstat(url.path, &metadata) == 0 else {
            throw NSError(domain: NSPOSIXErrorDomain, code: Int(errno))
        }
        return metadata.st_ino
    }

    private func manifest(
        filename: String,
        sizeBytes: Int64,
        sha256: String = "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20"
    ) throws -> TransferManifest {
        try TransferManifest(
            fileId: "f_delivery_test012345",
            filename: filename,
            sizeBytes: sizeBytes,
            mimeType: "text/plain",
            sha256: sha256,
            chunkSizeBytes: 1024,
            chunkCount: sizeBytes == 0 ? 0 : 1
        )
    }
}
