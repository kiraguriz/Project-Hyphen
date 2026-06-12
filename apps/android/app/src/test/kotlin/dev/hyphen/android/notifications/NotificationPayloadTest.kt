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
