package dev.hyphen.android.notifications

import android.app.Notification
import android.os.Build
import android.service.notification.StatusBarNotification
import dev.hyphen.android.transport.Json

object NotificationProtocol {
    const val CAPABILITY = "notifications.v1"
    const val TYPE_POSTED = "notification.posted"
    const val TYPE_UPDATED = "notification.updated"
    const val TYPE_REMOVED = "notification.removed"
    const val TYPE_DISMISS_REQUEST = "notification.dismiss.request"
    const val TYPE_DISMISS_RESULT = "notification.dismiss.result"
    const val TYPE_REPLY_REQUEST = "notification.reply.request"
    const val TYPE_REPLY_RESULT = "notification.reply.result"
}

data class NotificationReplyAction(
    val actionIndex: Int,
    val label: String,
    val actionId: String? = null,
) {
    init {
        require(actionIndex >= 0) { "actionIndex must be non-negative" }
        require(label.isNotBlank()) { "label must not be blank" }
        require(actionId == null || actionId.isNotBlank()) { "actionId must not be blank" }
    }

    fun toJson(): Json.Obj {
        val entries = linkedMapOf(
            "actionIndex" to Json.Num(actionIndex.toString()),
            "label" to Json.Str(label),
        )
        actionId?.let { entries["actionId"] = Json.Str(it) }
        return Json.Obj(entries)
    }
}

internal data class NotificationReplyActionMetadata(
    val actionIndex: Int,
    val semanticAction: Int,
    val title: String?,
    val resultKeys: List<String>,
    val hasActionIntent: Boolean,
)

internal object NotificationReplyActions {
    fun stableActionId(metadata: NotificationReplyActionMetadata): String? {
        if (metadata.resultKeys.isEmpty()) return null
        val parts = listOf(
            metadata.semanticAction.toString(),
            normalizePart(metadata.title),
            metadata.resultKeys.sorted().joinToString("+") { normalizePart(it) },
        )
        return "reply:${parts.joinToString(":")}"
    }

    fun stableActionIds(actions: List<NotificationReplyActionMetadata>): Map<Int, String?> {
        val replyCapableActions = actions.filter { it.isReplyCapable() }
        val baseIds = replyCapableActions.map { it to stableActionId(it) }
        val duplicateCounts = baseIds
            .mapNotNull { it.second }
            .groupingBy { it }
            .eachCount()
        val stableIds = baseIds.associate { (metadata, baseId) ->
            val actionId = baseId?.takeIf { id -> duplicateCounts[id] == 1 }
            metadata.actionIndex to actionId
        }
        return actions.associate { metadata -> metadata.actionIndex to stableIds[metadata.actionIndex] }
    }

    fun resolveActionIndex(
        actions: List<NotificationReplyActionMetadata>,
        requestedActionIndex: Int,
        requestedActionId: String?,
    ): Int? {
        if (requestedActionId != null) {
            val currentActionIds = stableActionIds(actions)
            val current = actions.firstOrNull { currentActionIds[it.actionIndex] == requestedActionId } ?: return null
            return current.takeIf { it.isReplyCapable() }?.actionIndex
        }
        return actions.firstOrNull { it.actionIndex == requestedActionIndex && it.isReplyCapable() }?.actionIndex
    }

    private fun NotificationReplyActionMetadata.isReplyCapable(): Boolean =
        hasActionIntent && resultKeys.isNotEmpty()

    private fun normalizePart(value: String?): String =
        value
            ?.trim()
            ?.lowercase()
            ?.replace(Regex("\\s+"), "-")
            ?.takeIf { it.isNotEmpty() }
            ?: "_"
}

/**
 * Raw fields extracted from StatusBarNotification. postTime stays at this
 * boundary so tests can prove it never enters Hyphen notification identity.
 */
data class NotificationPayloadSource(
    val sbnKey: String,
    val packageName: String,
    val title: String? = null,
    val text: String? = null,
    val category: String? = null,
    val isClearable: Boolean = false,
    val isOngoing: Boolean = false,
    val postTimeUnixMs: Long? = null,
    val replyActions: List<NotificationReplyAction> = emptyList(),
)

data class NormalizedNotificationPayload(
    val sbnKey: String,
    val packageName: String,
    val title: String? = null,
    val text: String? = null,
    val category: String? = null,
    val isClearable: Boolean = false,
    val isOngoing: Boolean = false,
    val replyActions: List<NotificationReplyAction> = emptyList(),
) {
    init {
        require(sbnKey.isNotBlank()) { "sbnKey must not be blank" }
        require(packageName.isNotBlank()) { "packageName must not be blank" }
    }

    fun toJson(): Json.Obj {
        val entries = linkedMapOf<String, Json>(
            "sbnKey" to Json.Str(sbnKey),
            "packageName" to Json.Str(packageName),
            "clearable" to Json.Bool(isClearable),
            "ongoing" to Json.Bool(isOngoing),
        )
        title?.let { entries["title"] = Json.Str(it) }
        text?.let { entries["text"] = Json.Str(it) }
        category?.let { entries["category"] = Json.Str(it) }
        if (replyActions.isNotEmpty()) {
            entries["replyActions"] = Json.Arr(replyActions.map { it.toJson() })
        }
        return Json.Obj(entries)
    }

    companion object {
        fun from(source: NotificationPayloadSource): NormalizedNotificationPayload =
            NormalizedNotificationPayload(
                sbnKey = source.sbnKey,
                packageName = source.packageName,
                title = normalizeText(source.title),
                text = normalizeText(source.text),
                category = normalizeText(source.category),
                isClearable = source.isClearable,
                isOngoing = source.isOngoing,
                replyActions = source.replyActions,
            )

        fun fromStatusBarNotification(sbn: StatusBarNotification): NormalizedNotificationPayload {
            val notification = sbn.notification
            val extras = notification.extras
            return from(
                NotificationPayloadSource(
                    sbnKey = sbn.key,
                    packageName = sbn.packageName,
                    title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
                    text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
                    category = notification.category,
                    isClearable = sbn.isClearable,
                    isOngoing = sbn.isOngoing,
                    postTimeUnixMs = sbn.postTime,
                    replyActions = replyActions(notification),
                ),
            )
        }

        private fun normalizeText(value: String?): String? =
            value?.trim()?.takeIf { it.isNotEmpty() }

        private fun replyActions(notification: Notification): List<NotificationReplyAction> {
            val metadata = notification.actions
                ?.mapIndexedNotNull { index, action ->
                    val remoteInputs = action.remoteInputs ?: return@mapIndexedNotNull null
                    if (remoteInputs.isEmpty() || action.actionIntent == null) return@mapIndexedNotNull null
                    NotificationReplyActionMetadata(
                        actionIndex = index,
                        semanticAction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) action.semanticAction else 0,
                        title = action.title?.toString(),
                        resultKeys = remoteInputs.map { it.resultKey },
                        hasActionIntent = action.actionIntent != null,
                    )
                }
                ?: emptyList()
            val actionIds = NotificationReplyActions.stableActionIds(metadata)
            return metadata.map { action ->
                NotificationReplyAction(
                    actionIndex = action.actionIndex,
                    label = normalizeText(action.title) ?: "Reply",
                    actionId = actionIds[action.actionIndex],
                )
            }
        }
    }
}
