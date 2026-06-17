package dev.hyphen.android.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// Phase 0 — the observable "connection + activity" model for Android (frontend
// UX plan Track B / B1), mirroring the macOS `HyphenAppModel`. This file is the
// UI-agnostic, pure core: immutable value types, a bounded in-memory
// `ActivityFeed`, the structured `ActivityEvent`, and a pure `reduce`. The
// Compose-facing holder (`HyphenUiModel`) wraps these.
//
// Convention: notification history is NEVER persisted (CLAUDE.md). The feed is
// in-memory only and capped. Android is the notification SOURCE (it mirrors to
// the Mac), so the feed records transfers, text/links, pairing/session events,
// and reply/dismiss actions performed for the Mac — it does not fabricate
// "received notification" rows.

// MARK: connection

enum class ConnectionState {
    CONNECTED,
    DEGRADED,
    RECONNECTING,
    DISCOVERING,
    SLEEPING,
    SUSPENDED,
}

data class ConnectionSnapshot(
    val state: ConnectionState = ConnectionState.SUSPENDED,
    val isPaired: Boolean = false,
    val peerName: String? = null,
    val latencyMs: Int? = null,
)

// MARK: feed item payloads

enum class TransferDirection { INCOMING, OUTGOING }

enum class TransferStatus { ACTIVE, COMPLETED, CANCELLED }

enum class TextKind { TEXT, URL }

enum class TextDirection { SENT, RECEIVED }

