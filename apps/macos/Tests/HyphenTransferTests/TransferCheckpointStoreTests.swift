import XCTest
@testable import HyphenTransfer

final class TransferCheckpointStoreTests: XCTestCase {
    private let peerA = Data([0x01, 0x02])
    private let peerB = Data([0x03, 0x04])

    func testSaveLoadAndInvalidatePeer() throws {
        var clock: Int64 = 1_000
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        let store = TransferCheckpointStore(root: root, nowMs: { clock })
        let manifest = try TransferManifest(
            fileId: "f_test_checkpoint012345",
            filename: "a.bin",
            sizeBytes: 3,
            mimeType: "application/octet-stream",
            sha256: "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20",
            chunkSizeBytes: 1024,
            chunkCount: 1
        )
        try store.save(
            TransferCheckpointStore.Record(
                fileId: manifest.fileId,
                manifest: manifest,
                peerFingerprintHex: peerA.hexLower(),
                sessionId: "s_test",
                nextChunkIndex: 1,
                receivedRanges: [0..<1],
                updatedAtMs: clock,
                expiresAtMs: clock + TransferCheckpointStore.defaultTTLMs
            )
        )

        XCTAssertEqual(store.load(fileId: manifest.fileId)?.nextChunkIndex, 1)
        store.invalidatePeer(peerA)
        XCTAssertNil(store.load(fileId: manifest.fileId))
    }

    func testPurgeExpired() throws {
        var clock: Int64 = 0
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        let store = TransferCheckpointStore(root: root, nowMs: { clock })
        let manifest = try TransferManifest(
            fileId: "f_expired012345678",
            filename: "a.bin",
            sizeBytes: 0,
            mimeType: "application/octet-stream",
            sha256: "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            chunkSizeBytes: 1024,
            chunkCount: 0
        )
        try store.save(
            TransferCheckpointStore.Record(
                fileId: manifest.fileId,
                manifest: manifest,
                peerFingerprintHex: peerA.hexLower(),
                sessionId: "s_test",
                nextChunkIndex: 0,
                receivedRanges: [],
                updatedAtMs: clock,
                expiresAtMs: clock + 10
            )
        )
        clock = 11
        store.purgeExpired()
        XCTAssertTrue(store.loadActive(for: peerA).isEmpty)
    }
}

private extension Data {
    func hexLower() -> String {
        map { String(format: "%02x", $0) }.joined()
    }
}
