package dev.hyphen.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

// B1 verification — the observable model is the contract between the backend
// event producers and the Compose UI, so its append / upsert / bounding /
// grouping and the reducer that drives them are unit-tested here (pure JVM,
// no Android dependency).

class ActivityFeedTest {

    private var id = 0L
    private fun nextId() = id++

    private fun text(value: String, at: Long): ActivityItem =
        ActivityItem(nextId(), at, ActivityKind.Text(TextFeedItem(TextKind.TEXT, TextDirection.SENT, value)))

    @Test
    fun appendIsNewestFirst() {
        var feed = ActivityFeed()
        feed = feed.upsert(text("a", 1000))
        feed = feed.upsert(text("b", 2000))
        assertEquals(2, feed.items.size)
        val first = (feed.items[0].kind as ActivityKind.Text).item
        assertEquals("b", first.value)
    }

    @Test
    fun boundingTrimsOldest() {
        var feed = ActivityFeed(capacity = 3)
        for (i in 0 until 10) feed = feed.upsert(text("$i", i.toLong()))
        assertEquals(3, feed.items.size)
        val values = feed.items.map { (it.kind as ActivityKind.Text).item.value }
        assertEquals(listOf("9", "8", "7"), values)
    }

    @Test
    fun upsertDeduplicatesAndPreservesIdentityAndMovesToFront() {
        var feed = ActivityFeed()
        val key = "transfer:f_a"
        feed = feed.upsert(ActivityItem(nextId(), 1000, transfer("f_a", TransferStatus.ACTIVE), key))
        val originalId = feed.items[0].id
        feed = feed.upsert(text("other", 1500))
        feed = feed.upsert(ActivityItem(nextId(), 2000, transfer("f_a", TransferStatus.COMPLETED), key))
        assertEquals(2, feed.items.size)
        assertEquals(originalId, feed.items[0].id)
        assertEquals(2000L, feed.items[0].timestampMillis)
    }

    @Test
    fun groupingTodayYesterdayOlder() {
        val zone = ZoneId.of("UTC")
        val now = 1_700_000_000_000L // fixed reference
        val day = 24L * 60 * 60 * 1000
        var feed = ActivityFeed()
        // Events arrive oldest-first; each upsert prepends → newest-first.
        feed = feed.upsert(text("older", now - 5 * day))
        feed = feed.upsert(text("yesterday", now - day))
        feed = feed.upsert(text("today", now))
        val days = feed.grouped(now, zone)
        assertEquals(3, days.size)
        assertEquals(DayLabel.Today, days[0].label)
        assertEquals(DayLabel.Yesterday, days[1].label)
        assertTrue(days[2].label is DayLabel.OnDate)
    }

    private fun transfer(fileId: String, status: TransferStatus): ActivityKind.Transfer =
        ActivityKind.Transfer(
            TransferFeedItem(fileId, "doc.pdf", TransferDirection.OUTGOING, 1024, 2048, 1, 2, status, false),
        )
}

class HyphenReducerTest {

    private var id = 0L
    private fun apply(state: HyphenUiState, event: ActivityEvent) =
        HyphenReducer.reduce(state, event) { id++ }

    @Test
    fun peerChangedTogglesPairedAndResetsWhenUnpaired() {
        var s = HyphenUiState()
        s = apply(s, ActivityEvent.ConnectionChanged(ConnectionState.CONNECTED, null))
        s = apply(s, ActivityEvent.PeerChanged(isPaired = true, peerName = "MacBook Pro"))
        assertTrue(s.connection.isPaired)
        assertEquals("MacBook Pro", s.connection.peerName)

        s = apply(s, ActivityEvent.PeerChanged(isPaired = false, peerName = null))
        assertFalse(s.connection.isPaired)
        assertNull(s.connection.peerName)
        assertEquals(ConnectionState.SUSPENDED, s.connection.state)
    }

    @Test
    fun transferProgressThenCompletedThenCancelled() {
        var s = HyphenUiState()
        s = apply(s, ActivityEvent.TransferProgressed("f_x", "a.bin", TransferDirection.INCOMING, 512, 1024, 1, 2, false, 10))
        assertEquals(1, s.feed.items.size)
        assertEquals(TransferStatus.ACTIVE, (s.feed.items[0].kind as ActivityKind.Transfer).item.status)

        s = apply(s, ActivityEvent.TransferCompleted("f_x", "a.bin", 1024, TransferDirection.INCOMING, true, 20))
        assertEquals(1, s.feed.items.size)
        val done = (s.feed.items[0].kind as ActivityKind.Transfer).item
        assertEquals(TransferStatus.COMPLETED, done.status)
        assertTrue(done.verified)

        s = apply(s, ActivityEvent.TransferCancelled("f_x", 30))
        assertEquals(TransferStatus.CANCELLED, (s.feed.items[0].kind as ActivityKind.Transfer).item.status)
    }

    @Test
    fun transferCancelledForUnknownFileIsIgnored() {
        val s = apply(HyphenUiState(), ActivityEvent.TransferCancelled("f_missing", 10))
        assertTrue(s.feed.items.isEmpty())
    }

    @Test
    fun textAndNotificationActionAppend() {
        var s = HyphenUiState()
        s = apply(s, ActivityEvent.Text(TextKind.URL, TextDirection.SENT, "https://x", 10))
        s = apply(s, ActivityEvent.NotificationActionPerformed("微信", "已回复", 20))
        assertEquals(2, s.feed.items.size)
        assertTrue(s.feed.items[0].kind is ActivityKind.NotificationAction)
    }
}
