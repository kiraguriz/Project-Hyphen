import XCTest
import HyphenNotifications
import HyphenTransfer
@testable import HyphenApp

// M-A1 verification — the observable model is the contract between backend
// event producers and the UI, so its append / upsert / bounding / grouping and
// the reducer that drives them are unit-tested here.

final class ActivityFeedTests: XCTestCase {

    private func note(_ key: String, at date: Date) -> ActivityItem {
        ActivityItem(
            timestamp: date,
            kind: .notification(NotificationFeedItem(
                sbnKey: key,
                identifier: "hyphen.notification.\(key)",
                appName: "com.example",
                title: "T",
                body: "B",
                replyActions: []
            )),
            dedupeKey: "hyphen.notification.\(key)"
        )
    }

    private func text(_ value: String, at date: Date) -> ActivityItem {
        ActivityItem(timestamp: date, kind: .text(TextFeedItem(kind: .text, direction: .sent, value: value)))
    }

    func testAppendIsNewestFirst() {
        var feed = ActivityFeed()
        let t0 = Date(timeIntervalSince1970: 1000)
        feed.upsert(text("a", at: t0))
        feed.upsert(text("b", at: t0.addingTimeInterval(1)))
        XCTAssertEqual(feed.items.count, 2)
        guard case .text(let first) = feed.items[0].kind else { return XCTFail("expected text") }
        XCTAssertEqual(first.value, "b", "most recent append is at the front")
    }

    func testBoundingTrimsOldest() {
        var feed = ActivityFeed(capacity: 3)
        let base = Date(timeIntervalSince1970: 0)
        for i in 0..<10 {
            feed.upsert(text("\(i)", at: base.addingTimeInterval(Double(i))))
        }
        XCTAssertEqual(feed.items.count, 3, "capped at capacity")
        let values = feed.items.compactMap { item -> String? in
            if case .text(let t) = item.kind { return t.value }
            return nil
        }
        XCTAssertEqual(values, ["9", "8", "7"], "newest three retained, oldest dropped")
    }

    func testUpsertDeduplicatesAndPreservesIdentity() {
        var feed = ActivityFeed()
        let t0 = Date(timeIntervalSince1970: 1000)
        feed.upsert(note("k1", at: t0))
        let originalId = feed.items[0].id
        feed.upsert(text("other", at: t0.addingTimeInterval(1)))
        // Update the same notification key — should replace in place (not grow)
        // and move to the front, keeping the SwiftUI-stable id.
        feed.upsert(note("k1", at: t0.addingTimeInterval(2)))
        XCTAssertEqual(feed.items.count, 2, "keyed update does not add a row")
        XCTAssertEqual(feed.items[0].id, originalId, "id preserved across update")
        XCTAssertEqual(feed.items[0].timestamp, t0.addingTimeInterval(2))
    }

    func testRemoveNotificationByIdentifier() {
        var feed = ActivityFeed()
        let t0 = Date(timeIntervalSince1970: 1000)
        feed.upsert(note("k1", at: t0))
        feed.upsert(note("k2", at: t0.addingTimeInterval(1)))
        feed.removeNotification(identifier: "hyphen.notification.k1")
        XCTAssertEqual(feed.items.count, 1)
        guard case .notification(let remaining) = feed.items[0].kind else { return XCTFail() }
        XCTAssertEqual(remaining.sbnKey, "k2")
    }

    func testGroupingTodayYesterdayAndOlder() {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "UTC")!
        let now = Date(timeIntervalSince1970: 1_700_000_000) // fixed reference
        let yesterday = cal.date(byAdding: .day, value: -1, to: now)!
        let older = cal.date(byAdding: .day, value: -5, to: now)!

        // Events arrive chronologically (oldest first); each upsert prepends, so
        // the feed ends up newest-first.
        var feed = ActivityFeed()
        feed.upsert(text("older", at: older))
        feed.upsert(text("yesterday", at: yesterday))
        feed.upsert(text("today", at: now))

        // Titles are localized; compare against the localized values so the
        // grouping logic is asserted independent of the test host's locale.
        let today = L("feed.today")
        let yesterdayTitle = L("feed.yesterday")
        let days = feed.grouped(relativeTo: now, calendar: cal)
        XCTAssertEqual(days.count, 3)
        XCTAssertEqual(days[0].title, today)
        XCTAssertEqual(days[1].title, yesterdayTitle)
        XCTAssertFalse(days[2].title.isEmpty)
        XCTAssertNotEqual(days[2].title, today)
        XCTAssertNotEqual(days[2].title, yesterdayTitle)
    }
}