data class TransferFeedItem(
    val fileId: String,
    val filename: String,
    val direction: TransferDirection,
    val completedBytes: Long,
    val totalBytes: Long,
    val completedChunks: Int,
    val totalChunks: Int,
    val status: TransferStatus,
    val verified: Boolean,
) {
    /** 0..1 progress, or null when the total size is unknown. */
    val fraction: Float?
        get() = if (totalBytes > 0) (completedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null
}

data class TextFeedItem(
    val kind: TextKind,
    val direction: TextDirection,
    val value: String,
)

/** A reply/dismiss action Android performed on the Mac's behalf. */
data class NotificationActionFeedItem(
    val label: String,
    val action: String,
)

/** A pairing / session lifecycle note. */
data class PairingFeedItem(val message: String)

// MARK: activity item

sealed interface ActivityKind {
    data class Transfer(val item: TransferFeedItem) : ActivityKind
    data class Text(val item: TextFeedItem) : ActivityKind
    data class NotificationAction(val item: NotificationActionFeedItem) : ActivityKind
    data class Pairing(val item: PairingFeedItem) : ActivityKind
}

data class ActivityItem(
    val id: Long,
    val timestampMillis: Long,
    val kind: ActivityKind,
    /** Identity for in-place updates (transfers by `transfer:<fileId>`). */
    val dedupeKey: String? = null,
)

/**
 * A relative day label for a timeline bucket. The pure model stays
 * UI-agnostic and locale-agnostic; the Compose layer resolves these to
 * localized strings (mirroring the macOS `ActivityFeed.dayTitle`, which goes
 * through `L(...)` rather than baking copy into the model).
 */
sealed interface DayLabel {
    data object Today : DayLabel
    data object Yesterday : DayLabel
    data class OnDate(val month: Int, val day: Int) : DayLabel
}

/** A day bucket for the timeline; [label] is resolved to a localized string at render time. */
data class ActivityDay(
    val id: String,
    val label: DayLabel,
    val items: List<ActivityItem>,
)

// MARK: activity feed

/**
 * Bounded, newest-first, in-memory activity log. Pure value type so the
 * append/upsert/bounding/grouping logic is unit-testable without Android.
 */
data class ActivityFeed(
    val items: List<ActivityItem> = emptyList(),
    val capacity: Int = 200,
) {
    /**
     * Insert or update by [ActivityItem.dedupeKey]. A keyed item replaces the
     * existing one (preserving its id so list identity is stable) and moves to
     * the front; an unkeyed item is always prepended. Trimmed to [capacity].
     */
    fun upsert(item: ActivityItem): ActivityFeed {
        val key = item.dedupeKey
        val existing = if (key != null) items.firstOrNull { it.dedupeKey == key } else null
        val placed = if (existing != null) item.copy(id = existing.id) else item
        val without = if (existing != null) items.filterNot { it.dedupeKey == key } else items
        val next = (listOf(placed) + without).take(capacity.coerceAtLeast(1))
        return copy(items = next)
    }

    /** Group newest-first items into day buckets relative to [nowMillis]. */
    fun grouped(nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): List<ActivityDay> {
        if (items.isEmpty()) return emptyList()
        val today = LocalDate.ofInstant(Instant.ofEpochMilli(nowMillis), zone)
        val order = mutableListOf<LocalDate>()
        val buckets = LinkedHashMap<LocalDate, MutableList<ActivityItem>>()
        for (item in items) {
            val date = LocalDate.ofInstant(Instant.ofEpochMilli(item.timestampMillis), zone)
            buckets.getOrPut(date) { order.add(date); mutableListOf() }.add(item)
        }
        return order.map { date ->
            ActivityDay(id = date.toString(), label = dayLabel(date, today), items = buckets.getValue(date))
        }
    }

    private fun dayLabel(date: LocalDate, today: LocalDate): DayLabel = when (date) {
        today -> DayLabel.Today
        today.minusDays(1) -> DayLabel.Yesterday
        else -> DayLabel.OnDate(date.monthValue, date.dayOfMonth)
    }
}

// MARK: activity event

/**
 * Structured event the backend (`MainActivity` controller) emits into the
 * model. Replaces the legacy debug-log string lines as the UI's source of
 * truth (the raw event log is still kept for the auditable "本机事件流" card).
 */
sealed interface ActivityEvent {
    data class PeerChanged(val isPaired: Boolean, val peerName: String?) : ActivityEvent
    data class ConnectionChanged(val state: ConnectionState, val latencyMs: Int?) : ActivityEvent
    data class TransferProgressed(
        val fileId: String,
        val filename: String,
        val direction: TransferDirection,
        val completedBytes: Long,
        val totalBytes: Long,
        val completedChunks: Int,
        val totalChunks: Int,
        val complete: Boolean,
        val atMillis: Long,
    ) : ActivityEvent
    data class TransferCompleted(
        val fileId: String,
        val filename: String,
        val sizeBytes: Long,
        val direction: TransferDirection,
        val verified: Boolean,
        val atMillis: Long,
    ) : ActivityEvent
    data class TransferCancelled(val fileId: String, val atMillis: Long) : ActivityEvent
    data class Text(
        val kind: TextKind,
        val direction: TextDirection,
        val value: String,
        val atMillis: Long,
    ) : ActivityEvent
    data class NotificationActionPerformed(
        val label: String,
        val action: String,
        val atMillis: Long,
    ) : ActivityEvent
    data class PairingNote(val message: String, val atMillis: Long) : ActivityEvent
}

// MARK: state + reducer

data class HyphenUiState(
    val connection: ConnectionSnapshot = ConnectionSnapshot(),
    val feed: ActivityFeed = ActivityFeed(),
    val logLines: List<String> = emptyList(),
)

/** Pure reducer — the single mutation entry, testable without Android. */
object HyphenReducer {
    private fun transferKey(fileId: String) = "transfer:$fileId"

    fun reduce(state: HyphenUiState, event: ActivityEvent, nextId: () -> Long): HyphenUiState =
        when (event) {
            is ActivityEvent.PeerChanged -> {
                val c = state.connection.copy(isPaired = event.isPaired, peerName = event.peerName)
                state.copy(
                    connection = if (event.isPaired) c
                    else c.copy(state = ConnectionState.SUSPENDED, latencyMs = null),
                )
            }

            is ActivityEvent.ConnectionChanged ->
                state.copy(connection = state.connection.copy(state = event.state, latencyMs = event.latencyMs))

            is ActivityEvent.TransferProgressed -> {
                val item = TransferFeedItem(
                    fileId = event.fileId,
                    filename = event.filename,
                    direction = event.direction,
                    completedBytes = event.completedBytes,
                    totalBytes = event.totalBytes,
                    completedChunks = event.completedChunks,
                    totalChunks = event.totalChunks,
                    status = if (event.complete) TransferStatus.COMPLETED else TransferStatus.ACTIVE,
                    verified = false,
                )
                state.copy(feed = state.feed.upsert(
                    ActivityItem(nextId(), event.atMillis, ActivityKind.Transfer(item), transferKey(event.fileId)),
                ))
            }

            is ActivityEvent.TransferCompleted -> {
                val item = TransferFeedItem(
                    fileId = event.fileId,
                    filename = event.filename,
                    direction = event.direction,
                    completedBytes = event.sizeBytes,
                    totalBytes = event.sizeBytes,
                    completedChunks = 0,
                    totalChunks = 0,
                    status = TransferStatus.COMPLETED,
                    verified = event.verified,
                )
                state.copy(feed = state.feed.upsert(
                    ActivityItem(nextId(), event.atMillis, ActivityKind.Transfer(item), transferKey(event.fileId)),
                ))
            }

            is ActivityEvent.TransferCancelled -> {
                val key = transferKey(event.fileId)
                val existing = state.feed.items.firstOrNull { it.dedupeKey == key }
                val kind = existing?.kind as? ActivityKind.Transfer ?: return state
                val item = kind.item.copy(status = TransferStatus.CANCELLED)
                state.copy(feed = state.feed.upsert(
                    ActivityItem(nextId(), event.atMillis, ActivityKind.Transfer(item), key),
                ))
            }

            is ActivityEvent.Text ->
                state.copy(feed = state.feed.upsert(
                    ActivityItem(nextId(), event.atMillis, ActivityKind.Text(
                        TextFeedItem(event.kind, event.direction, event.value),
                    )),
                ))

            is ActivityEvent.NotificationActionPerformed ->
                state.copy(feed = state.feed.upsert(
                    ActivityItem(nextId(), event.atMillis, ActivityKind.NotificationAction(
                        NotificationActionFeedItem(event.label, event.action),
                    )),
                ))

            is ActivityEvent.PairingNote ->
                state.copy(feed = state.feed.upsert(
                    ActivityItem(nextId(), event.atMillis, ActivityKind.Pairing(PairingFeedItem(event.message))),
                ))
        }
}
