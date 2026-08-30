import XCTest
import HyphenTransfer
@testable import HyphenApp

final class TransferPanelMappingTests: XCTestCase {
    func testRowsReflectMultipleRuntimeTransfersAndTheirActions() throws {
        let model = HyphenAppModel()
        let outgoing = try TransferProgress(
            manifest: manifest(fileId: "f_panelout01", filename: "draft.pdf"),
            completedChunks: 2
        )
        let incoming = try TransferProgress(
            manifest: manifest(fileId: "f_panelin01", filename: "photo.heic"),
            completedChunks: 2
        )

        model.apply(.transferProgress(outgoing, direction: .outgoing, at: Date(timeIntervalSince1970: 1)))
        model.apply(.transferResuming(incoming, direction: .incoming, at: Date(timeIntervalSince1970: 2)))
        model.apply(.transferDeliveryFailed(
            fileId: "f_panelretry01",
            filename: "verified.zip",
            sizeBytes: 300,
            at: Date(timeIntervalSince1970: 3)
        ))

        let rows = TransferRow.rows(from: model.feed)

        XCTAssertEqual(rows.map(\.id), ["f_panelretry01", "f_panelin01", "f_panelout01"])
        XCTAssertEqual(rows.map(\.action), [.retrySave, .cancel, .cancel])
        XCTAssertEqual(rows[1].direction, .incoming)
        XCTAssertTrue(rows[1].amber)
        XCTAssertEqual(rows[2].progress, 0.5)
    }

    func testCompletedAndCancelledRowsHaveNoFakeActions() throws {
        let model = HyphenAppModel()
        let progress = try TransferProgress(
            manifest: manifest(fileId: "f_paneldone01", filename: "done.txt"),
            completedChunks: 1
        )
        model.apply(.transferProgress(progress, direction: .incoming, at: Date()))
        model.apply(.transferCompleted(
            fileId: "f_paneldone01",
            filename: "done.txt",
            sizeBytes: 10,
            direction: .incoming,
            verified: true,
            at: Date()
        ))

        var completed = try XCTUnwrap(TransferRow.rows(from: model.feed).first)
        XCTAssertNil(completed.action)
        XCTAssertNil(completed.footerRight)

        model.apply(.transferCancelled(fileId: "f_paneldone01", at: Date()))
        completed = try XCTUnwrap(TransferRow.rows(from: model.feed).first)
        XCTAssertNil(completed.action)
        XCTAssertNil(completed.footerRight)
    }

    func testLegacyCancelTargetRetainsOtherActiveTransferWhenLatestCompletes() throws {
        var index = ActiveTransferIndex()
        let first = try TransferProgress(
            manifest: manifest(fileId: "f_activefirst01", filename: "first.bin"),
            completedChunks: 1
        )
        let second = try TransferProgress(
            manifest: manifest(fileId: "f_activesecond1", filename: "second.bin"),
            completedChunks: 2
        )
        let secondComplete = try TransferProgress(
            manifest: manifest(fileId: "f_activesecond1", filename: "second.bin"),
            completedChunks: 4
        )

        index.record(first)
        index.record(second)
        XCTAssertEqual(index.preferredFileId, second.fileId)

        index.record(secondComplete)
        XCTAssertEqual(index.preferredFileId, first.fileId)

        index.remove(fileId: first.fileId)
        XCTAssertNil(index.preferredFileId)
    }

    private func manifest(fileId: String, filename: String) throws -> TransferManifest {
        try TransferManifest(
            fileId: fileId,
            filename: filename,
            sizeBytes: 4096,
            mimeType: "application/octet-stream",
            sha256: String(repeating: "a", count: 64),
            chunkSizeBytes: 1024,
            chunkCount: 4
        )
    }
}