final class HyphenAppModelReducerTests: XCTestCase {

    func testPeerChangedTogglesPairedAndResetsWhenUnpaired() {
        let model = HyphenAppModel()
        model.apply(.connectionStateChanged(.connected, latencyMs: nil))
        model.apply(.peerChanged(isPaired: true, peerName: "Pixel 8"))
        XCTAssertTrue(model.connection.isPaired)
        XCTAssertEqual(model.connection.peerName, "Pixel 8")

        model.apply(.peerChanged(isPaired: false, peerName: nil))
        XCTAssertFalse(model.connection.isPaired)
        XCTAssertNil(model.connection.peerName)
        XCTAssertEqual(model.connection.state, .suspended, "unpaired forces suspended")
    }

    func testNotificationPostedThenRemoved() {
        let model = HyphenAppModel()
        let request = NotificationPresentationRequest(
            identifier: AndroidNotificationPayload.identifier(for: "key-1"),
            sbnKey: "key-1",
            packageName: "com.tencent.mm",
            title: "张伟",
            body: "晚上吃饭吗",
            category: nil
        )
        model.apply(.notificationPosted(request, at: Date(timeIntervalSince1970: 10)))
        XCTAssertEqual(model.feed.items.count, 1)

        // An update with the same identifier must not duplicate the row.
        model.apply(.notificationPosted(request, at: Date(timeIntervalSince1970: 20)))
        XCTAssertEqual(model.feed.items.count, 1)

        model.apply(.notificationRemoved(identifier: request.identifier, at: Date(timeIntervalSince1970: 30)))
        XCTAssertTrue(model.feed.items.isEmpty)
    }

    func testTransferProgressThenCompletedThenCancelled() throws {
        let model = HyphenAppModel()
        let manifest = try TransferManifest(
            fileId: "f_testfile01",
            filename: "doc.pdf",
            sizeBytes: 2048,
            mimeType: "application/pdf",
            sha256: String(repeating: "a", count: 64),
            chunkSizeBytes: 1024,
            chunkCount: 2
        )
        let half = try TransferProgress(manifest: manifest, completedChunks: 1)
        model.apply(.transferProgress(half, direction: .outgoing, at: Date(timeIntervalSince1970: 10)))
        XCTAssertEqual(model.feed.items.count, 1)
        guard case .transfer(let active) = model.feed.items[0].kind else { return XCTFail() }
        XCTAssertEqual(active.status, .active)
        XCTAssertEqual(active.direction, .outgoing)

        // Same fileId completing updates in place.
        model.apply(.transferCompleted(
            fileId: "f_testfile01",
            filename: "doc.pdf",
            sizeBytes: 2048,
            direction: .outgoing,
            verified: false,
            at: Date(timeIntervalSince1970: 20)
        ))
        XCTAssertEqual(model.feed.items.count, 1, "completion updates the existing row")
        guard case .transfer(let done) = model.feed.items[0].kind else { return XCTFail() }
        XCTAssertEqual(done.status, .completed)

        model.apply(.transferCancelled(fileId: "f_testfile01", at: Date(timeIntervalSince1970: 30)))
        guard case .transfer(let cancelled) = model.feed.items[0].kind else { return XCTFail() }
        XCTAssertEqual(cancelled.status, .cancelled)
    }

    func testTransferCancelledForUnknownFileIsIgnored() {
        let model = HyphenAppModel()
        model.apply(.transferCancelled(fileId: "f_unknown01", at: Date()))
        XCTAssertTrue(model.feed.items.isEmpty, "no fabricated row for an unknown transfer")
    }

    func testTextEventsAppend() {
        let model = HyphenAppModel()
        model.apply(.text(kind: .url, direction: .sent, value: "https://example.com", at: Date(timeIntervalSince1970: 10)))
        model.apply(.text(kind: .text, direction: .received, value: "hi", at: Date(timeIntervalSince1970: 20)))
        XCTAssertEqual(model.feed.items.count, 2)
        guard case .text(let newest) = model.feed.items[0].kind else { return XCTFail() }
        XCTAssertEqual(newest.value, "hi")
        XCTAssertEqual(newest.direction, .received)
    }
}
