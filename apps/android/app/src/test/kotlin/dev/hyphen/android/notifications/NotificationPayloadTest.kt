package dev.hyphen.android.notifications

import dev.hyphen.android.transport.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class NotificationPayloadTest {

    @Test
    fun `normalization uses sbn key as identity and ignores post time`() {
        val first = NormalizedNotificationPayload.from(
            NotificationPayloadSource(
                sbnKey = "0|com.chat|7|thread-123|10101",
                packageName = "com.chat",
                title = "Alice",
                text = "hello",
                category = "msg",
                isClearable = true,
                postTimeUnixMs = 1_781_200_000_000,
            ),
        )
        val repost = NormalizedNotificationPayload.from(
            NotificationPayloadSource(
                sbnKey = "0|com.chat|7|thread-123|10101",
                packageName = "com.chat",
                title = "Alice",
                text = "hello",
                category = "msg",
                isClearable = true,
                postTimeUnixMs = 1_781_200_005_000,
            ),
        )

        assertEquals(first, repost)
        assertEquals("0|com.chat|7|thread-123|10101", first.sbnKey)
        assertFalse(first.toJson().entries.containsKey("postTime"))
        assertFalse(first.toJson().entries.containsKey("postTimeUnixMs"))
    }

    @Test
    fun `payload json contains stable display fields`() {
        val payload = NormalizedNotificationPayload.from(
            NotificationPayloadSource(
                sbnKey = "0|com.mail|42|null|10101",
                packageName = "com.mail",
                title = "Inbox",
                text = "2 new messages",
                category = "email",
                isClearable = true,
                isOngoing = false,
            ),
        ).toJson()

        assertEquals(Json.Str("0|com.mail|42|null|10101"), payload["sbnKey"])
        assertEquals(Json.Str("com.mail"), payload["packageName"])
        assertEquals(Json.Str("Inbox"), payload["title"])
        assertEquals(Json.Str("2 new messages"), payload["text"])
        assertEquals(Json.Str("email"), payload["category"])
        assertEquals(Json.Bool(true), payload["clearable"])
        assertEquals(Json.Bool(false), payload["ongoing"])
    }

    @Test
    fun `payload json includes only advertised remote input reply actions`() {
        val payload = NormalizedNotificationPayload.from(
            NotificationPayloadSource(
                sbnKey = "0|com.chat|7|thread-123|10101",
                packageName = "com.chat",
                replyActions = listOf(
                    NotificationReplyAction(
                        actionIndex = 2,
                        label = "Reply",
                        actionId = "reply:1:reply:android-reply",
                    ),
                ),
            ),
        ).toJson()

        val replyActions = payload["replyActions"] as Json.Arr
        val action = replyActions.items.single() as Json.Obj
        assertEquals(Json.Num("2"), action["actionIndex"])
        assertEquals(Json.Str("Reply"), action["label"])
        assertEquals(Json.Str("reply:1:reply:android-reply"), action["actionId"])
    }

    @Test
    fun `reply action id resolves reordered actions and legacy index fallback`() {
        val reply = NotificationReplyActionMetadata(
            actionIndex = 2,
            semanticAction = 1,
            title = "Reply",
            resultKeys = listOf("android.reply"),
            hasActionIntent = true,
        )
        val markRead = NotificationReplyActionMetadata(
            actionIndex = 0,
            semanticAction = 2,
            title = "Mark read",
            resultKeys = emptyList(),
            hasActionIntent = true,
        )
        val reorderedReply = reply.copy(actionIndex = 0)
        val actionId = NotificationReplyActions.stableActionId(reply)

        assertEquals(
            0,
            NotificationReplyActions.resolveActionIndex(
                actions = listOf(reorderedReply, markRead.copy(actionIndex = 1)),
                requestedActionIndex = 2,
                requestedActionId = actionId,
            ),
        )
        assertEquals(
            null,
            NotificationReplyActions.resolveActionIndex(
                actions = listOf(markRead.copy(actionIndex = 0)),
                requestedActionIndex = 2,
                requestedActionId = actionId,
            ),
        )
        assertEquals(
            2,
            NotificationReplyActions.resolveActionIndex(
                actions = listOf(markRead, reply),
                requestedActionIndex = 2,
                requestedActionId = null,
            ),
        )
    }

    @Test
    fun `reply action ids are omitted for duplicate reply metadata`() {
        val firstReply = NotificationReplyActionMetadata(
            actionIndex = 1,
            semanticAction = 1,
            title = "Reply",
            resultKeys = listOf("android.reply"),
            hasActionIntent = true,
        )
        val secondReply = firstReply.copy(actionIndex = 3)
        val actionIds = NotificationReplyActions.stableActionIds(listOf(firstReply, secondReply))
        val unstableBaseId = NotificationReplyActions.stableActionId(firstReply)

        assertEquals(null, actionIds[1])
        assertEquals(null, actionIds[3])
        assertEquals(
            null,
            NotificationReplyActions.resolveActionIndex(
                actions = listOf(firstReply, secondReply),
                requestedActionIndex = 3,
                requestedActionId = unstableBaseId,
            ),
        )
    }

    @Test
    fun `reply action id ignores non-sendable duplicate when resolving`() {
        val reply = NotificationReplyActionMetadata(
            actionIndex = 1,
            semanticAction = 1,
            title = "Reply",
            resultKeys = listOf("android.reply"),
            hasActionIntent = true,
        )
        val unsendableDuplicate = reply.copy(
            actionIndex = 2,
            hasActionIntent = false,
        )
        val actionId = NotificationReplyActions.stableActionIds(listOf(reply))[1]

        assertEquals(
            1,
            NotificationReplyActions.resolveActionIndex(
                actions = listOf(reply, unsendableDuplicate),
                requestedActionIndex = 1,
                requestedActionId = actionId,
            ),
        )
        assertEquals(null, NotificationReplyActions.stableActionIds(listOf(reply, unsendableDuplicate))[2])
    }

    @Test
    fun `blank optional text fields are omitted`() {
        val payload = NormalizedNotificationPayload.from(
            NotificationPayloadSource(
                sbnKey = "0|com.app|1|null|10101",
                packageName = "com.app",
                title = "   ",
                text = "",
                category = null,
            ),
        ).toJson()

        assertFalse(payload.entries.containsKey("title"))
        assertFalse(payload.entries.containsKey("text"))
        assertFalse(payload.entries.containsKey("category"))
    }

    @Test
    fun `blank identity fields are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            NormalizedNotificationPayload.from(
                NotificationPayloadSource(
                    sbnKey = "",
                    packageName = "com.app",
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            NormalizedNotificationPayload.from(
                NotificationPayloadSource(
                    sbnKey = "0|com.app|1|null|10101",
                    packageName = " ",
                ),
            )
        }
    }
}
